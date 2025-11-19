---
layout: default
title: Home
---

# MCP ZAP Server Documentation

**Spring AI MCP Server for OWASP ZAP Security Testing**

A Model Context Protocol (MCP) server that provides secure access to OWASP ZAP security scanning tools through Spring AI.

## 🚀 Quick Links

### Getting Started
- [Installation & Setup](#-docker-quick-start)
- [Quick Start Guide](#-docker-quick-start)
- [Docker Deployment](#-docker-quick-start)

### Security & Authentication
- [Security Modes Overview](SECURITY_MODES.html) - Three authentication modes (none/api-key/jwt)
- [JWT Authentication Guide](JWT_AUTHENTICATION.html) - Complete JWT setup and usage
- [Quick Start: JWT Auth](QUICK_START_JWT.html) - 5-minute JWT setup
- [MCP Client Authentication](MCP_CLIENT_AUTHENTICATION.html) - Client-side configuration
- [Authentication Quick Start](AUTHENTICATION_QUICK_START.html) - Fast authentication setup
- [Security Examples](SECURITY_MODE_EXAMPLES.html) - Real-world examples

### Advanced Features
- [AJAX Spider Guide](AJAX_SPIDER.html) - Bypass WAF protection with real browser scanning
- [Kubernetes Deployment](../helm/) - Production Helm charts

### Implementation Details
- [JWT Implementation Summary](JWT_IMPLEMENTATION_SUMMARY.html)
- [Security Modes Implementation](SECURITY_MODES_IMPLEMENTATION_SUMMARY.html)

## 📺 Demo Video

<div style="text-align: center; margin: 2em 0;">
  <iframe width="560" height="315" src="https://www.youtube.com/embed/9_9VqsL0lNw?rel=0&modestbranding=1" frameborder="0" allow="accelerometer; autoplay; encrypted-media; gyroscope; picture-in-picture" allowfullscreen></iframe>
</div>

## 🔧 Available MCP Tools

### Traditional Spider
- `zap_spider` - Start HTTP-based spider scan
- `zap_spider_status` - Check spider scan progress

### AJAX Spider (WAF Bypass)
- `zap_ajax_spider` - Start browser-based spider scan
- `zap_ajax_spider_status` - Check AJAX spider progress
- `zap_ajax_spider_results` - Get discovered URLs
- `zap_ajax_spider_stop` - Stop running scan

### Active Scanning
- `zap_active_scan` - Start vulnerability scanning
- `zap_active_scan_status` - Check scan progress

### Reporting
- `zap_html_report` - Generate HTML security report
- `zap_json_report` - Generate JSON security report

## 🐳 Docker Quick Start

```bash
# Clone repository
git clone https://github.com/dtkmn/mcp-zap-server.git
cd mcp-zap-server

# Configure environment
cp .env.example .env
# Edit .env with your API keys

# Start services
docker-compose up -d

# MCP server available at: http://localhost:7456/mcp
```

## 🔐 Security Modes

| Mode | Use Case | Setup Complexity |
|------|----------|------------------|
| **none** | Local development (STDIO) | ⭐ Easiest |
| **api-key** | Trusted environments | ⭐⭐ Simple |
| **jwt** | Production deployments | ⭐⭐⭐ Advanced |

Default mode: `none` (perfect for local MCP clients like Claude Desktop or Cursor)

## 🌐 Transport Support

- **HTTP Streamable** (default): `protocol: streamable`
- **STDIO**: Set `spring.ai.mcp.server.stdio=true`

## 📦 Repository Structure

```
mcp-zap-server/
├── src/               # Java source code
├── docs/              # Documentation (this site)
├── helm/              # Kubernetes Helm charts
├── docker-compose.yml # Docker development setup
└── build.gradle       # Gradle build configuration
```

## 🤝 Contributing

Contributions welcome! Please read our [Contributing Guidelines](../CONTRIBUTING.md) first.

## 📄 License

MIT License - see [LICENSE](../LICENSE) file for details.

## 🔗 Links

- [GitHub Repository](https://github.com/dtkmn/mcp-zap-server)
- [OWASP ZAP](https://www.zaproxy.org/)
- [Model Context Protocol](https://modelcontextprotocol.io/)
- [Spring AI](https://spring.io/projects/spring-ai)

---

*Last updated: November 2025*