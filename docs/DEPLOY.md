# 배포·운영 런북

Oracle Cloud Always Free(ARM64) 인스턴스 한 대에 Spring Boot + PostgreSQL + Caddy를
Docker Compose로 올린다(ADR-001). **자동 배포(CD)는 MVP 범위 밖이다** — 배포는 이 문서의
절차대로 서버에서 수동으로 실행한다.

위에서부터 순서대로 진행한다. 4장(외부 서비스 계정)만 1~3장과 병렬로 해도 된다.

## 이 구성에서 열리는 것

```
인터넷 ──443/80──▶ Caddy(컨테이너) ──compose 네트워크──▶ app:8080 ──▶ db:5432
                    Let's Encrypt 자동                   (외부 노출 없음)

app ──아웃바운드만──▶ OpenAI · Telegram(롱폴링) · HN Algolia · RSS · 기사 크롤링 · healthchecks.io
```

텔레그램은 웹훅이 아니라 롱폴링이므로(ADR-008) **인바운드가 필요 없다.** 80/443을 여는 이유는
오직 랜딩·아카이브 웹 화면(ADR-004) 때문이다.

## 월 비용

| 항목 | 비용 |
|---|---|
| OCI (Always Free 한도 내, PAYG) | 0원 |
| OpenAI `gpt-5-mini` | 약 600원 |
| healthchecks.io | 0원 |
| 도메인 (연 1.5만원 가정) | 약 1,250원 |
| **합계** | **약 1,850원** — PRD 상한 5,000원 내 |

---

## 1장. OCI 테넌시 준비

### 1.1 홈 리전 확인

콘솔 우측 상단 리전 메뉴에서 `(홈)`이 붙은 리전을 확인한다. **Always Free 컴퓨트와 블록 볼륨은
홈 리전에서만 무료다.** 다른 리전에 만들면 과금된다. 홈 리전은 변경할 수 없다.

### 1.2 Pay As You Go로 업그레이드 — 인스턴스 생성보다 **먼저** 한다

Billing & Cost Management → Upgrade and Payment. 해외결제 카드를 등록한다.
Always Free 한도 안에서만 쓰면 청구는 0원이다.

전환하는 이유가 둘이다.

1. **유휴 회수 회피.** Oracle은 7일간 CPU·네트워크·메모리 95th percentile이 모두 20% 미만인
   인스턴스를 유휴로 보고 회수한다. 이 서비스는 하루 두 번 몇 분 도는 배치라 거의 확실히
   유휴로 판정된다. PAYG 계정은 이 정책에서 제외된다.
2. **A1 재고 확보 확률.** PAYG 계정이 Always Free 계정보다 ARM 인스턴스를 먼저 잡는다는
   보고가 많다. 그래서 3장(인스턴스 생성)보다 앞에 둔다.

### 1.3 예산 알림 — PAYG의 필수 안전장치

Billing → Budgets에서 월 예산 `$1`, 알림 임계값 50% / 100%.

정상 상태의 청구액은 0원이다. $1을 넘었다는 것은 **뭔가가 Always Free 한도 밖에 있다는 뜻**이므로
그 자체가 알람이다. PAYG는 한도를 넘는 순간 실제로 돈이 나가기 때문에 이 설정 없이 운영하지 않는다.

### 1.4 계정 보호

- 루트 계정 MFA 활성화 (Identity → 내 프로필 → Multi-Factor Authentication)
- Limits, Quotas and Usage에서 `Compute` / `VM.Standard.A1.Flex` 가용 수량 확인

### 1.5 2026년 한도 변경 — 반드시 알고 시작한다

**2026년 6월 15일부로 Always Free Ampere A1 한도가 4 OCPU / 24 GB에서 2 OCPU / 12 GB로 반감됐다.**
Oracle은 공지 없이 문서만 수정했다. 지금 만드는 계정은 2 OCPU / 12 GB가 상한이다.

이 앱에는 충분하다. 가장 무거운 구간은 런타임이 아니라 서버에서 도는 Gradle 빌드이고,
2 OCPU / 12 GB로 3~6분이면 끝난다.

