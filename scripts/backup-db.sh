#!/usr/bin/env bash
#
# PostgreSQL 논리 백업. 운영 서버(Oracle Cloud)의 호스트 cron에서 매일 돈다.
#
#   0 3 * * * /home/ubuntu/ainewsdigest/scripts/backup-db.sh >> /home/ubuntu/backup.log 2>&1
#
# 03:00인 이유는 생성(07:00)·재시도(07:15)·발송(07:30) 배치와 겹치지 않기 위해서다.
#
# 왜 필요한가: pgdata 볼륨이 날아가면 구독자 전원과 /archive 전체가 사라진다.
# 아카이브는 다시 만들 수 없고, 구독자는 더 나쁘다 — 텔레그램 봇은 상대가 먼저
# /start를 보내기 전에는 말을 걸 수 없으므로(ADR-003) 우리 쪽에서 복구할 방법이
# 아예 없다. 한 명씩 다시 찾아오기를 기다리는 것 말고는 수단이 없다.
#
# 환경변수로 조정한다:
#   BACKUP_DIR       기본 $HOME/ainewsdigest-backups (레포 밖에 둔다 — git 작업과 섞이면 안 된다)
#   RETENTION_DAYS   기본 30. 덤프가 수십 KB라 길게 잡아도 부담이 없고,
#                    짧으면 조용히 진행된 데이터 손상을 되돌릴 창이 사라진다.
#   BACKUP_PAR_URL   OCI Object Storage Pre-Authenticated Request URL (슬래시로 끝나는 접두사).
#                    설정하면 원격 사본도 올린다. 비어 있으면 로컬 사본만 만든다.

set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUP_DIR="${BACKUP_DIR:-$HOME/ainewsdigest-backups}"
RETENTION_DAYS="${RETENTION_DAYS:-30}"

cd "$REPO_DIR"

# DB_USER는 compose와 같은 .env에서 읽는다. 두 곳에 나눠 적으면 언젠가 어긋난다.
if [ -f .env ]; then
	set -a
	# shellcheck disable=SC1091
	. ./.env
	set +a
fi
DB_USER="${DB_USER:-postgres}"
# .env를 Windows에서 만들어 올리면 값 끝에 CR이 붙는다. 그대로 두면 pg_dump가
# 존재하지 않는 사용자로 접속을 시도하고, 에러 메시지에는 CR이 보이지 않아 원인을 찾기 어렵다.
DB_USER="${DB_USER%$'\r'}"

mkdir -p "$BACKUP_DIR"
OUT="$BACKUP_DIR/ainewsdigest-$(date +%Y-%m-%d_%H%M).sql.gz"
TMP="$OUT.partial"

# .partial로 받은 뒤 검증에 통과해야 최종 이름으로 옮긴다.
# 중간에 죽은 파일이 정상 백업인 척 남아 있으면 백업이 없는 것보다 나쁘다.
docker compose exec -T db \
	pg_dump -U "$DB_USER" -d ainewsdigest --clean --if-exists | gzip -9 >"$TMP"

gzip -t "$TMP"

# 스키마가 실제로 들어 있는지 본다. pg_dump는 접속에 실패해도 헤더만 있는
# 짧은 파일을 남길 수 있고, gzip 검사는 그것도 통과시킨다.
if [ "$(gzip -dc "$TMP" | grep -c 'CREATE TABLE' || true)" -lt 4 ]; then
	echo "backup-db: 덤프에 테이블 4개가 보이지 않는다. 파기하고 실패로 끝낸다: $TMP" >&2
	rm -f "$TMP"
	exit 1
fi

mv "$TMP" "$OUT"
chmod 600 "$OUT"
echo "backup-db: $OUT ($(du -h "$OUT" | cut -f1))"

if [ -n "${BACKUP_PAR_URL:-}" ]; then
	# PAR URL은 만료가 있다. 업로드 실패는 로컬 백업까지 실패로 만들지 않되
	# 조용히 넘어가지도 않는다 — cron 로그에 남겨 다음 점검에서 보이게 한다.
	if curl -fsS -X PUT --upload-file "$OUT" "${BACKUP_PAR_URL}$(basename "$OUT")"; then
		echo "backup-db: Object Storage 업로드 완료"
	else
		echo "backup-db: Object Storage 업로드 실패 (PAR 만료 확인). 로컬 사본은 정상." >&2
	fi
fi

find "$BACKUP_DIR" -maxdepth 1 -name 'ainewsdigest-*.sql.gz' -mtime "+$RETENTION_DAYS" -delete
