# Security Policy

All security vulnerabilities in this project should be reported responsibly and handled with care. This document outlines the process and scope for reporting and addressing vulnerabilities.

## Supported Versions

| Version    | Supported                                |
| ---------- | ---------------------------------------- |
| `>= 0.3.0` | ✅ Active development; receives security fixes |
| `< 0.3.0`  | ⚠️ Legacy versions; consider upgrading    |

## Security Features

### Authentication & Authorization

- **Multiple Authentication Modes**: Supports `none`, `api-key`, and `jwt` authentication modes
- **API Key Authentication**: Simple bearer token authentication for trusted environments
- **JWT Authentication**: Token-based authentication with expiration and refresh capabilities
- **Header-based Auth**: Supports both `X-API-Key` header and `Authorization: Bearer` token
- **CSRF Protection**: 
  - **Intentionally disabled** for all authentication modes
  - **Reason**: This is an API-only server using token-based authentication (JWT/API keys), not session cookies
  - **Not a security risk**: CSRF attacks only affect cookie-based authentication in browsers
  - **Follows OWASP guidelines**: CSRF protection is not applicable for stateless RESTful APIs
  - See: [OWASP CSRF Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)
- **Configurable Security**: Can be adjusted per environment (development vs production)

### URL Validation & Access Control

- **Whitelist/Blacklist Support**: Control which domains can be scanned
- **Private Network Protection**: Prevents scanning internal/private networks by default
- **Localhost Protection**: Blocks localhost scanning unless explicitly enabled
- **Protocol Validation**: Only HTTP/HTTPS protocols are allowed

### Resource Management

- **Scan Timeouts**: Configurable duration limits for active and spider scans
- **Concurrent Scan Limits**: Prevents resource exhaustion from too many simultaneous scans
- **Thread Limits**: Configurable threading for scanning operations

### Configuration Security

- **Environment-based Secrets**: API keys stored in `.env` files (never committed to git)
- **No Hard-coded Credentials**: All sensitive data loaded from environment variables
- **.gitignore Protection**: `.env` files automatically excluded from version control

## Security Best Practices

### For Deployment

1. **Always use strong API keys**:
   ```bash
   openssl rand -hex 32  # Generate secure random keys
   ```

2. **Never commit `.env` files** to version control

3. **Use HTTPS** for production deployments (configure reverse proxy)

4. **Configure URL whitelist** for production to limit scan targets:
   ```bash
   ZAP_URL_WHITELIST=yourdomain.com,*.yourdomain.com
   ```

5. **Disable localhost/private network scanning** in production:
   ```bash
   ZAP_ALLOW_LOCALHOST=false
   ZAP_ALLOW_PRIVATE_NETWORKS=false
   ```

6. **Set reasonable scan limits**:
   ```bash
   ZAP_MAX_ACTIVE_SCAN_DURATION=30
   ZAP_MAX_CONCURRENT_ACTIVE_SCANS=3
   ```

7. **Use dedicated ZAP instances** - Don't share ZAP across untrusted users

8. **Choose appropriate security mode**:
   ```bash
   # Development: none (CSRF disabled for MCP compatibility)
   MCP_SECURITY_MODE=none
   
   # Production: jwt (CSRF enabled, tokens expire)
   MCP_SECURITY_MODE=jwt
   JWT_ENABLED=true
   JWT_SECRET=<256-bit-secret>
   ```

9. **CSRF Protection Disabled (By Design)**:
   - CSRF is disabled because this is an API-only server with token-based authentication
   - Authentication uses JWT tokens and API keys in HTTP headers, NOT cookies
   - CSRF attacks only affect cookie-based authentication in web browsers
   - This follows industry best practices for RESTful API security
   - Security is provided by strong token authentication, not CSRF tokens

### For Development

1. **Use separate API keys** for development and production
2. **Never use production keys** in development
3. **Review security settings** before deploying
4. **Test with security enabled** even in development

## CSRF Protection - Why It's Disabled

This server **intentionally disables CSRF protection**, and this is the correct security decision for the following reasons:

### What is CSRF?
Cross-Site Request Forgery (CSRF) is an attack where a malicious website tricks a user's browser into making unwanted requests to a different website where the user is authenticated. This works because browsers **automatically** send cookies with requests.

### Why CSRF Protection is Not Needed Here

1. **No Cookies Used**: This server uses JWT tokens and API keys sent in HTTP headers (`Authorization`, `X-API-Key`), NOT cookies. Browsers don't automatically send these headers to other sites.

2. **API-Only Server**: This is a machine-to-machine API, not a web application with HTML forms. CSRF only affects browsers.

3. **Stateless Authentication**: Each request includes explicit credentials. There's no session state that could be exploited.

4. **MCP Protocol**: MCP clients (Claude Desktop, Cursor, mcpo) don't support CSRF token exchange.