---

## 2장. 네트워크 (VCN)

### 2.1 VCN 생성

Networking → VCN Wizard → **Create VCN with Internet Connectivity**.
퍼블릭 서브넷 + 인터넷 게이트웨이 + 라우트 테이블이 한 번에 만들어진다. 기본값 그대로 둔다.

### 2.2 Security List 인그레스 규칙

| 포트 | 소스 | 용도 |
|---|---|---|
| 22/TCP | 내 공인 IP `/32` (기본값은 `0.0.0.0/0`) | SSH |
| 80/TCP | `0.0.0.0/0` | ACME HTTP-01 챌린지 + HTTPS 리다이렉트 |
| 443/TCP | `0.0.0.0/0` | 랜딩 · 아카이브 |

- **8080은 열지 않는다.** 이 앱에는 Spring Security도 인증도 없어(PRD MVP 제외)
  8080이 열리는 즉시 익명 공개다. 외부에서 들어오는 웹 트래픽은 Caddy만 받는다.
- 80을 닫으면 안 된다. Let's Encrypt HTTP-01 챌린지가 80으로 온다.
- 22를 `/32`로 좁힐 때: 집 IP가 바뀌면 잠긴다. 복구 경로는 콘솔의 **Cloud Shell** 또는
  인스턴스 **시리얼 콘솔**이다. 둘 다 브라우저에서 되므로 잠겨도 복구는 가능하다.

### 2.3 예약 공인 IP

Networking → IP Management → Reserved Public IPs에서 예약 IP를 만들어 인스턴스 VNIC에 붙인다.

기본값인 임시(ephemeral) IP는 인스턴스를 지우면 함께 사라진다. 그러면 도메인 A 레코드를
다시 잡아야 하고, 그 사이 Caddy는 인증서 갱신에 실패한다. 예약 IP는 Always Free 범위 안이다.

---

## 3장. 인스턴스 생성

### 3.1 SSH 키페어

```powershell
# Windows PowerShell
ssh-keygen -t ed25519 -C "ainewsdigest" -f $env:USERPROFILE\.ssh\oci_ainewsdigest
```

`.pub` 파일 내용을 인스턴스 생성 화면에 붙여넣는다.

### 3.2 인스턴스 설정

Compute → Instances → Create Instance

| 항목 | 값 |
|---|---|
| Image | Canonical Ubuntu 24.04 **(aarch64)** |
| Shape | `VM.Standard.A1.Flex` — **2 OCPU / 12 GB** (1.5절 참고) |
| Boot volume | 50 GB, VPU 10 (Balanced) — 무료 총량 200GB 내 |
| Network | 2장에서 만든 퍼블릭 서브넷, 공인 IPv4 할당 |
| SSH key | 3.1의 `.pub` |

### 3.3 IMDSv2 강제 — 건너뛰지 말 것

Advanced options → Instance Metadata Service → **Require IMDSv2**.

`ADR-017`이 크롤러 레벨에서 `169.254.169.254`(인스턴스 메타데이터)를 차단하지만,
그 ADR 스스로 **DNS rebinding은 막지 못한다**고 한계를 적어두었다. 크롤링 대상 URL은
HN에 아무나 올린 것이고, 받아온 내용은 요약을 거쳐 공개 아카이브에 게시되고 구독자
전원에게 발송된다. 유출 경로까지 완성돼 있는 구조다. IMDSv1을 끄면 그 잔여 위험이 사라진다.

### 3.4 "Out of host capacity" 대응

도쿄·오사카는 가용성 도메인(AD)이 1개뿐이라 AD를 바꾸는 우회는 없다. 순서대로 시도한다.

1. **Fault Domain 바꿔 재시도** — FD-1 / FD-2 / FD-3
2. **작게 잡고 나중에 키우기** — `1 OCPU / 6 GB`로 먼저 확보한 뒤 리사이즈
3. **시간대 바꾸기** — 반납이 몰리는 현지 새벽(한국 시각과 거의 같다)
4. **재시도 루프** — 콘솔 새로고침 대신 CLI에 맡긴다

