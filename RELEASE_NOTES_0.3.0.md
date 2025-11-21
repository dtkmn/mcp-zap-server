# Release Notes - Version 0.3.0

**Release Date:** November 22, 2025

## 🚀 Major Features

### GraalVM Native Image Support
- **10x faster startup** (0.6s vs 3-5s) for production deployments
- Reduced memory footprint (~200MB vs ~300MB)
- Optimized for cloud and serverless environments
- Separate Docker builds for development (JVM) and production (native)

### Spring Boot 4.0.0 Upgrade
- Migrated from Spring Boot 3.5.7 to 4.0.0
- Enhanced performance and security features
- Improved actuator endpoints
- Better reactive programming support

### JWT Authentication
- Migrated from JJWT to Spring Security OAuth2 JWT
- GraalVM native image compatible
- Token-based authentication with refresh support
- Multi-client support with `clientId` parameter
- 1-hour access tokens, 7-day refresh tokens

### CSRF Protection
- Disabled for `/mcp` endpoint for MCP protocol compatibility
- Enabled by default in `api-key` and `jwt` modes
- Configurable per security mode
- Comprehensive documentation

## 🛠️ Infrastructure & DevOps

### Docker Compose Profiles
- **Development profile** (`docker-compose.dev.yml`): Fast JVM builds (2-3 min)
- **Production profile** (`docker-compose.prod.yml`): Optimized native builds (20-25 min)
- Quick start scripts: `dev.sh` and `prod.sh`

### Kubernetes Helm Charts
- Complete Helm chart for Kubernetes deployments
- Separate ZAP and MCP server pods
- Horizontal pod autoscaling for MCP servers
- Support for LoadBalancer, NodePort, and Ingress
- Comprehensive values.yaml with all configuration options

### Documentation Site
- GitHub Pages documentation with custom Mr. Robot terminal theme
- Interactive navigation and code samples
- Native image performance guide
- Deployment best practices

## 🔒 Security Enhancements

### Authentication Modes
- **none**: Development only (CSRF disabled for MCP compatibility)
- **api-key**: Simple authentication for trusted environments (CSRF enabled)
- **jwt**: Production-ready with token expiration (CSRF enabled)

### Security Configuration
- Environment-based secrets management
- API key validation
- JWT token blacklist for logout
- Configurable security per environment

## 📚 Documentation

### New Documentation
- `NATIVE_IMAGE_PERFORMANCE.md`: Build optimization strategies
- `.env.example`: Comprehensive environment variable template
- `IMPLEMENTATION_SUMMARY.md`: Security implementation details
- `QUICK_START_SECURITY.md`: Security setup guide
- `SECURITY_FIXES.md`: Security vulnerability resolutions
- Helm chart README with deployment examples

### Updated Documentation
- `README.md`: Enhanced with native image workflows, CSRF details
- `SECURITY.md`: Expanded security features and best practices
- `CHANGELOG.md`: Detailed release history

## 🐛 Bug Fixes

- Fixed deprecated `@AutoConfigureWebTestClient` annotation
- Resolved SecurityContext population in JWT/API key authentication
- Fixed CSRF protection configuration across all modes
- Corrected test imports for Spring Boot 4.0.0 compatibility

## ⚠️ Breaking Changes

### Spring Boot 4.0.0
- Requires Java 17+ (Java 25 recommended)
- Some APIs have changed (see Spring Boot 4.0.0 migration guide)
- `@AutoConfigureWebTestClient` no longer needed (auto-configured)

### Security Configuration
- Default security mode changed to `none` in docker-compose for development
- CSRF protection now mode-dependent (disabled in `none`, enabled in `api-key`/`jwt`)
- Environment variables required for API keys (no hardcoded defaults)

### Docker Images
- Separate Dockerfiles for JVM (`Dockerfile`) and native (`Dockerfile.native`)
- Different build times and startup characteristics
- Production deployments should use native images

## 📊 Performance Improvements

| Metric | JVM (Dev) | Native (Prod) | Improvement |
|--------|-----------|---------------|-------------|
| **Startup Time** | 3-5 sec | 0.6 sec | **10x faster** |
| **Memory Usage** | ~300MB | ~200MB | **33% less** |
| **Build Time** | 2-3 min | 20-25 min | Trade-off |
| **Image Size** | 383MB | 391MB | Similar |

## 🔄 Migration Guide

### From 0.2.x to 0.3.0

1. **Update Spring Boot dependencies** (if building from source)
2. **Choose build profile**: JVM for dev, native for prod
3. **Update environment variables**:
   ```bash
   # Add new JWT variables if using JWT mode
   JWT_ENABLED=true
   JWT_SECRET=your-256-bit-secret
   JWT_ISSUER=mcp-zap-server
   JWT_ACCESS_TOKEN_EXPIRATION=60
   JWT_REFRESH_TOKEN_EXPIRATION=7
   ```
4. **Update Docker Compose**:
   ```bash
   # Development
   docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d
   
   # Production
   docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
   ```
5. **Test CSRF compatibility**: MCP clients should work without CSRF tokens

### For Kubernetes Users
```bash
# Install/upgrade with Helm
helm upgrade --install mcp-zap ./helm/mcp-zap-server \
  --namespace mcp-zap \
  --set mcp.security.mode=jwt \
  --set mcp.security.jwt.secret="$(openssl rand -base64 32)"
```

## 🎯 Next Steps

### Recommended Actions
1. Review the new `NATIVE_IMAGE_PERFORMANCE.md` guide
2. Explore Kubernetes deployment with Helm charts
3. Configure JWT authentication for production
4. Test native image builds for your environment
5. Update CI/CD pipelines for new Docker profiles

### Future Enhancements (0.4.0 Roadmap)
- Rate limiting for API endpoints
- Enhanced audit logging
- Scan result encryption at rest
- Multi-tenancy support
- OAuth2/OIDC integration
- Webhook notifications for scan completion

## 📦 Artifacts

### Docker Images
- JVM: `dtkmn/mcp-zap-server:0.3.0`
- Native: `dtkmn/mcp-zap-server:0.3.0-native`

### Helm Chart
- Chart version: `0.3.0`
- App version: `0.3.0`

### Source Code
- GitHub: https://github.com/dtkmn/mcp-zap-server/releases/tag/v0.3.0

## 🙏 Acknowledgements

Thank you to all contributors and users who provided feedback and reported issues. Special thanks to the Spring Boot and GraalVM teams for their excellent documentation and tooling.

## 📞 Support

- **Documentation**: https://dtkmn.github.io/mcp-zap-server/
- **Issues**: https://github.com/dtkmn/mcp-zap-server/issues
- **Discussions**: https://github.com/dtkmn/mcp-zap-server/discussions
- **Security**: danieltse@gmail.com (private disclosure)

---

**Full Changelog**: https://github.com/dtkmn/mcp-zap-server/compare/v0.2.1...v0.3.0
