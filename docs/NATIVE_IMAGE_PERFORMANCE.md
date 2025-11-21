# Performance Optimization for Native Image Builds

## Why Native Builds are Slow (20+ minutes)

Native image compilation is CPU-intensive because GraalVM:
1. Analyzes entire classpath at build time
2. Performs aggressive dead code elimination
3. Optimizes method calls (ahead-of-time compilation)
4. Generates platform-specific machine code
5. Computes all reachability for reflection/resources

## Optimization Strategies

### 1. Use JVM for Development ⚡ (RECOMMENDED)

```bash
# Fast builds (2-3 min), good enough startup (3-5 sec)
./dev.sh
```

**Trade-off:** 3-5s startup vs 0.6s - acceptable for dev!

### 2. Multi-Core Builds (if you must build native)

Add to `Dockerfile.native`:
```dockerfile
RUN ./gradlew nativeCompile --no-daemon --parallel --max-workers=8
```

**Savings:** ~20-30% faster on multi-core CPUs

### 3. Docker BuildKit Cache

Enable BuildKit for layer caching:
```bash
export DOCKER_BUILDKIT=1
docker compose -f docker-compose.yml -f docker-compose.prod.yml build --build-arg BUILDKIT_INLINE_CACHE=1
```

**Savings:** Incremental builds ~10-15 min (if only code changed)

### 4. GitHub Actions for CI/CD

Build native images in CI/CD only (16-core runners):
```yaml
# .github/workflows/release.yml
- name: Build Native Image
  run: ./gradlew nativeCompile -Pnative
```

Developers use JVM locally, CI builds native for releases.

### 5. Cloud Build Services

Use cloud services with powerful machines:
- **AWS CodeBuild**: 72 vCPU instances → ~8-10 min
- **GitHub Actions**: 4-core runners → ~15-18 min
- **Google Cloud Build**: 32 vCPU → ~10-12 min

## Recommended Workflow

```bash
# Daily development (fast)
./dev.sh                    # 2-3 min builds, iterate quickly

# Before release (slow but optimized)
./prod.sh                   # 20+ min, or let CI/CD handle it

# Or manually choose
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d    # Dev
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d   # Prod
```

## When Native Image is Worth It

✅ **Use Native when:**
- Production serverless (AWS Lambda, Google Cloud Run)
- Kubernetes with frequent pod restarts
- Microservices with auto-scaling
- CI/CD builds (not local dev)

❌ **Skip Native when:**
- Local development (use JVM!)
- Long-running servers (startup doesn't matter)
- Resource-constrained build machines
- Quick iteration needed

## Build Time Comparison

| Environment | Cores | Build Time |
|-------------|-------|------------|
| MacBook M1/M2 | 8-10 | 20-25 min |
| AWS c6i.4xlarge | 16 | 10-12 min |
| GitHub Actions | 4 | 15-18 min |
| Local Docker | 4-8 | 20-30 min |

**Bottom line:** Use JVM for dev (2-3 min), native for prod releases only!