```bash
# 콘솔에서 성공 파라미터(compartment/subnet/image OCID, shape config)를 확인해 launch.json을 만든 뒤:
until oci compute instance launch --from-json file://launch.json; do
  echo "$(date) 재시도"; sleep 60
done
```

---

## 4장. 외부 서비스 계정

1~3장과 병렬로 진행할 수 있다. **6장(배포)에 들어가기 전에는 전부 끝나 있어야 한다** —
`.env`를 채우려면 이 값들이 필요하다.

### 4.1 도메인

도메인을 구입하고 A 레코드를 2.3의 예약 공인 IP로 지정한다.

> **Cloudflare DNS를 쓴다면**: 처음에는 프록시(주황 구름)를 **OFF**로 둔다. 켜져 있으면
> Caddy의 HTTP-01 챌린지가 Cloudflare에서 끊겨 인증서가 발급되지 않는다. TLS가 정상
> 발급된 것을 확인한 뒤 켜고, SSL 모드를 **Full (strict)** 로 설정한다.

`dig +short <도메인>`이 예약 IP를 돌려주는 것을 확인하고 6장으로 넘어간다.

### 4.2 OpenAI

1. platform.openai.com → 결제 수단 등록 → 크레딧 충전
2. **Usage limits에서 월 하드 캡을 건다 (예: $3).** 알림 이메일도 켠다.
   PRD의 예산 상한이 월 5,000원인데, 종량 과금이라 사용량이 튀면 비용도 튄다(ADR-002).
   **이 캡이 사실상 유일한 비용 브레이크다.**
3. `gpt-5-mini` 접근 확인 (`application.yml`의 `ainewsdigest.curation.openai.model`)
4. 프로젝트 스코프 API 키를 `ainewsdigest-prod` 이름으로 발급 → `OPENAI_API_KEY`

### 4.3 Telegram

1. @BotFather → `/newbot` → 토큰(`TELEGRAM_BOT_TOKEN`)과 username(`TELEGRAM_BOT_USERNAME`) 확보
2. `/setdescription`, `/setabouttext`, `/setuserpic`, `/setcommands`
   ```
   start - 구독 시작
   stop - 구독 해지
   help - 사용 안내
   ```
3. 봇에게 `/start`를 보낸 뒤 본인 `chat_id`를 확인해 `ADMIN_CHAT_ID`에 넣는다.
   ```bash
   curl -s "https://api.telegram.org/bot<TOKEN>/getUpdates" | grep -o '"id":[0-9]*'
   ```
   **이 확인은 배포 전에 한다.** 앱이 떠 있으면 폴러가 업데이트를 먼저 소비해 여기서 아무것도
   보이지 않고, 동시에 붙으면 양쪽 다 409를 받는다.

### 4.4 healthchecks.io (ADR-010 데드맨스위치)

| 항목 | 값 |
|---|---|
| Schedule | cron `30 7 * * *` |
| Timezone | `Asia/Seoul` |
| Grace | 60분 |
| 알림 채널 | 이메일 또는 별도 텔레그램 |

Ping URL을 `HEALTHCHECK_URL`에 넣는다.

이게 필요한 이유: `오늘의 AI 뉴스는 없습니다`가 정상 동작이라(ADR-013) **침묵과 장애가 구분되지 않는다.**
실패 알림만으로는 "앱이 죽어서 알림조차 못 보내는" 상황을 잡지 못한다. 성공 시에만 핑을 보내는
역방향 감시가 있어야 그 사각지대가 사라진다.

---

## 5장. 서버 초기 설정

```bash
ssh -i ~/.ssh/oci_ainewsdigest ubuntu@<예약IP>
```

### 5.1 기본 패키지와 시각

```bash
sudo apt-get update && sudo apt-get upgrade -y
sudo apt-get install -y unattended-upgrades git
sudo dpkg-reconfigure --priority=low unattended-upgrades

sudo timedatectl set-timezone Asia/Seoul
```

