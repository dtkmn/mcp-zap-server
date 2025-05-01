FROM eclipse-temurin:21-jdk-alpine
WORKDIR /app
COPY build/libs/mcp-zap-server-*.jar ./app.jar
EXPOSE 7456
ENTRYPOINT ["java", "-Dspring.ai.mcp.server.type=sync", "-jar","/app/app.jar"]