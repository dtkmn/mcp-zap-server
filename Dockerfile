FROM eclipse-temurin:21-jdk-alpine
WORKDIR /app
COPY build/libs/mcp-zap-server-0.0.2-SNAPSHOT.jar ./app.jar
EXPOSE 7456
ENTRYPOINT ["java", "-Dspring.ai.mcp.server.stdio=true", "-Dspring.main.web-application-type=none", "-Dlogging.pattern.console=", "-jar","/app/app.jar"]