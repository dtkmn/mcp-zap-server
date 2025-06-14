# Build stage
FROM gradle:8.14.2-jdk21 as builder
WORKDIR /usr/src/app
COPY src ./src
COPY build.gradle .
COPY settings.gradle .
RUN gradle build -x test

# Runtime stage
FROM eclipse-temurin:21-jdk-alpine
WORKDIR /app
COPY --from=builder /usr/src/app/build/libs/mcp-zap-server-*.jar ./app.jar
EXPOSE 7456
ENTRYPOINT ["java", "-Dspring.ai.mcp.server.type=sync", "-jar","/app/app.jar"]