# 운영 서버는 Oracle Cloud Always Free의 ARM64(aarch64) 인스턴스다 (ADR-001).
# eclipse-temurin의 21-jdk-noble / 21-jre-noble은 linux/arm64 매니페스트를 포함하므로
# 서버에서 그대로 빌드된다. latest 태그는 쓰지 않는다 — 재현 불가능한 빌드가 된다.

# ---------- 빌드 스테이지 ----------
FROM eclipse-temurin:21-jdk-noble AS build

WORKDIR /workspace

# 의존성 레이어를 먼저 굳힌다. 소스만 바뀐 커밋은 아래 RUN이 캐시에서 재사용되어
# Gradle 배포판(9.5.1)과 의존성을 다시 내려받지 않는다.
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts ./

# gradlew는 git에 100644(비실행)로 들어 있다. 클론한 리눅스에서 ./gradlew는 곧바로
# permission denied다. 여기서 실행 비트를 세운다 (CI도 같은 이유로 chmod를 한다).
RUN chmod +x gradlew \
 && ./gradlew --no-daemon --console=plain dependencies --configuration runtimeClasspath

COPY src src

# 테스트를 여기서 돌리지 않는다. 빌드 컨테이너 안에는 Docker 데몬이 없어
# Testcontainers PostgreSQL(ADR-011)이 뜨지 못한다. 테스트는 CI와 로컬에서 돈다.
#
# 산출물을 버전 없는 고정 경로로 옮긴다. build/libs/*.jar를 그대로 COPY하면 버전을 올릴 때마다
# Dockerfile을 고쳐야 하거나, bootJar 대신 build를 돌린 순간 -plain.jar까지 두 개가 잡혀 깨진다.
RUN ./gradlew --no-daemon --console=plain bootJar \
 && find build/libs -name '*.jar' ! -name '*-plain.jar' -exec cp {} /workspace/app.jar \;

# ---------- 실행 스테이지 ----------
FROM eclipse-temurin:21-jre-noble

# 스케줄러는 @Scheduled(zone = "Asia/Seoul")로 시각을 못박지만, 컨테이너 기본 타임존은
# UTC라 로그 타임스탬프가 9시간 어긋나 읽힌다. 장애를 새벽에 봐야 하는 서비스다.
ENV TZ=Asia/Seoul

# non-root로 돌린다. 앱은 파일을 쓰지 않으므로 홈 디렉터리도 필요 없다.
RUN groupadd --system --gid 1001 app \
 && useradd --system --uid 1001 --gid app --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app

COPY --from=build /workspace/app.jar /app/app.jar

USER app
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