컨테이너는 `TZ=Asia/Seoul`로 고정돼 있지만(Dockerfile) 호스트가 UTC면 `journalctl`·cron·
`docker logs`의 타임스탬프가 9시간 어긋나 읽힌다. 새벽 장애를 봐야 하는 서비스다.

### 5.2 Docker Engine + Compose plugin

```bash
sudo apt-get install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

sudo usermod -aG docker ubuntu
sudo systemctl enable --now docker
```

그룹 변경을 적용하려면 로그아웃 후 다시 접속한다.

### 5.3 방화벽 — 정확히 알아야 할 함정

OCI Ubuntu 이미지는 `iptables` INPUT 체인 끝에 REJECT가 있고 22만 열려 있다.
**그런데 Docker가 게시(publish)한 포트는 `nat/PREROUTING` DNAT + FORWARD 경로를 타서
INPUT 체인을 우회한다.** 즉:

- Caddy의 80/443 → **OCI Security List만 열면 바로 접속된다. `iptables` 작업 불필요.**
- 뒤집으면 이게 위험이다 → **8080을 `0.0.0.0`에 게시하면 호스트 방화벽이 막아주지 못한다.**
  그래서 `docker-compose.yml`이 8080을 `127.0.0.1`에만 묶는다. 이 바인딩을 되돌리지 말 것.

확인:

```bash
docker compose ps --format 'table {{.Service}}\t{{.Ports}}'
# app 의 PORTS가 127.0.0.1:8080->8080/tcp 인지 본다. 0.0.0.0:8080 이면 잘못된 것이다.
```

호스트에서 직접 도는 서비스를 나중에 추가한다면 그때는 INPUT 규칙이 필요하다:

```bash
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport <포트> -j ACCEPT
sudo netfilter-persistent save
```

---

## 6장. 최초 배포

```bash
git clone https://github.com/JongGurlHan/AINewsDigest.git ~/ainewsdigest
cd ~/ainewsdigest

cp .env.example .env
nano .env          # 4장에서 모은 값과 DOMAIN, DB_PASSWORD를 채운다
chmod 600 .env     # 시크릿 파일이다. 기본 644로 두지 않는다.

docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
docker compose logs -f app
```

빌드는 서버에서 Gradle을 돌리므로 첫 회 3~6분 걸린다. 의존성 레이어는 이후 캐시된다.
CI 이미지를 쓰지 않는 이유는 GitHub 러너가 x86_64이고 이 서버가 ARM64이기 때문이다(ADR-001).

`.env`에서 반드시 바꿀 것:

| 키 | 주의 |
|---|---|
| `DB_PASSWORD` | `.env.example`의 `change-me`를 그대로 두지 말 것 |
| `DOMAIN` | 비어 있으면 Caddy가 기동에 실패한다 (의도된 동작) |
| `DB_URL` | compose를 쓰면 `jdbc:postgresql://db:5432/ainewsdigest` 그대로 둔다 |

---

## 7장. 배포 검증

순서대로 전부 통과해야 배포가 끝난 것이다.

```bash
# 1. 컨테이너 세 개가 모두 살아 있는가
docker compose ps
#    app / db / caddy 가 Up, app 의 PORTS는 127.0.0.1:8080->8080/tcp

# 2. Flyway 마이그레이션이 적용됐는가
docker compose logs app | grep -i flyway
#    "Successfully applied 1 migration" 이 보여야 한다.
#    ddl-auto: validate 라 스키마가 어긋나면 앱이 아예 뜨지 않는다(ADR-011).

# 3. 인증서가 발급됐는가
docker compose logs caddy | grep -i certificate

# 4. HTTPS 응답
curl -I https://<도메인>/          # 200
curl -I http://<도메인>/           # 308 → https 리다이렉트

# 5. 8080이 외부에 열려 있지 않은가 (서버 밖에서 실행)
curl --max-time 5 http://<예약IP>:8080/   # 반드시 timeout 또는 refused 여야 한다
```

