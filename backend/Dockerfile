FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,id=convenient-clipboard-maven,target=/root/.m2,sharing=locked \
    mvn -B -U -Daether.connector.connectTimeout=10000 -Daether.connector.requestTimeout=60000 package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -g 10001 app && adduser -D -u 10001 -G app app \
    && mkdir -p /data/files /app && chown -R app:app /data/files /app
WORKDIR /app
COPY --from=build --chown=app:app /build/target/*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
