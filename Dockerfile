# Build stage
FROM gradle:9.5.0-jdk25 AS builder
WORKDIR /usr/src/app
COPY src ./src
COPY build.gradle .
COPY settings.gradle .
RUN gradle bootJar -x test && \
    boot_jars="$(find build/libs -maxdepth 1 -type f -name '*.jar' \
      ! -name '*-plain.jar' \
      ! -name '*-enterprise-extension.jar' \
      ! -name '*-sample-policy-metadata-extension.jar' \
      ! -name 'mcp-zap-extension-api-*.jar' | sort)" && \
    boot_jar_count="$(printf '%s\n' "${boot_jars}" | sed '/^$/d' | wc -l | tr -d ' ')" && \
    if [ "${boot_jar_count}" != "1" ]; then \
      echo "Expected exactly one executable application JAR, found ${boot_jar_count}: ${boot_jars}" >&2; \
      exit 1; \
    fi && \
    cp "${boot_jars}" /tmp/app.jar

# Runtime stage
FROM eclipse-temurin:25-jre-alpine
LABEL io.modelcontextprotocol.server.name="io.github.dtkmn/mcp-zap-server"
RUN apk add --no-cache curl
WORKDIR /app
COPY --from=builder /tmp/app.jar ./app.jar
EXPOSE 7456
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:7456/actuator/health || exit 1
ENTRYPOINT ["java", "-Dspring.ai.mcp.server.type=sync", "-jar","/app/app.jar"]
