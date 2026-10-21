# Terms Of Service For MCP ZAP Connect

Last updated: 12 October 2026. MCP ZAP Connect is an open-source plugin
maintained by [Daniel Tse](https://github.com/dtkmn). It supplies instructions
for local MCP ZAP Server setup. The maintainer does not provide a hosted
scanner through this plugin.

**Software license.** The plugin is licensed under
[Apache-2.0](https://github.com/dtkmn/mcp-zap-server/blob/main/plugins/mcp-zap-connect/LICENSE).
That license governs use, modification and distribution, including its warranty
disclaimer and limitation of liability, subject to applicable law. This page
does not replace or restrict the rights granted by that license.

**Local setup and scanning.** Installing the plugin alone does not start a
scanner. A setup request can download project files and images, start local
containers and update the requested client's private MCP settings. Review the
actions and permissions requested by your AI client. Scanning requires a
separate request; use it only against targets you own or have permission to test.
Findings do not establish that an application is secure or replace a complete
security assessment. You are responsible for choosing authorized targets,
the scan scope and how you use the results.

**Dependencies and data.** Docker, ZAP, your AI client and download services
have their own licenses, terms and privacy policies. Read the
[privacy policy](MCP_ZAP_CONNECT_PRIVACY.md) before providing
sensitive targets or reports. This independent project is not affiliated with
or endorsed by ZAP, OpenAI or Cursor.

**Support.** Community support is available through
[GitHub issues](https://github.com/dtkmn/mcp-zap-server/issues); response times
and resolution are not guaranteed. Share redacted diagnostics only. Use the
project's [private vulnerability reporting process](https://github.com/dtkmn/mcp-zap-server/security/policy)
for security concerns.
