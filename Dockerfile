# football-svc: Spring Boot 이미지 (Oracle A1 = arm64, 로컬은 그 기계 아키텍처로 빌드된다)
#
#   docker build -t factos-backend .
#
# 테스트는 CI가 이미지 빌드 전에 돌린다 (여기서는 jar만 만든다).

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# 의존성만 먼저 받아 레이어 캐시를 살린다 — 소스만 바뀌면 이 단계는 다시 안 돈다
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test \
 && cp "$(ls build/libs/*.jar | grep -v -- '-plain' | head -1)" /app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
# root로 돌리지 않는다. curl은 헬스체크용.
# upgrade: 베이스 이미지가 나온 뒤 패치된 OS 패키지를 받는다 (CI Trivy 게이트가 고칠 수 있는 취약점을 막는다)
RUN apt-get update && apt-get upgrade -y && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 app
COPY --from=build --chown=app /app.jar /app/app.jar
USER app
EXPOSE 8080
# 힙은 컨테이너 메모리 한도의 비율로 (compose의 mem_limit을 바꾸면 따라 바뀐다)
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError" \
    SPRING_PROFILES_ACTIVE=prod
# deploy.sh와 같은 주소 — DB까지 읽는 가벼운 조회
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD curl -fsS "http://127.0.0.1:8080/api/news/clubs/arsenal?size=1" > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
