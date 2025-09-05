# Build stage
FROM gradle:9.0.0-jdk21 as builder
WORKDIR /usr/src/app
COPY src ./src
COPY build.gradle .
COPY settings.gradle .
RUN gradle build -x test

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl
WORKDIR /app
COPY --from=builder /usr/src/app/build/libs/mcp-zap-server-*.jar ./app.jar
EXPOSE 7456
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:7456/actuator/health || exit 1
ENTRYPOINT ["java", "-Dspring.ai.mcp.server.type=sync", "-jar","/app/app.jar"]
