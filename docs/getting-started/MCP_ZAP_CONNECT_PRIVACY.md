# Privacy Policy For MCP ZAP Connect

Last updated: 20 October 2026. This policy covers MCP ZAP Connect, maintained
by [Daniel Tse](https://github.com/dtkmn), and the local setup it guides. The plugin
contains setup instructions. The maintainer does not operate a hosted scanner
or collect setup telemetry through this plugin. Setup does not transmit local
credentials or reports to the maintainer. Support receives only information
you choose to submit; do not include credentials or sensitive reports.

**Local data and purpose.** Setup checks installation paths, software versions,
container state and configuration to start and connect your scanner. API keys are
generated or reused locally for authentication and stored in private local
settings; they must not be pasted into chat or support reports. Settings, local
logs, configuration backups and any subsequently generated scan reports remain
in your installation. Setup uses a harmless status call; scanning requires a
separate request. Scan data can contain URLs, HTTP traffic, application content
and findings from your chosen targets.

**Recipients and network access.** Setup downloads project files, container images
and any required ZAP add-ons from their publishers and hosting services. Those
services receive ordinary network request information, such as your IP address
and requested resource, under their own policies. An authorized scan sends
requests to your chosen targets. Your AI client/provider processes prompts,
selected command output and MCP responses under its own data policies. A report
retrieved through MCP can therefore reach that provider. Local storage does not
mean that information returned to an AI client stays on your machine. Review
your provider's settings before using sensitive targets or reports.

**Retention and controls.** The plugin has no maintainer-hosted store of scanner
data and no automatic deletion schedule for your local installation. Local data
and private backups remain until you delete them or apply your own retention
policy. Stopping containers preserves settings and reports. Uninstalling the
plugin does not remove Docker containers, local files or the separate client MCP
connection. To stop using the setup, stop its containers, disable or remove that
MCP connection, and delete the installation's files and backups when no longer
needed. Deletion of reports or settings is a separate action and requires your
instruction. Your AI provider and download services govern retention of data
they receive; their controls are separate from local deletion.

**Support information.** Information you submit in
[GitHub issues](https://github.com/dtkmn/mcp-zap-server/issues), including your
GitHub identity, versions and description, is public and used to investigate the
reported problem. Its retention and deletion controls follow GitHub's policies;
deletion cannot recall copies others have made. Share only redacted diagnostics.
For security concerns, use the project's
[private vulnerability reporting process](https://github.com/dtkmn/mcp-zap-server/security/policy).
Use GitHub issues for non-sensitive questions about this policy.