6. **텔레그램 왕복** — `https://t.me/<봇username>?start=web` 열고 `/start` →
   환영 메시지 + 최근 다이제스트 수신. `/stop` → 해지 확인 후 다시 `/start`.

7. **파이프라인 수동 1회** — 다음 아침을 기다리지 않고 전 구간을 검증한다.

```bash
docker compose run --rm app --ainewsdigest.run=generate --ainewsdigest.telegram.polling.enabled=false
# → https://<도메인>/archive 에 오늘 항목이 생성됐는지 확인

docker compose run --rm app --ainewsdigest.run=send --ainewsdigest.telegram.polling.enabled=false
# → 텔레그램 수신 + healthchecks.io 체크가 up 으로 전환
```

> **`--ainewsdigest.telegram.polling.enabled=false`를 빠뜨리지 말 것.** 운영 인스턴스의 폴러와
> 이 일회성 컨테이너의 폴러가 같은 봇 토큰으로 `getUpdates`에 붙으면 텔레그램이 양쪽 모두에
> 409를 돌려준다. 서로를 끊어 그동안 `/start`·`/stop`이 처리되지 않는데, 폴러는 연속 20회
> (약 10분)에 도달해야 ERROR를 내므로 조용히 죽은 채로 지나간다.
> `docker compose run --rm`은 포트를 게시하지 않으므로 8080 충돌은 없다.

8. **재부팅 복원력** — 이 서비스의 철학("사람이 개입해야만 복구되는 구조는 만들지 않는다")의
   실제 검증이다.

```bash
sudo reboot
# 2분 뒤 재접속
docker compose ps    # 세 컨테이너가 사람 개입 없이 Up 이어야 한다
```

9. `README.md`의 서비스 URL 플레이스홀더를 실제 도메인으로 교체하고 커밋한다.

---

## 8장. 재배포와 롤백

PRD가 CD를 제외했으므로 이것이 정식 배포 경로다.

```bash
cd ~/ainewsdigest
git pull
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
docker compose logs -f app        # Flyway와 기동 로그 확인
```

**롤백:**

```bash
git checkout <직전 커밋>
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

`pgdata` 볼륨은 유지되므로 데이터는 남는다. 다만 **Flyway가 이미 적용한 마이그레이션은
코드를 되돌려도 되돌아가지 않는다.** `ddl-auto: validate`라 구버전 엔티티가 신버전 스키마와
어긋나면 앱이 아예 뜨지 않는다. 스키마를 바꾸는 변경의 롤백은 코드 되돌리기가 아니라
**되돌리는 마이그레이션을 새로 추가하는 방식**으로 처리한다.

---

## 9장. 백업과 복원

### 9.1 cron 등록 (최초 1회)

```bash
# 저장소에 실행 비트(100755)로 들어 있다. 아니면 아래 한 줄을 먼저 실행한다.
ls -l scripts/backup-db.sh
crontab -e
```

> cron은 실패해도 아무 말을 하지 않는다. 등록 다음 날 `~/backup.log`와 백업 디렉터리를
> 반드시 확인한다 — "매일 돌고 있다고 믿었는데 한 번도 안 돌았다"가 백업에서 가장 흔한 실패다.
```
0 3 * * * /home/ubuntu/ainewsdigest/scripts/backup-db.sh >> /home/ubuntu/backup.log 2>&1
```

03:00인 이유는 생성(07:00)·재시도(07:15)·발송(07:30) 배치와 겹치지 않기 위해서다.
스크립트는 `$HOME/ainewsdigest-backups`에 30일치를 보관하고, 덤프에 테이블 4개가
들어 있는지 확인한 뒤에만 최종 파일로 남긴다.

원격 사본까지 두려면 OCI Object Storage(무료 20GB)에 Pre-Authenticated Request를 만들어
`.env`에 `BACKUP_PAR_URL`을 추가한다. Instance Principal(Dynamic Group + Policy)보다
설정이 훨씬 짧다.

### 9.2 복원

```bash
cd ~/ainewsdigest
docker compose stop app          # ddl-auto: validate 가 도는 중에 스키마를 갈아엎지 않는다
gzip -dc ~/ainewsdigest-backups/ainewsdigest-YYYY-MM-DD_HHMM.sql.gz \
  | docker compose exec -T db psql -U postgres -d ainewsdigest
