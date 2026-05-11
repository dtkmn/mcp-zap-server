#!/usr/bin/env python3
"""Minimal MCP-over-HTTP helper for CI security gates."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


SCAN_ID_PATTERN = re.compile(r"^Scan ID:\s*(.+)$", re.MULTILINE)
OPERATION_ID_PATTERN = re.compile(r"^Operation ID:\s*(.+)$", re.MULTILINE)
REPORT_PATH_PATTERN = re.compile(r"^Path:\s*(.+)$", re.MULTILINE)
COMPLETED_PATTERN = re.compile(r"^Completed:\s*(yes|no)$", re.MULTILINE | re.IGNORECASE)
PROGRESS_PATTERN = re.compile(r"^Progress:\s*(\d+)%$", re.MULTILINE)
COUNT_PATTERNS = {
    "new": re.compile(r"^New Findings:\s*(\d+)$", re.MULTILINE),
    "resolved": re.compile(r"^Resolved Findings:\s*(\d+)$", re.MULTILINE),
    "unchanged": re.compile(r"^Unchanged Findings:\s*(\d+)$", re.MULTILINE),
}

CI_GATE_RESULT_CONTRACT_VERSION = "ci_gate_result/v1"
CI_GATE_FINDINGS_SNAPSHOT_CONTRACT_VERSION = "ci_gate_findings_snapshot/v1"
CI_GATE_FINDINGS_DIFF_CONTRACT_VERSION = "ci_gate_findings_diff/v1"
CI_GATE_SUPPRESSIONS_CONTRACT_VERSION = "ci_gate_suppressions/v1"
CI_GATE_ARTIFACT_MANIFEST_CONTRACT_VERSION = "ci_gate_artifact_manifest/v1"
LEGACY_FINDINGS_SNAPSHOT_CONTRACT_VERSION = "legacy_zap_findings_snapshot/v1"
SUPPORTED_SUPPRESSION_MATCH_FIELDS = ("plugin_id", "alert_name", "risk", "confidence", "url", "param")
RISK_RANKS = {
    "High": 3,
    "Medium": 2,
    "Low": 1,
    "Informational": 0,
}
CONFIDENCE_ALIASES = {
    "false positive": "False Positive",
    "low": "Low",
    "medium": "Medium",
    "high": "High",
    "user confirmed": "User Confirmed",
    "confirmed": "User Confirmed",
}


def parse_bool(value: str) -> bool:
    normalized = (value or "").strip().lower()
    if normalized in {"true", "1", "yes", "y", "on"}:
        return True
    if normalized in {"false", "0", "no", "n", "off"}:
        return False
    raise argparse.ArgumentTypeError(f"Unsupported boolean value: {value}")


def normalize_tool_text(text: str) -> str:
    stripped = str(text).strip()
    if len(stripped) >= 2 and stripped[0] == '"' and stripped[-1] == '"':
        try:
            decoded = json.loads(stripped)
        except json.JSONDecodeError:
            return stripped
        if isinstance(decoded, str):
            return decoded.strip()
    return stripped


def extract_tool_text(payload: Any) -> str:
    if isinstance(payload, dict):
        if "error" in payload:
            raise RuntimeError(extract_error_message(payload))
        result = payload.get("result", payload)
        if isinstance(result, dict):
            if result.get("isError"):
                raise RuntimeError(extract_error_message(result))
            content = result.get("content")
            if isinstance(content, list):
                parts: list[str] = []
                for item in content:
                    if isinstance(item, dict) and "text" in item:
                        parts.append(normalize_tool_text(item["text"]))
                    elif isinstance(item, dict):
                        parts.append(json.dumps(item, indent=2, sort_keys=True))
                    else:
                        parts.append(str(item))
                return "\n".join(part for part in parts if part).strip()
            if "text" in result:
                return normalize_tool_text(result["text"])
        return json.dumps(result, indent=2, sort_keys=True)
    return str(payload).strip()


def unwrap_mcp_body(body: str) -> str:
    stripped = body.strip()
    if not stripped:
        return stripped
    if not stripped.startswith("event:") and not stripped.startswith("data:"):
        return stripped

    data_lines: list[str] = []
    for line in stripped.splitlines():
        if line.startswith("data:"):
            data_lines.append(line[len("data:"):].lstrip())
    if data_lines:
        return "\n".join(data_lines).strip()
    return stripped


def extract_error_message(payload: Any) -> str:
    if isinstance(payload, dict):
        if isinstance(payload.get("error"), dict):
            error = payload["error"]
            message = error.get("message") or error.get("error") or json.dumps(error, sort_keys=True)
            return str(message)
        if "error" in payload:
            return str(payload["error"])
        if "message" in payload:
            return str(payload["message"])
    return str(payload)


def parse_scan_id(text: str) -> str:
    match = SCAN_ID_PATTERN.search(text)
    if not match:
        raise RuntimeError(f"Could not parse scan ID from tool output:\n{text}")
    return match.group(1).strip()


def parse_operation_id(text: str) -> str:
    match = OPERATION_ID_PATTERN.search(text)
    if not match:
        raise RuntimeError(f"Could not parse operation ID from tool output:\n{text}")
    return match.group(1).strip()


def parse_completed(text: str) -> bool:
    match = COMPLETED_PATTERN.search(text)
    if match:
        return match.group(1).strip().lower() == "yes"
    progress = PROGRESS_PATTERN.search(text)
    return bool(progress and int(progress.group(1)) >= 100)


def parse_count(text: str, kind: str) -> int | None:
    pattern = COUNT_PATTERNS[kind]
    match = pattern.search(text)
    if not match:
        return None
    return int(match.group(1))


def parse_report_path(text: str) -> str:
    match = REPORT_PATH_PATTERN.search(text)
    if not match:
        raise RuntimeError(f"Could not parse report path from tool output:\n{text}")
    return match.group(1).strip()


def map_report_path(container_path: str, container_root: str | None, local_root: str | None) -> Path | None:
    report_path = Path(container_path)
    if report_path.exists():
        return report_path
    if not container_root or not local_root:
        return None
    normalized_container_root = container_root.rstrip("/")
    if container_path == normalized_container_root:
        return Path(local_root)
    prefix = normalized_container_root + "/"
    if not container_path.startswith(prefix):
        return None
    suffix = container_path[len(prefix):]
    return Path(local_root) / suffix


def append_github_output(name: str, value: str | None) -> None:
    output_path = os.environ.get("GITHUB_OUTPUT")
    if not output_path:
        return
    rendered = "" if value is None else str(value)
    with open(output_path, "a", encoding="utf-8") as handle:
        handle.write(f"{name}<<__EOF__\n{rendered}\n__EOF__\n")


def append_github_summary(markdown: str) -> None:
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not summary_path:
        return
    with open(summary_path, "a", encoding="utf-8") as handle:
        handle.write(markdown)
        if not markdown.endswith("\n"):
            handle.write("\n")


class McpHttpClient:
    def __init__(self, server_url: str, api_key: str, timeout_seconds: int = 60) -> None:
        self.server_url = server_url
        self.api_key = api_key
        self.timeout_seconds = timeout_seconds
        self.session_id: str | None = None
        self.request_id = 0

    def initialize(self) -> None:
        payload = {
            "jsonrpc": "2.0",
            "id": self._next_id(),
            "method": "initialize",
            "params": {
                "protocolVersion": "2025-03-26",
                "capabilities": {},
                "clientInfo": {
                    "name": "github-actions-zap-gate",
                    "version": "1.0.0",
                },
            },
        }
        body, headers = self._post_json(payload)
        self.session_id = headers.get("Mcp-Session-Id")
        if not self.session_id:
            raise RuntimeError(f"MCP initialize response did not return Mcp-Session-Id:\n{body}")

    def call_tool(self, name: str, arguments: dict[str, Any]) -> str:
        if not self.session_id:
            self.initialize()
        payload = {
            "jsonrpc": "2.0",
            "id": self._next_id(),
            "method": "tools/call",
            "params": {
                "name": name,
                "arguments": arguments,
            },
        }
        body, _ = self._post_json(payload)
        body = unwrap_mcp_body(body)
        try:
            parsed = json.loads(body)
        except json.JSONDecodeError:
            return body.strip()
        return extract_tool_text(parsed)

    def list_tools(self) -> list[str]:
        if not self.session_id:
            self.initialize()
        payload = {
            "jsonrpc": "2.0",
            "id": self._next_id(),
            "method": "tools/list",
        }
        body, _ = self._post_json(payload)
        body = unwrap_mcp_body(body)
        parsed = json.loads(body)
        result = parsed.get("result", {})
        tools = result.get("tools", [])
        return [tool.get("name", "") for tool in tools if isinstance(tool, dict) and tool.get("name")]

    def _post_json(self, payload: dict[str, Any]) -> tuple[str, Any]:
        body = json.dumps(payload).encode("utf-8")
        headers = {
            "Accept": "application/json,text/event-stream",
            "Content-Type": "application/json",
            "X-API-Key": self.api_key,
        }
        if self.session_id:
            headers["Mcp-Session-Id"] = self.session_id
        request = urllib.request.Request(self.server_url, data=body, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                response_body = response.read().decode("utf-8")
                return response_body, response.headers
        except urllib.error.HTTPError as exc:
            error_body = exc.read().decode("utf-8", errors="replace")
            normalized_error_body = unwrap_mcp_body(error_body)
            message = error_body
            try:
                message = extract_error_message(json.loads(normalized_error_body))
            except json.JSONDecodeError:
                pass
            raise RuntimeError(f"HTTP {exc.code} from MCP server: {message}") from exc
        except urllib.error.URLError as exc:
            raise RuntimeError(f"Unable to reach MCP server at {self.server_url}: {exc}") from exc

    def _next_id(self) -> int:
        self.request_id += 1
        return self.request_id


def wait_for_completion(client: McpHttpClient,
                        tool_name: str,
                        reference_id: str,
                        timeout_seconds: int,
                        poll_interval_seconds: int,
                        argument_name: str = "scanId") -> str:
    deadline = time.time() + timeout_seconds
    last_output = ""
    while time.time() < deadline:
        last_output = client.call_tool(tool_name, {argument_name: reference_id})
        if parse_completed(last_output):
            return last_output
        time.sleep(poll_interval_seconds)
    raise RuntimeError(
        f"Timed out waiting for {tool_name} on reference {reference_id} after {timeout_seconds} seconds.\n"
        f"Last output:\n{last_output}"
    )


def stable_json_dumps(payload: Any) -> str:
    return json.dumps(payload, indent=2, sort_keys=True) + "\n"


def write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def write_json(path: Path, payload: dict[str, Any]) -> None:
    write_text(path, stable_json_dumps(payload))


def compact_whitespace(value: str | None) -> str | None:
    if value is None:
        return None
    compacted = re.sub(r"\s+", " ", str(value)).strip()
    return compacted or None


def normalize_risk(value: str | None) -> str | None:
    normalized = compact_whitespace(value)
    if not normalized:
        return None
    lowered = normalized.lower()
    if lowered == "info":
        return "Informational"
    if lowered in {"high", "medium", "low", "informational"}:
        return lowered.capitalize() if lowered != "informational" else "Informational"
    return normalized


def normalize_confidence(value: str | None) -> str | None:
    normalized = compact_whitespace(value)
    if not normalized:
        return None
    return CONFIDENCE_ALIASES.get(normalized.lower(), normalized)


def normalize_url(value: str | None) -> str | None:
    normalized = compact_whitespace(value)
    if not normalized:
        return None

    parsed = urllib.parse.urlsplit(normalized)
    if not parsed.scheme or not parsed.netloc:
        return normalized

    scheme = parsed.scheme.lower()
    hostname = (parsed.hostname or "").lower()
    if not hostname:
        return normalized

    port = parsed.port
    include_port = port is not None and not ((scheme == "http" and port == 80) or (scheme == "https" and port == 443))

    userinfo = ""
    if parsed.username:
        userinfo = parsed.username
        if parsed.password:
            userinfo = f"{userinfo}:{parsed.password}"
        userinfo = f"{userinfo}@"

    netloc = f"{userinfo}{hostname}"
    if include_port:
        netloc = f"{netloc}:{port}"

    path = parsed.path or "/"
    normalized_query = parsed.query
    if normalized_query:
        normalized_query = urllib.parse.urlencode(
            sorted(urllib.parse.parse_qsl(normalized_query, keep_blank_values=True)),
            doseq=True,
        )

    return urllib.parse.urlunsplit((scheme, netloc, path, normalized_query, ""))


def normalize_finding_record(raw: dict[str, Any]) -> dict[str, Any]:
    finding = {
        "plugin_id": compact_whitespace(raw.get("plugin_id") or raw.get("pluginId")),
        "alert_name": compact_whitespace(raw.get("alert_name") or raw.get("alertName") or raw.get("name")),
        "risk": normalize_risk(raw.get("risk")),
        "confidence": normalize_confidence(raw.get("confidence")),
        "url": normalize_url(raw.get("url")),
        "param": compact_whitespace(raw.get("param")),
    }
    payload = {key: finding[key] for key in SUPPORTED_SUPPRESSION_MATCH_FIELDS}
    digest = hashlib.sha256(json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8")).hexdigest()
    finding["fingerprint"] = f"sha256:{digest}"
    return finding


def finding_sort_key(finding: dict[str, Any]) -> tuple[Any, ...]:
    return (
        -RISK_RANKS.get(finding.get("risk"), -1),
        (finding.get("alert_name") or "").lower(),
        (finding.get("plugin_id") or "").lower(),
        (finding.get("url") or "").lower(),
        (finding.get("param") or "").lower(),
        finding.get("fingerprint") or "",
    )


def group_sort_key(group: dict[str, Any]) -> tuple[Any, ...]:
    return (
        -RISK_RANKS.get(group.get("risk"), -1),
        (group.get("alert_name") or "").lower(),
        (group.get("plugin_id") or "").lower(),
    )


def canonicalize_snapshot(payload: dict[str, Any], fallback_target_url: str | None = None) -> tuple[dict[str, Any], str]:
    if payload.get("contract_version") == CI_GATE_FINDINGS_SNAPSHOT_CONTRACT_VERSION:
        raw_findings = payload.get("findings")
        target_url = payload.get("target_url") or fallback_target_url
        source_contract = CI_GATE_FINDINGS_SNAPSHOT_CONTRACT_VERSION
    elif payload.get("version") == 1 and isinstance(payload.get("fingerprints"), list):
        raw_findings = payload.get("fingerprints")
        target_url = payload.get("baseUrl") or fallback_target_url
        source_contract = LEGACY_FINDINGS_SNAPSHOT_CONTRACT_VERSION
    else:
        raise ValueError("Baseline snapshot must be valid JSON exported by zap_findings_snapshot or current-findings.json")

    if not isinstance(raw_findings, list):
        raise ValueError("Findings snapshot is missing findings")

    deduped: dict[str, dict[str, Any]] = {}
    for item in raw_findings:
        if not isinstance(item, dict):
            continue
        finding = normalize_finding_record(item)
        deduped.setdefault(finding["fingerprint"], finding)

    findings = sorted(deduped.values(), key=finding_sort_key)
    return (
        {
            "contract_version": CI_GATE_FINDINGS_SNAPSHOT_CONTRACT_VERSION,
            "target_url": normalize_url(target_url),
            "finding_count": len(findings),
            "findings": findings,
        },
        source_contract,
    )


def render_findings_summary(snapshot: dict[str, Any]) -> str:
    findings = snapshot["findings"]
    lines = [
        "# CI Gate Findings Summary",
        "",
        f"Target: {snapshot.get('target_url') or 'All targets'}",
        f"Unique Findings: {snapshot['finding_count']}",
        "",
    ]

    if not findings:
        lines.append("No findings detected.")
        return "\n".join(lines) + "\n"

    grouped: dict[str, dict[tuple[str | None, str | None], int]] = {}
    for finding in findings:
        risk = finding.get("risk") or "Unknown"
        key = (finding.get("alert_name"), finding.get("plugin_id"))
        grouped.setdefault(risk, {})
        grouped[risk][key] = grouped[risk].get(key, 0) + 1

    for risk in ("High", "Medium", "Low", "Informational", "Unknown"):
        risk_groups = grouped.get(risk)
        if not risk_groups:
            continue
        lines.append(f"## {risk} Risk")
        for (alert_name, plugin_id), count in sorted(
            risk_groups.items(),
            key=lambda item: ((item[0][0] or "").lower(), (item[0][1] or "").lower()),
        ):
            label = alert_name or "<none>"
            plugin_label = plugin_id or "<none>"
            lines.append(f"- {label} | Plugin ID: {plugin_label} | Count: {count}")
        lines.append("")

    return "\n".join(lines).rstrip() + "\n"


def build_diff_groups(findings: list[dict[str, Any]]) -> list[dict[str, Any]]:
    grouped: dict[tuple[str | None, str | None, str | None], int] = {}
    for finding in findings:
        key = (finding.get("plugin_id"), finding.get("alert_name"), finding.get("risk"))
        grouped[key] = grouped.get(key, 0) + 1

    groups = [
        {
            "plugin_id": plugin_id,
            "alert_name": alert_name,
            "risk": risk,
            "count": count,
        }
        for (plugin_id, alert_name, risk), count in grouped.items()
    ]
    return sorted(groups, key=group_sort_key)


def render_diff_text(diff_contract: dict[str, Any]) -> str:
    counts = diff_contract["counts"]
    suppressions = diff_contract["suppressions"]
    lines = [
        "Findings diff summary",
        f"Target: {diff_contract.get('target_url') or 'All targets'}",
        f"Baseline Findings: {diff_contract['baseline']['finding_count']}",
        f"Current Findings: {diff_contract['current']['finding_count']}",
        f"Suppressed Baseline Findings: {suppressions['suppressed_baseline_findings']}",
        f"Suppressed Current Findings: {suppressions['suppressed_current_findings']}",
        f"New Findings: {counts['new']}",
        f"Resolved Findings: {counts['resolved']}",
        f"Unchanged Findings: {counts['unchanged']}",
        "",
    ]

    for heading, groups in (
        ("New finding groups", diff_contract["new_finding_groups"]),
        ("Resolved finding groups", diff_contract["resolved_finding_groups"]),
    ):
        lines.append(f"{heading}: {len(groups)}")
        if not groups:
            lines.append("- none")
        else:
            for group in groups:
                lines.append(
                    "- "
                    f"{group.get('alert_name') or '<none>'}"
                    f" | Risk: {group.get('risk') or '<none>'}"
                    f" | Plugin ID: {group.get('plugin_id') or '<none>'}"
                    f" | Count: {group['count']}"
                )
        lines.append("")

    if suppressions["used"]:
        matched_rule_ids = suppressions["matched_rule_ids"]
        lines.append(
            "Suppression rules applied: "
            + (", ".join(matched_rule_ids) if matched_rule_ids else "none")
        )
    elif suppressions["requested"] and suppressions["reason"]:
        lines.append(f"Suppressions skipped: {suppressions['reason']}")
    else:
        lines.append("Suppression rules applied: none")

    return "\n".join(lines).rstrip() + "\n"


def parse_iso8601_timestamp(value: str | None) -> datetime | None:
    normalized = compact_whitespace(value)
    if not normalized:
        return None
    try:
        parsed = datetime.fromisoformat(normalized.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ValueError(f"Invalid ISO-8601 timestamp: {value}") from exc
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def normalize_suppression_match(raw_match: dict[str, Any]) -> dict[str, Any]:
    normalized_match: dict[str, Any] = {}
    fingerprint = compact_whitespace(raw_match.get("fingerprint"))
    if fingerprint:
        normalized_match["fingerprint"] = fingerprint

    aliases = {
        "plugin_id": ("plugin_id", "pluginId"),
        "alert_name": ("alert_name", "alertName"),
        "risk": ("risk",),
        "confidence": ("confidence",),
        "url": ("url",),
        "param": ("param",),
    }
    for field, candidates in aliases.items():
        value = None
        for candidate in candidates:
            if candidate in raw_match:
                value = raw_match[candidate]
                break
        if field == "risk":
            normalized_value = normalize_risk(value)
        elif field == "confidence":
            normalized_value = normalize_confidence(value)
        elif field == "url":
            normalized_value = normalize_url(value)
        else:
            normalized_value = compact_whitespace(value)
        if normalized_value is not None:
            normalized_match[field] = normalized_value
    return normalized_match


def load_suppressions_contract(path: Path, now: datetime | None = None) -> dict[str, Any]:
    current_time = now or datetime.now(timezone.utc)
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise ValueError(f"Suppressions file must be valid JSON: {path}") from exc

    contract_version = payload.get("contract_version")
    legacy_version = payload.get("version")
    if contract_version != CI_GATE_SUPPRESSIONS_CONTRACT_VERSION and legacy_version != 1:
        raise ValueError(
            "Suppressions file must declare contract_version ci_gate_suppressions/v1 or version 1"
        )

    raw_rules = payload.get("suppressions")
    if not isinstance(raw_rules, list):
        raise ValueError("Suppressions file must contain a suppressions array")

    rules: list[dict[str, Any]] = []
    active_rule_count = 0
    expired_rule_count = 0
    for index, raw_rule in enumerate(raw_rules, start=1):
        if not isinstance(raw_rule, dict):
            raise ValueError(f"Suppression rule #{index} must be a JSON object")

        rule_id = compact_whitespace(raw_rule.get("id"))
        if not rule_id:
            raise ValueError(f"Suppression rule #{index} is missing id")

        match = raw_rule.get("match")
        if match is None:
            match = {}
        if not isinstance(match, dict):
            raise ValueError(f"Suppression rule {rule_id} must use an object for match")

        if raw_rule.get("fingerprint") is not None:
            match = dict(match)
            match["fingerprint"] = raw_rule.get("fingerprint")

        normalized_match = normalize_suppression_match(match)
        if not normalized_match:
            raise ValueError(
                f"Suppression rule {rule_id} must provide fingerprint or at least one exact match field"
            )

        expires_at = parse_iso8601_timestamp(raw_rule.get("expires_at") or raw_rule.get("expiresAt"))
        active = expires_at is None or expires_at > current_time
        if active:
            active_rule_count += 1
        else:
            expired_rule_count += 1

        rules.append(
            {
                "id": rule_id,
                "reason": compact_whitespace(raw_rule.get("reason")),
                "expires_at": None if expires_at is None else expires_at.isoformat().replace("+00:00", "Z"),
                "match": normalized_match,
                "active": active,
            }
        )

    return {
        "contract_version": CI_GATE_SUPPRESSIONS_CONTRACT_VERSION,
        "rule_count": len(rules),
        "active_rule_count": active_rule_count,
        "expired_rule_count": expired_rule_count,
        "rules": rules,
        "source_name": path.name,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    }


def finding_matches_rule(finding: dict[str, Any], rule: dict[str, Any]) -> bool:
    match = rule["match"]
    expected_fingerprint = match.get("fingerprint")
    if expected_fingerprint and finding["fingerprint"] != expected_fingerprint:
        return False

    for field in SUPPORTED_SUPPRESSION_MATCH_FIELDS:
        expected = match.get(field)
        if expected is not None and finding.get(field) != expected:
            return False
    return True


def apply_suppressions(findings: list[dict[str, Any]], suppressions: dict[str, Any] | None) -> tuple[list[dict[str, Any]], int, list[str]]:
    if not suppressions or not suppressions.get("rules"):
        return list(findings), 0, []

    filtered: list[dict[str, Any]] = []
    matched_rule_ids: set[str] = set()
    suppressed_count = 0

    for finding in findings:
        matched = False
        for rule in suppressions["rules"]:
            if not rule["active"]:
                continue
            if finding_matches_rule(finding, rule):
                matched = True
                matched_rule_ids.add(rule["id"])
        if matched:
            suppressed_count += 1
            continue
        filtered.append(finding)

    return filtered, suppressed_count, sorted(matched_rule_ids)


def build_diff_contract(target_url: str,
                        baseline_snapshot: dict[str, Any],
                        current_snapshot: dict[str, Any],
                        suppressions_summary: dict[str, Any]) -> dict[str, Any]:
    normalized_target_url = (
        current_snapshot.get("target_url")
        or baseline_snapshot.get("target_url")
        or normalize_url(target_url)
    )
    baseline_map = {finding["fingerprint"]: finding for finding in baseline_snapshot["findings"]}
    current_map = {finding["fingerprint"]: finding for finding in current_snapshot["findings"]}

    new_findings = sorted(
        [finding for fingerprint, finding in current_map.items() if fingerprint not in baseline_map],
        key=finding_sort_key,
    )
    resolved_findings = sorted(
        [finding for fingerprint, finding in baseline_map.items() if fingerprint not in current_map],
        key=finding_sort_key,
    )

    return {
        "contract_version": CI_GATE_FINDINGS_DIFF_CONTRACT_VERSION,
        "target_url": normalized_target_url,
        "baseline": {
            "contract_version": baseline_snapshot["contract_version"],
            "finding_count": baseline_snapshot["finding_count"],
        },
        "current": {
            "contract_version": current_snapshot["contract_version"],
            "finding_count": current_snapshot["finding_count"],
        },
        "suppressions": suppressions_summary,
        "counts": {
            "new": len(new_findings),
            "resolved": len(resolved_findings),
            "unchanged": len(set(baseline_map).intersection(current_map)),
        },
        "new_finding_groups": build_diff_groups(new_findings),
        "resolved_finding_groups": build_diff_groups(resolved_findings),
    }


def relative_artifact_path(path: Path | None, output_dir: Path) -> str | None:
    if path is None:
        return None
    return str(path.resolve().relative_to(output_dir.resolve()))


def media_type_for_path(path: Path) -> str:
    suffix = path.suffix.lower()
    if suffix == ".json":
        return "application/json"
    if suffix == ".md":
        return "text/markdown"
    if suffix == ".txt":
        return "text/plain"
    if suffix == ".html":
        return "text/html"
    return "application/octet-stream"


def build_manifest_entry(name: str, path: Path, output_dir: Path, include_digest: bool = True) -> dict[str, Any]:
    entry = {
        "name": name,
        "path": relative_artifact_path(path, output_dir),
        "media_type": media_type_for_path(path),
        "bytes": path.stat().st_size,
    }
    if include_digest:
        entry["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
    return entry


def deterministic_report_path(mapped_report: Path, output_dir: Path, report_template: str) -> Path:
    suffix = mapped_report.suffix
    if not suffix:
        suffix = ".json" if "json" in report_template.lower() else ".html"
    return output_dir / "reports" / f"zap-report{suffix}"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run a GitHub Actions-friendly MCP ZAP gate.")
    parser.add_argument("--server-url", required=True)
    parser.add_argument("--api-key", required=True)
    parser.add_argument("--target-url", required=True)
    parser.add_argument("--baseline-file")
    parser.add_argument(
        "--baseline-mode",
        choices=("enforce", "seed"),
        default="enforce",
        help=(
            "How to handle missing baseline diff evidence. 'enforce' fails when "
            "fail-on-new-findings is true but no diff can be produced; 'seed' allows "
            "first-run baseline review without blocking."
        ),
    )
    parser.add_argument("--suppressions-file")
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--report-template", default="traditional-html-plus")
    parser.add_argument("--report-theme", default="light")
    parser.add_argument("--run-active-scan", type=parse_bool, default=True)
    parser.add_argument("--scan-policy")
    parser.add_argument("--fail-on-new-findings", type=parse_bool, default=True)
    parser.add_argument("--max-new-findings", type=int, default=0)
    parser.add_argument("--poll-interval-seconds", type=int, default=5)
    parser.add_argument("--passive-timeout-seconds", type=int, default=180)
    parser.add_argument("--spider-timeout-seconds", type=int, default=600)
    parser.add_argument("--active-timeout-seconds", type=int, default=1200)
    parser.add_argument("--report-root-container")
    parser.add_argument("--report-root-local")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    output_dir = Path(args.output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    client = McpHttpClient(args.server_url, args.api_key)
    available_tools = set(client.list_tools())

    guided_surface = {
        "zap_crawl_start",
        "zap_crawl_status",
        "zap_attack_start",
        "zap_attack_status",
        "zap_findings_summary",
        "zap_report_generate",
    }.issubset(available_tools)
    expert_surface = {
        "zap_spider_start",
        "zap_spider_status",
        "zap_active_scan_start",
        "zap_active_scan_status",
        "zap_get_findings_summary",
        "zap_findings_snapshot",
        "zap_generate_report",
    }.issubset(available_tools)

    if not guided_surface and not expert_surface:
        raise RuntimeError(
            "Unable to find a supported MCP tool surface for the security gate. "
            f"Available tools were: {sorted(available_tools)}"
        )

    surface = "expert" if expert_surface else "guided"
    crawl_reference: str
    attack_reference: str | None = None

    if surface == "guided":
        crawl_start = client.call_tool(
            "zap_crawl_start",
            {
                "targetUrl": args.target_url,
                "strategy": "auto",
            },
        )
        crawl_reference = parse_operation_id(crawl_start)
        wait_for_completion(
            client,
            "zap_crawl_status",
            crawl_reference,
            args.spider_timeout_seconds,
            args.poll_interval_seconds,
            argument_name="operationId",
        )

        if args.run_active_scan:
            attack_start = client.call_tool(
                "zap_attack_start",
                {
                    "targetUrl": args.target_url,
                    "recurse": "true",
                    **({"policy": args.scan_policy} if args.scan_policy else {}),
                },
            )
            attack_reference = parse_operation_id(attack_start)
            wait_for_completion(
                client,
                "zap_attack_status",
                attack_reference,
                args.active_timeout_seconds,
                args.poll_interval_seconds,
                argument_name="operationId",
            )
    else:
        spider_start = client.call_tool("zap_spider_start", {"targetUrl": args.target_url})
        crawl_reference = parse_scan_id(spider_start)
        wait_for_completion(
            client,
            "zap_spider_status",
            crawl_reference,
            args.spider_timeout_seconds,
            args.poll_interval_seconds,
        )

        if args.run_active_scan:
            active_start = client.call_tool(
                "zap_active_scan_start",
                {
                    "targetUrl": args.target_url,
                    "recurse": "true",
                    **({"policy": args.scan_policy} if args.scan_policy else {}),
                },
            )
            attack_reference = parse_scan_id(active_start)
            wait_for_completion(
                client,
                "zap_active_scan_status",
                attack_reference,
                args.active_timeout_seconds,
                args.poll_interval_seconds,
            )

    passive_output = client.call_tool(
        "zap_passive_scan_wait",
        {
            "timeoutSeconds": args.passive_timeout_seconds,
            "pollIntervalMs": max(args.poll_interval_seconds * 1000, 1000),
        },
    )
    if "timed out" in passive_output.lower():
        raise RuntimeError(passive_output)

    baseline_requested = bool(args.baseline_file)
    baseline_used = False
    diff_unavailable_reason: str | None = None
    current_snapshot_path: Path | None = None
    diff_text_path: Path | None = None
    diff_json_path: Path | None = None
    baseline_note_path: Path | None = None
    suppressions_note_path: Path | None = None

    current_snapshot_contract: dict[str, Any] | None = None
    current_findings_for_diff: dict[str, Any] | None = None
    baseline_snapshot_contract: dict[str, Any] | None = None
    baseline_source_contract: str | None = None

    if surface == "expert":
        raw_snapshot = client.call_tool("zap_findings_snapshot", {"baseUrl": args.target_url})
        current_snapshot_contract, _ = canonicalize_snapshot(json.loads(raw_snapshot), args.target_url)
        current_snapshot_path = output_dir / "current-findings.json"
        write_json(current_snapshot_path, current_snapshot_contract)
        current_findings_for_diff = current_snapshot_contract

        findings_summary = render_findings_summary(current_snapshot_contract)
    else:
        findings_summary = client.call_tool("zap_findings_summary", {"baseUrl": args.target_url})

    findings_summary_path = output_dir / "findings-summary.md"
    write_text(findings_summary_path, findings_summary.rstrip() + "\n")

    suppressions_requested = bool(args.suppressions_file)
    suppressions_contract: dict[str, Any] | None = None
    suppressions_summary = {
        "requested": suppressions_requested,
        "used": False,
        "source_name": None,
        "source_contract": None,
        "rule_count": 0,
        "active_rule_count": 0,
        "expired_rule_count": 0,
        "matched_rule_ids": [],
        "suppressed_baseline_findings": 0,
        "suppressed_current_findings": 0,
        "reason": None,
    }

    if suppressions_requested:
        if not expert_surface or current_findings_for_diff is None:
            suppressions_note_path = output_dir / "suppressions-note.txt"
            write_text(
                suppressions_note_path,
                "Suppressions were requested, but the active guided tool surface does not expose snapshot support.\n",
            )
            suppressions_summary["reason"] = "guided_surface_without_snapshot_support"
        else:
            suppressions_path = Path(args.suppressions_file).resolve()
            if not suppressions_path.exists():
                raise RuntimeError(f"Suppressions file was requested but not found: {suppressions_path}")
            suppressions_contract = load_suppressions_contract(suppressions_path)
            suppressions_summary.update(
                {
                    "used": True,
                    "source_name": suppressions_contract["source_name"],
                    "source_contract": suppressions_contract["contract_version"],
                    "rule_count": suppressions_contract["rule_count"],
                    "active_rule_count": suppressions_contract["active_rule_count"],
                    "expired_rule_count": suppressions_contract["expired_rule_count"],
                }
            )

    if not baseline_requested:
        diff_unavailable_reason = "baseline_file_not_configured"
    elif baseline_requested and not expert_surface:
        diff_unavailable_reason = "guided_surface_without_snapshot_support"
        baseline_note_path = output_dir / "baseline-note.txt"
        write_text(
            baseline_note_path,
            "Baseline diff was requested, but the guided tool surface does not expose snapshot/diff tools.\n"
            "This run still validated the self-serve crawl/attack/summary/report flow.\n",
        )
    elif baseline_requested:
        baseline_path = Path(args.baseline_file).resolve()
        if baseline_path.exists():
            try:
                baseline_payload = json.loads(baseline_path.read_text(encoding="utf-8"))
            except json.JSONDecodeError as exc:
                raise RuntimeError(f"Baseline file must be valid JSON: {baseline_path}") from exc

            baseline_snapshot_contract, baseline_source_contract = canonicalize_snapshot(baseline_payload, args.target_url)
            baseline_used = True
        else:
            baseline_note_path = output_dir / "baseline-note.txt"
            diff_unavailable_reason = "baseline_file_missing"
            write_text(
                baseline_note_path,
                f"Baseline file was requested but not found: {baseline_path}\n"
                "Current findings snapshot was still generated so this run can seed a baseline.\n",
            )

    if baseline_used and baseline_snapshot_contract and current_findings_for_diff:
        effective_baseline = baseline_snapshot_contract
        effective_current = current_findings_for_diff
        if suppressions_contract:
            filtered_baseline, suppressed_baseline, baseline_rule_ids = apply_suppressions(
                baseline_snapshot_contract["findings"],
                suppressions_contract,
            )
            filtered_current, suppressed_current, current_rule_ids = apply_suppressions(
                current_findings_for_diff["findings"],
                suppressions_contract,
            )
            suppressions_summary["suppressed_baseline_findings"] = suppressed_baseline
            suppressions_summary["suppressed_current_findings"] = suppressed_current
            suppressions_summary["matched_rule_ids"] = sorted(set(baseline_rule_ids).union(current_rule_ids))
            effective_baseline = {
                **baseline_snapshot_contract,
                "finding_count": len(filtered_baseline),
                "findings": filtered_baseline,
            }
            effective_current = {
                **current_findings_for_diff,
                "finding_count": len(filtered_current),
                "findings": filtered_current,
            }

        diff_contract = build_diff_contract(args.target_url, effective_baseline, effective_current, suppressions_summary)
        diff_text_path = output_dir / "findings-diff.txt"
        diff_json_path = output_dir / "findings-diff.json"
        write_text(diff_text_path, render_diff_text(diff_contract))
        write_json(diff_json_path, diff_contract)

    if diff_json_path is not None:
        diff_unavailable_reason = None
    elif baseline_requested and diff_unavailable_reason is None:
        diff_unavailable_reason = "snapshot_diff_unavailable"

    new_findings = None if diff_json_path is None else json.loads(diff_json_path.read_text(encoding="utf-8"))["counts"]["new"]
    resolved_findings = None if diff_json_path is None else json.loads(diff_json_path.read_text(encoding="utf-8"))["counts"]["resolved"]
    unchanged_findings = None if diff_json_path is None else json.loads(diff_json_path.read_text(encoding="utf-8"))["counts"]["unchanged"]

    if surface == "guided":
        report_response = client.call_tool(
            "zap_report_generate",
            {
                "baseUrl": args.target_url,
                "format": "json" if "json" in args.report_template.lower() else "html",
                "theme": args.report_theme,
            },
        )
        raw_report_path = parse_report_path(report_response)
    else:
        raw_report_path = client.call_tool(
            "zap_generate_report",
            {
                "reportTemplate": args.report_template,
                "theme": args.report_theme,
                "sites": args.target_url,
            },
        ).strip()

    copied_report_path: Path | None = None
    mapped_report = map_report_path(raw_report_path, args.report_root_container, args.report_root_local)
    if mapped_report and mapped_report.exists():
        copied_report_path = deterministic_report_path(mapped_report, output_dir, args.report_template)
        copied_report_path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(mapped_report, copied_report_path)

    diff_available = diff_json_path is not None
    enforcement_requested = bool(args.fail_on_new_findings)
    enforcement_failure_reason: str | None = None
    gate_passed = True
    if diff_available and enforcement_requested:
        gate_passed = (new_findings or 0) <= args.max_new_findings
        if not gate_passed:
            enforcement_failure_reason = "new_findings_exceeded_threshold"
    elif enforcement_requested and args.baseline_mode == "enforce":
        gate_passed = False
        enforcement_failure_reason = diff_unavailable_reason or "findings_diff_unavailable"

    metadata_path = output_dir / "gate-metadata.json"
    manifest_path = output_dir / "artifact-manifest.json"
    gate_summary_path = output_dir / "gate-summary.md"

    relative_report_artifact_path = relative_artifact_path(copied_report_path, output_dir)
    metadata = {
        "contract_version": CI_GATE_RESULT_CONTRACT_VERSION,
        "target_url": compact_whitespace(args.target_url),
        "tool_surface": surface,
        "crawl_reference": crawl_reference,
        "attack_reference": attack_reference,
        "active_scan_requested": args.run_active_scan,
        "scan_policy": compact_whitespace(args.scan_policy),
        "baseline_requested": baseline_requested,
        "baseline_mode": args.baseline_mode,
        "baseline_used": baseline_used,
        "baseline": {
            "source_name": None if not baseline_requested else Path(args.baseline_file).name if args.baseline_file else None,
            "source_contract": baseline_source_contract,
            "finding_count": None if baseline_snapshot_contract is None else baseline_snapshot_contract["finding_count"],
            "missing": baseline_requested and not baseline_used,
            "diff_unavailable_reason": diff_unavailable_reason,
        },
        "enforcement": {
            "requested": enforcement_requested,
            "mode": args.baseline_mode,
            "diff_available": diff_available,
            "failure_reason": enforcement_failure_reason,
        },
        "suppressions": suppressions_summary,
        "findings": {
            "summary_path": relative_artifact_path(findings_summary_path, output_dir),
            "snapshot_path": relative_artifact_path(current_snapshot_path, output_dir),
            "diff_path": relative_artifact_path(diff_text_path, output_dir),
            "diff_json_path": relative_artifact_path(diff_json_path, output_dir),
            "current_finding_count": None if current_snapshot_contract is None else current_snapshot_contract["finding_count"],
            "new_findings": new_findings,
            "resolved_findings": resolved_findings,
            "unchanged_findings": unchanged_findings,
        },
        "report": {
            "artifact_path": relative_report_artifact_path,
            "copied": copied_report_path is not None,
        },
        "manifest_path": relative_artifact_path(manifest_path, output_dir),
        "new_findings": new_findings,
        "resolved_findings": resolved_findings,
        "unchanged_findings": unchanged_findings,
        "report_path": relative_report_artifact_path,
        "report_local_path": relative_report_artifact_path,
        "gate_passed": gate_passed,
    }
    write_json(metadata_path, metadata)

    gate_summary_lines = [
        "## MCP ZAP Security Gate",
        "",
        f"- Target: `{args.target_url}`",
        f"- Tool surface: `{surface}`",
        f"- Crawl reference: `{crawl_reference}`",
        f"- Active scan: `{'enabled' if args.run_active_scan else 'skipped'}`",
        f"- Baseline requested: `{'yes' if baseline_requested else 'no'}`",
        f"- Baseline mode: `{args.baseline_mode}`",
        f"- Baseline used: `{'yes' if baseline_used else 'no'}`",
        f"- Suppressions requested: `{'yes' if suppressions_requested else 'no'}`",
    ]
    if attack_reference:
        gate_summary_lines.append(f"- Attack reference: `{attack_reference}`")
    if diff_json_path:
        gate_summary_lines.extend(
            [
                f"- New findings: `{new_findings if new_findings is not None else 'unknown'}`",
                f"- Resolved findings: `{resolved_findings if resolved_findings is not None else 'unknown'}`",
                f"- Gate passed: `{'yes' if gate_passed else 'no'}`",
            ]
        )
    elif baseline_requested and surface == "guided":
        gate_summary_lines.append("- Baseline diff was skipped because the guided self-serve surface does not expose snapshot/diff tools.")
    elif baseline_requested and not baseline_used:
        gate_summary_lines.append("- Baseline file was requested but not found.")
    else:
        gate_summary_lines.append("- No baseline file was used.")
    if enforcement_failure_reason and not diff_available:
        gate_summary_lines.append(
            "- Gate failed: enforcement requested but no findings diff could be produced "
            f"(`{enforcement_failure_reason}`)."
        )
    if suppressions_requested and suppressions_summary["reason"]:
        gate_summary_lines.append(f"- Suppressions skipped: `{suppressions_summary['reason']}`")
    elif suppressions_requested:
        gate_summary_lines.append(
            f"- Suppression rules applied: `{len(suppressions_summary['matched_rule_ids'])}`"
        )
    if relative_report_artifact_path:
        gate_summary_lines.append(f"- Copied report artifact: `{relative_report_artifact_path}`")
    gate_summary_lines.append(f"- Artifact manifest: `{metadata['manifest_path']}`")
    gate_summary_markdown = "\n".join(gate_summary_lines) + "\n"
    write_text(gate_summary_path, gate_summary_markdown)
    append_github_summary(gate_summary_markdown)

    manifest_entries = [
        build_manifest_entry("gate_result", metadata_path, output_dir),
        build_manifest_entry("gate_summary", gate_summary_path, output_dir),
        build_manifest_entry("findings_summary", findings_summary_path, output_dir),
    ]
    if current_snapshot_path:
        manifest_entries.append(build_manifest_entry("findings_snapshot", current_snapshot_path, output_dir))
    if diff_text_path:
        manifest_entries.append(build_manifest_entry("findings_diff_text", diff_text_path, output_dir))
    if diff_json_path:
        manifest_entries.append(build_manifest_entry("findings_diff_json", diff_json_path, output_dir))
    if baseline_note_path:
        manifest_entries.append(build_manifest_entry("baseline_note", baseline_note_path, output_dir))
    if suppressions_note_path:
        manifest_entries.append(build_manifest_entry("suppressions_note", suppressions_note_path, output_dir))
    if copied_report_path:
        manifest_entries.append(build_manifest_entry("report_artifact", copied_report_path, output_dir, include_digest=False))

    manifest = {
        "contract_version": CI_GATE_ARTIFACT_MANIFEST_CONTRACT_VERSION,
        "result_path": relative_artifact_path(metadata_path, output_dir),
        "artifacts": manifest_entries,
    }
    write_json(manifest_path, manifest)

    append_github_output("new_findings", "" if new_findings is None else str(new_findings))
    append_github_output("resolved_findings", "" if resolved_findings is None else str(resolved_findings))
    append_github_output("baseline_used", "true" if baseline_used else "false")
    append_github_output("gate_passed", "true" if gate_passed else "false")
    append_github_output("summary_path", str(findings_summary_path))
    append_github_output("snapshot_path", str(current_snapshot_path) if current_snapshot_path else "")
    append_github_output("diff_path", str(diff_text_path) if diff_text_path else "")
    append_github_output("diff_json_path", str(diff_json_path) if diff_json_path else "")
    append_github_output("manifest_path", str(manifest_path))
    append_github_output("report_path", raw_report_path or "")
    append_github_output("report_local_path", str(copied_report_path) if copied_report_path else "")
    append_github_output("metadata_path", str(metadata_path))

    if not gate_passed:
        if enforcement_failure_reason == "new_findings_exceeded_threshold":
            print(
                f"Security gate failed: new findings ({new_findings}) exceeded the allowed threshold "
                f"({args.max_new_findings}).",
                file=sys.stderr,
            )
        else:
            print(
                "Security gate failed: enforcement requested but no findings diff could be produced "
                f"({enforcement_failure_reason}). Use --baseline-mode seed or "
                "--fail-on-new-findings false for first-run baseline review.",
                file=sys.stderr,
            )
        return 1

    print(gate_summary_markdown)
    return 0


if __name__ == "__main__":
    sys.exit(main())