### Industry Standards

According to OWASP (Open Web Application Security Project):
> "CSRF protection is not needed for APIs that don't use cookies for authentication."

Reference: [OWASP CSRF Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)

### GitHub Security Alerts

If you receive GitHub security alerts about disabled CSRF protection, this is a **false positive**. Security scanners often flag disabled CSRF without understanding the context. You can safely dismiss these alerts with the explanation that this is an API-only server using token-based authentication.

### Actual Security Measures

Instead of CSRF protection (which doesn't apply), this server uses:
- ✅ Strong JWT secret keys (256-bit minimum)
- ✅ Token expiration (1 hour access tokens)
- ✅ Refresh token rotation
- ✅ Token blacklisting for logout
- ✅ HTTPS in production (via reverse proxy)
- ✅ Input validation and URL whitelisting
- ✅ Rate limiting (recommended for production)

## MCP Security Best Practices Compliance

This server follows the [Model Context Protocol Security Best Practices](https://modelcontextprotocol.io/specification/draft/basic/security_best_practices) with the following implementation status:

### ✅ Fully Compliant

- **Token Passthrough Prevention**: Server validates JWT tokens issued specifically for this server (custom issuer `mcp-zap-server`), not accepting arbitrary third-party tokens
- **Session Hijacking Prevention**: Uses stateless JWT authentication, no HTTP session state or cookies that could be hijacked
- **Local Server Authorization**: HTTP transport requires authentication via JWT or API key for all protected endpoints (`/mcp`)
- **Secure Token Storage**: Tokens are transmitted via HTTP headers only, never in URLs or request bodies

### 🔄 Planned for SaaS Version

- **Granular Scopes**: Current implementation uses wildcard scope `["*"]` for all operations. When offering this as a cloud service, granular scopes will be implemented:
  - `mcp:tools-list`: List available tools
  - `zap:scan-active`: Run active security scans
  - `zap:scan-spider`: Run spider scans
  - `zap:scan-openapi`: Import and scan OpenAPI specs
  - `zap:reports-read`: Generate and retrieve reports
  - `zap:context-manage`: Manage ZAP contexts and authentication
- **Progressive Scope Request**: OAuth-style scope elevation with `WWW-Authenticate` challenges
- **Scope-Based Rate Limiting**: Different rate limits per scope category

**Rationale for Current Design**: For self-hosted open-source deployments, users control both the ZAP instance and MCP clients in a trusted environment. Granular scopes add complexity without security benefit in this model. When transitioning to a multi-tenant SaaS offering, scopes become essential for isolation and access control.

### ⚠️ Not Applicable

- **Confused Deputy Problem**: This server does not act as an OAuth proxy to third-party APIs, so confused deputy attacks are not relevant. The server directly controls its own ZAP instance.

## Known Security Considerations

### ZAP as a Security Tool

- OWASP ZAP is designed for security testing and can be used maliciously
- This server adds authentication and access controls to mitigate risks
- Always run in isolated, controlled environments
- Be aware of legal implications of security scanning

### Network Security

- By default, the server blocks internal network scanning
- Review and configure URL validation settings for your environment
- Consider network-level isolation (VPCs, firewalls) for additional protection

### Data Sensitivity

- Scan results may contain sensitive information about vulnerabilities
- Reports are stored in the configured workspace directory
- Implement file system permissions to protect report data
- Consider encrypting stored reports at rest

## Reporting a Vulnerability

If you believe you have discovered a security issue, **please do NOT open a public GitHub issue**. Instead, follow these steps:

1. **Email**: Send the details to **danieltse@gmail.com**.
2. **Details to include**:
    - Affected version(s) (e.g. `0.1.0-SNAPSHOT` or `0.1.0`).
    - Clear description of the vulnerability and its impact.
    - Steps to reproduce, including code snippets or commands if possible.
    - Suggested fix or mitigation, if you have one.

We aim to:
- Acknowledge receipt within **2 business days**.
- Provide a fix or mitigation plan within **14 calendar days** of acknowledgment.
- Publicly disclose the vulnerability and credit the reporter **after** a fix is released, unless otherwise requested.

## Scope

This policy covers all code in this repository, including:

- Spring Boot application code
- Docker Compose configurations
- CI/CD and build scripts
- Any auxiliary scripts or utilities provided here

## Handling a Report

1. **Confirmation**: We’ll confirm receipt by email and may request additional information.
2. **Investigation**: We’ll reproduce and assess the severity.
3. **Remediation**: We’ll develop, test, and release a patch.
4. **Disclosure**: We’ll coordinate public disclosure and release notes, crediting contributors as agreed.

## Acknowledgments

This policy is modeled after GitHub’s recommended [Security Policy template](https://docs.github.com/en/code-security/getting-started/adding-a-security-policy-to-your-repository).

---

*Last updated: 2025-05-27*
