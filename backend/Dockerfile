# ---- build stage ----
FROM gradle:8.10-jdk21 AS build
WORKDIR /app
COPY settings.gradle build.gradle ./
RUN gradle dependencies --no-daemon > /dev/null 2>&1 || true
COPY src ./src
RUN gradle bootJar --no-daemon -x test

# ---- runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 quizrun && mkdir -p /app/data && chown quizrun /app/data
COPY --from=build /app/build/libs/*.jar app.jar
USER quizrun
# H2 파일 DB(문제은행) 위치. 운영에서는 DB_URL로 Cloud DB for MySQL을 가리킨다.
VOLUME /app/data
ENV PORT=3000 JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 3000
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s \
  CMD curl -fs http://localhost:${PORT}/api/health || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
