# 빌드 컨텍스트는 저장소 루트 (data/seed, data/scenario가 필요하므로)
#   docker build -f backend/Dockerfile -t mz-erp-backend .

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY data/seed data/seed
COPY data/scenario data/scenario
COPY backend/gradlew backend/gradlew
COPY backend/gradle backend/gradle
COPY backend/build.gradle.kts backend/settings.gradle.kts backend/
WORKDIR /workspace/backend
# 의존성만 먼저 받아 레이어 캐시 (소스만 바뀌면 이 단계는 재사용)
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY backend/src src
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /workspace/backend/build/libs/app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
