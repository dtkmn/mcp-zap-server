---
title: "Automation Framework"
editUrl: false
description: "Understand the boundary between guided MCP scans and standalone ZAP Automation Framework plans."
---
ZAP Automation Framework executes native YAML plans. The guided MCP catalog does not expose plan-run, plan-status or plan-artifact tools; the former expert Automation Framework aliases have been retired.

For agent-driven work through MCP, use the supported guided sequence:

1. Prepare an auth session or import a supported API definition when required.
2. Start a crawl or an explicitly authorized active scan.
3. Monitor its Operation ID and use the matching stop tool when needed.
4. Wait for passive analysis, then read findings and generate reports.

See [Scan Execution](../scan-execution-modes/), [API Schema Imports](../api-schema-imports/) and [Findings and Reports](../findings-and-reports/).

A native plan is not a queued MCP scan. If a workflow requires Automation Framework, run it through a separately managed ZAP deployment using [ZAP's Automation Framework documentation](https://www.zaproxy.org/docs/automate/automation-framework/). Its status, cancellation, credentials and artifacts belong to that workflow; the MCP queue does not track it.

Before upgrading an installation that used the old expert plan tools, finish or deliberately stop those plans through the existing operator workflow. Plan IDs and plan paths cannot be converted into guided Operation IDs. Keeping automation workspace settings or granting old automation scopes does not restore the retired MCP tools.
