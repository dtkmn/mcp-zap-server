# Build stage
FROM --platform=$BUILDPLATFORM gradle:9.8.2-jdk25@sha256:ca424744a6a45a575f333edea680696d980999c461be38b1574a4c70d5455f24 AS builder
WORKDIR /usr/src/app
COPY build.gradle settings.gradle gradle.properties ./
COPY gradle ./gradle
COPY src ./src
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
    cp "${boot_jars}" /tmp/app.jar && \
    mkdir -p /tmp/runtime-layout/zap/wrk /tmp/runtime-layout/home/app \
      /tmp/runtime-layout/var/lib/mcp-zap/scan-launch-safety && \
    chmod 0700 /tmp/runtime-layout/var/lib/mcp-zap/scan-launch-safety

# The signed multi-architecture runtime index is kept literal so Dependabot can
# update its digest. This stage also supplies the canonical distroless passwd
# and group files to the healthcheck builder. Dependabot cannot rename this
# repository when the next Debian generation lands; that migration is manual.
FROM gcr.io/distroless/java25-debian13:nonroot@sha256:69514ce1edfe8759f5169870e8f9d4c9f2b831fb0b305a6809a269a25c515a2f AS runtime-base

# Build a shell-free, statically linked HTTP health probe for the target image
# architecture. Only /out/http-healthcheck enters the runtime image.
FROM --platform=$BUILDPLATFORM golang:1.27.3-alpine3.23@sha256:8a5046497cda39860b832ffdbe8dd66018a0f05bb01352f533a36e7e2ced66a0 AS healthcheck-builder
ARG TARGETOS
ARG TARGETARCH
WORKDIR /src/healthcheck
COPY tools/healthcheck/ ./
RUN test -n "${TARGETOS}" && \
    test -n "${TARGETARCH}" && \
    CGO_ENABLED=0 GOOS="${TARGETOS}" GOARCH="${TARGETARCH}" \
      go build -mod=readonly -trimpath -buildvcs=false \
      -ldflags="-s -w -buildid=" \
      -o /out/http-healthcheck .

# Preserve the named UID/GID 1000 identity from the previous image. Distroless
# defaults to UID 65532 and otherwise reports Java user.name as "?" for UID 1000.
COPY --from=runtime-base /etc/passwd /out/passwd
COPY --from=runtime-base /etc/group /out/group
RUN printf 'app:x:1000:1000:Application user:/home/app:/sbin/nologin\n' >> /out/passwd && \
    printf 'app:x:1000:\n' >> /out/group

# Runtime stage
FROM runtime-base
LABEL io.modelcontextprotocol.server.name="io.github.dtkmn/mcp-zap-server"
ENV HOME=/home/app
COPY --from=healthcheck-builder /out/passwd /etc/passwd
COPY --from=healthcheck-builder /out/group /etc/group
COPY --from=builder /tmp/runtime-layout/home/app /home/app
COPY --chown=1000:1000 --from=builder /tmp/runtime-layout/zap/wrk /zap/wrk
COPY --chown=1000:1000 --from=builder /tmp/runtime-layout/var/lib/mcp-zap/scan-launch-safety /var/lib/mcp-zap/scan-launch-safety
WORKDIR /app
COPY --chmod=0444 --from=builder /tmp/app.jar ./app.jar
COPY --chmod=0555 --from=healthcheck-builder /out/http-healthcheck /usr/local/bin/http-healthcheck
USER 1000:1000
EXPOSE 7456
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD ["/usr/local/bin/http-healthcheck", "http://127.0.0.1:7456/actuator/health"]
ENTRYPOINT ["/usr/bin/java", "-Dspring.ai.mcp.server.type=sync", "-jar", "/app/app.jar"]