docker compose start app
```

덤프는 `--clean --if-exists`로 뜨므로 볼륨을 지울 필요 없이 덮어쓴다.

### 9.3 분기 1회 복원 훈련

한 번도 복원해보지 않은 백업은 백업이 아니다. 운영 DB를 건드리지 않고 확인한다.

```bash
docker compose exec -T db createdb -U postgres restore_test
gzip -dc <최신 백업> | docker compose exec -T db psql -U postgres -d restore_test
docker compose exec -T db psql -U postgres -d restore_test -c 'select count(*) from subscriber'
docker compose exec -T db dropdb -U postgres restore_test
```

---

## 10장. 정기 점검

| 주기 | 항목 |
|---|---|
| 매일 | healthchecks.io에서 알림이 오지 않았는지 (조용하면 정상) |
| 주 1회 | `docker compose ps`, `df -h`, OpenAI usage 대시보드, `tail ~/backup.log` |
| 월 1회 | OCI Budgets 청구액 0원 확인, `apt upgrade` 후 재부팅 |
| 분기 | 9.3 복원 훈련, `.env` 시크릿 로테이션 검토 |

재부팅은 안심하고 해도 된다 — 세 서비스 모두 `restart: unless-stopped`다.

---

## 11장. 장애 대응

### 아침에 다이제스트가 오지 않았다

```bash
cd ~/ainewsdigest
docker compose ps
docker compose logs --since 24h app | grep -iE 'error|exception|failed'
```

수동 재실행(7장 7번과 같은 명령). 두 단계는 독립이다.

```bash
docker compose run --rm app --ainewsdigest.run=generate --ainewsdigest.telegram.polling.enabled=false
docker compose run --rm app --ainewsdigest.run=send --ainewsdigest.telegram.polling.enabled=false
```

`send`는 안전하게 반복할 수 있다. 이미 SUCCESS 로그가 있는 구독자는 건너뛴다(ADR-014).
전원이 실패한 날에는 `sent_at`이 비어 있으므로 재실행이 막히지 않는다(ADR-018).

### `/start`에 봇이 반응하지 않는다

409 충돌을 먼저 의심한다. 폴링을 끄지 않은 일회성 컨테이너가 떠 있는지 확인한다.

```bash
docker compose ps -a | grep run-
docker compose logs app | grep -i 409
```

떠 있는 임시 컨테이너를 정리하고 `docker compose restart app`.

### 인증서가 갱신되지 않는다

```bash
docker compose logs caddy | tail -50
```

흔한 원인 셋:
1. Security List에서 80이 닫혔다 (HTTP-01 챌린지 경로)
2. Cloudflare 프록시가 켜져 있다 (4.1절)
3. `caddy_data` 볼륨을 지워 rate limit(도메인당 주 5회)에 걸렸다 — 이 경우 일주일 기다려야 한다

### 디스크가 찼다

```bash
df -h
docker system df
docker image prune -f       # 재빌드로 쌓인 dangling 이미지
```

로그는 `docker-compose.prod.yml`이 컨테이너당 30MB로 제한한다. 그래도 찬다면
`~/ainewsdigest-backups`와 빌드 캐시를 본다.

### 인스턴스가 정지됐다

PAYG로 전환했다면(1.2절) 유휴 회수 대상이 아니다. Always Free 상태로 남아 있다가
정지된 것이라면 콘솔에서 다시 시작하고 1.2절을 실행한다.

---

## 관련 문서

- [ADR.md](ADR.md) — ADR-001(실행 환경), ADR-008(롱폴링), ADR-010(감시), ADR-017(SSRF 방어)
- [ARCHITECTURE.md](ARCHITECTURE.md) — 스케줄러 시각과 트랜잭션 경계
- [PRD.md](PRD.md) — 예산 상한과 MVP 제외 범위
