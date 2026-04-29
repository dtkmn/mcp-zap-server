---
title: "Security Policy"
editUrl: false
description: "Responsible vulnerability reporting, supported versions, security controls, and operator responsibilities."
---
All security vulnerabilities in this project should be reported responsibly and handled with care. This document outlines the process and scope for reporting and addressing vulnerabilities.

## Supported Versions

| Version    | Supported                                |
| ---------- | ---------------------------------------- |
| `>= 0.6.1` | ✅ Active development; receives security fixes |
| `< 0.6.1`  | ⚠️ Legacy versions; consider upgrading    |

## Security Features

### Authentication & Authorization

- **Multiple Authentication Modes**: Supports `none`, `api-key`, and `jwt` authentication modes
- **API Key Authentication**: Simple bearer token authentication for trusted environments
- **JWT Authentication**: Token-based authentication with expiration and refresh capabilities
- **Header-based Auth**: Supports both `X-API-Key` header and `Authorization: Bearer` token
- **Tool-Scope Authorization**: Per-tool scope checks for API-key and JWT callers, including `mcp:tools:list` and `zap:*` scopes
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
   
   # Production: jwt (CSRF intentionally disabled, tokens expire)
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
- ✅ Per-tool authorization scopes with startup validation for missing MCP tool mappings
- ✅ HTTPS in production (via reverse proxy)
- ✅ Input validation and URL whitelisting
- ✅ Rate limiting, workspace quotas, and backpressure controls

## MCP Security Best Practices Compliance

This server tracks the [Model Context Protocol Security Best Practices](https://modelcontextprotocol.io/docs/tutorials/security/security_best_practices) with the following implementation status:

### ✅ Implemented Controls

- **Token Passthrough Prevention**: Server validates JWT tokens issued specifically for this server (custom issuer `mcp-zap-server`), not accepting arbitrary third-party tokens
- **Session Authentication Boundary**: MCP session IDs are transport state only. Authentication is checked from request credentials, not from the session ID alone.
- **Local Server Authorization**: HTTP transport requires authentication via JWT or API key for protected endpoints (`/mcp`)
- **Secure Token Storage**: Tokens are transmitted via HTTP headers only, never in URLs or request bodies
- **Scope Minimization**: API-key and JWT clients can be constrained with per-tool scopes. The server has an authoritative tool-to-scope registry and fails startup if an exposed MCP tool lacks a mapping.
- **Wildcard Migration Control**: Legacy `*` scopes remain available only when `MCP_SECURITY_AUTHORIZATION_ALLOW_WILDCARD=true`. Shared or production deployments should disable wildcard scopes after explicit client grants are configured.
- **Abuse Protection**: Rate limiting, workspace quotas, backpressure decisions, and audit events are available for shared deployments.
- **SSRF Reduction for Scan Targets**: URL validation blocks localhost/private network targets by default and supports allowlists/blacklists. Operators should still enforce network egress policy for defense in depth.

### ⚠️ Operator Responsibilities

- Use HTTPS in front of `/mcp` for any non-local deployment.
- Keep the ZAP API private and reachable only from the MCP server.
- Keep streamable HTTP session affinity enabled when routing to multiple MCP replicas.
- Disable wildcard scopes and assign least-privilege scope sets per client.
- Keep actuator metrics, Prometheus, and audit endpoints on private or authenticated access paths.
- Use network egress controls where scan-target validation alone is not enough.

### ⚠️ Not Applicable

- **OAuth Confused Deputy Flow**: This server does not act as an OAuth proxy to third-party APIs and does not provide dynamic client registration for third-party authorization servers. If you add an OAuth proxy layer around it, that layer needs its own consent, redirect URI, state, and CSRF controls.

## Known Security Considerations

### ZAP as a Security Tool

- OWASP ZAP is designed for security testing and can be used maliciously
- This server adds authentication and access controls to mitigate risks
- Always run in isolated, controlled environments
- Be aware of legal implications of security scanning

### Network Security

- By default, the server blocks internal network scanning
- The default Docker Compose stack publishes host ports on loopback only
- Review and configure URL validation settings for your environment
- Consider network-level isolation (VPCs, firewalls) for additional protection

### Data Sensitivity

- Scan results may contain sensitive information about vulnerabilities
- Reports are stored in the configured workspace directory
- Implement file system permissions to protect report data
- Consider encrypting stored reports at rest

## 🛡️ Commercial & Private Reporting

If you are an enterprise user and require a **Non-Disclosure Agreement (NDA)** or a private remediation timeline, please contact our commercial security team at **[agentic.lab.au@gmail.com](mailto:agentic.lab.au@gmail.com)**.

We offer prioritized patching and private security advisories for commercial clients.

---

## Reporting a Vulnerability

If you believe you have discovered a security issue, **please do NOT open a public GitHub issue**. Use one of these private channels instead:

1. **GitHub private vulnerability reporting**: Open a private report at `https://github.com/dtkmn/mcp-zap-server/security/advisories/new`.
2. **Email**: Send the details to **danieltse@gmail.com**.
3. **Include**:
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

*Last updated: 2026-04-29*
