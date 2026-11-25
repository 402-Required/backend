# ---- 1단계: 빌드 ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# 의존성 레이어를 먼저 받아 두면 소스만 바뀔 때 재빌드가 빨라진다
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew \
    && ./gradlew --no-daemon dependencies > /dev/null

COPY src src
RUN ./gradlew --no-daemon bootJar -x test

# ---- 2단계: 실행 ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# plain jar(-plain.jar)가 아닌 실행 가능한 jar만 복사
COPY --from=build /app/build/libs/*-SNAPSHOT.jar app.jar

# Render 무료 인스턴스(RAM 512MB)에 맞춘 JVM 설정
ENV JAVA_TOOL_OPTIONS="-Xmx350m -Xss512k -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m"

# Render가 PORT 환경변수를 주입한다 (application.yml의 server.port=${PORT:8080})
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
