#!/usr/bin/env python3
"""Send CI gate metadata to an external webhook with retry and optional signing."""

from __future__ import annotations

import argparse
import email.utils
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


RETRYABLE_STATUS_CODES = {408, 409, 425, 429, 500, 502, 503, 504}


@dataclass
class RetryPolicy:
    max_attempts: int
    initial_backoff_seconds: float
    max_backoff_seconds: float
    backoff_multiplier: float


@dataclass
class DeliveryAttempt:
    attempt: int
    delivered: bool
    status_code: int | None
    error: str | None
    wait_seconds_before_retry: float | None


@dataclass
class DeliveryRecord:
    delivered: bool
    attempts: int
    final_status_code: int | None
    event_name: str
    provider: str
    delivery_id: str
    webhook_target: str
    metadata_path: str
    output_path: str | None
    attempts_detail: list[DeliveryAttempt]


def parse_bool(value: str) -> bool:
    normalized = (value or "").strip().lower()
    if normalized in {"true", "1", "yes", "y", "on"}:
        return True
    if normalized in {"false", "0", "no", "n", "off"}:
        return False
    raise argparse.ArgumentTypeError(f"Unsupported boolean value: {value}")


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


def detect_provider(explicit_provider: str, env: dict[str, str]) -> str:
    normalized = explicit_provider.strip().lower()
    if normalized and normalized != "auto":
        return normalized
    if env.get("GITHUB_ACTIONS", "").lower() == "true":
        return "github"
    if env.get("GITLAB_CI", "").lower() == "true":
        return "gitlab"
    return "generic"


def build_ci_context(provider: str, env: dict[str, str]) -> dict[str, Any]:
    if provider == "github":
        repository = env.get("GITHUB_REPOSITORY")
        server_url = env.get("GITHUB_SERVER_URL")
        run_id = env.get("GITHUB_RUN_ID")
        return {
            "provider": provider,
            "repository": repository,
            "ref": env.get("GITHUB_REF"),
            "refName": env.get("GITHUB_REF_NAME"),
            "sha": env.get("GITHUB_SHA"),
            "workflow": env.get("GITHUB_WORKFLOW"),
            "job": env.get("GITHUB_JOB"),
            "actor": env.get("GITHUB_ACTOR"),
            "runId": run_id,
            "runNumber": env.get("GITHUB_RUN_NUMBER"),
            "runUrl": f"{server_url}/{repository}/actions/runs/{run_id}" if server_url and repository and run_id else None,
        }
    if provider == "gitlab":
        return {
            "provider": provider,
            "projectPath": env.get("CI_PROJECT_PATH"),
            "projectUrl": env.get("CI_PROJECT_URL"),
            "refName": env.get("CI_COMMIT_REF_NAME"),
            "sha": env.get("CI_COMMIT_SHA"),
            "pipelineId": env.get("CI_PIPELINE_ID"),
            "pipelineUrl": env.get("CI_PIPELINE_URL"),
            "jobId": env.get("CI_JOB_ID"),
            "jobUrl": env.get("CI_JOB_URL"),
            "jobName": env.get("CI_JOB_NAME"),
            "user": env.get("GITLAB_USER_LOGIN"),
        }
    return {"provider": provider}


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def build_artifact_payload(paths: dict[str, Path | None]) -> dict[str, Any]:
    artifacts: dict[str, Any] = {}
    for key, path in paths.items():
        if not path:
            continue
        artifacts[key] = {
            "path": str(path),
            "exists": path.exists(),
        }
    return artifacts


def build_payload(event_name: str,
                  provider: str,
                  env: dict[str, str],
                  metadata: dict[str, Any],
                  artifact_paths: dict[str, Path | None]) -> dict[str, Any]:
    status = "passed" if metadata.get("gate_passed") else "failed"
    return {
        "event": event_name,
        "sentAt": datetime.now(timezone.utc).isoformat(),
        "status": status,
        "provider": provider,
        "gate": metadata,
        "artifacts": build_artifact_payload(artifact_paths),
        "ci": build_ci_context(provider, env),
    }


def compute_signature(secret: str, payload_bytes: bytes) -> str:
    digest = hmac.new(secret.encode("utf-8"), payload_bytes, hashlib.sha256).hexdigest()
    return f"sha256={digest}"


def parse_headers_json(raw_headers: str) -> dict[str, str]:
    if not raw_headers:
        return {}
    parsed = json.loads(raw_headers)
    if not isinstance(parsed, dict):
        raise ValueError("headers-json must be a JSON object")
    return {str(key): str(value) for key, value in parsed.items()}


def should_retry(status_code: int | None, error: str | None) -> bool:
    if error is not None:
        return True
    return status_code in RETRYABLE_STATUS_CODES


def parse_retry_after(headers: dict[str, str]) -> float | None:
    retry_after = headers.get("Retry-After")
    if not retry_after:
        return None
    retry_after = retry_after.strip()
    if retry_after.isdigit():
        return float(retry_after)
    try:
        parsed = email.utils.parsedate_to_datetime(retry_after)
    except (TypeError, ValueError):
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    delta = (parsed - datetime.now(timezone.utc)).total_seconds()
    return max(0.0, delta)


def compute_backoff_seconds(policy: RetryPolicy, current_attempt: int, retry_after_seconds: float | None) -> float:
    computed = min(
        policy.max_backoff_seconds,
        policy.initial_backoff_seconds * (policy.backoff_multiplier ** max(0, current_attempt - 1)),
    )
    if retry_after_seconds is None:
        return computed
    return min(policy.max_backoff_seconds, max(computed, retry_after_seconds))


def redact_webhook_url(webhook_url: str) -> str:
    parsed = urllib.parse.urlsplit(webhook_url)
    netloc = parsed.hostname or ""
    if parsed.port:
        netloc = f"{netloc}:{parsed.port}"
    return urllib.parse.urlunsplit((parsed.scheme, netloc, parsed.path, "", ""))


def send_request(webhook_url: str,
                 payload_bytes: bytes,
                 headers: dict[str, str],
                 timeout_seconds: float) -> tuple[int | None, str, dict[str, str], str | None]:
    request = urllib.request.Request(webhook_url, data=payload_bytes, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            body = response.read().decode("utf-8", errors="replace")
            return response.getcode(), body, dict(response.headers.items()), None
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        return exc.code, body, dict(exc.headers.items()), None
    except urllib.error.URLError as exc:
        return None, "", {}, str(exc)


def deliver_with_retries(webhook_url: str,
                         payload_bytes: bytes,
                         headers: dict[str, str],
                         timeout_seconds: float,
                         retry_policy: RetryPolicy,
                         sleep_fn=time.sleep,
                         sender=send_request) -> tuple[bool, int | None, list[DeliveryAttempt]]:
    attempts: list[DeliveryAttempt] = []
    final_status_code: int | None = None

    for attempt in range(1, retry_policy.max_attempts + 1):
        status_code, _, response_headers, error = sender(webhook_url, payload_bytes, headers, timeout_seconds)
        final_status_code = status_code
        delivered = error is None and status_code is not None and 200 <= status_code < 300

        if delivered:
            attempts.append(DeliveryAttempt(attempt, True, status_code, None, None))
            return True, final_status_code, attempts

        retryable = should_retry(status_code, error)
        wait_seconds = None
        if retryable and attempt < retry_policy.max_attempts:
            wait_seconds = compute_backoff_seconds(retry_policy, attempt, parse_retry_after(response_headers))
        attempts.append(DeliveryAttempt(attempt, False, status_code, error, wait_seconds))

        if wait_seconds is None:
            break

        sleep_fn(wait_seconds)

    return False, final_status_code, attempts


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Send CI gate metadata to a webhook endpoint.")
    parser.add_argument("--webhook-url", required=True)
    parser.add_argument("--metadata-path", required=True)
    parser.add_argument("--summary-path")
    parser.add_argument("--snapshot-path")
    parser.add_argument("--diff-path")
    parser.add_argument("--report-local-path")
    parser.add_argument("--event-name", default="zap_security_gate.completed")
    parser.add_argument("--provider", default="auto")
    parser.add_argument("--secret")
    parser.add_argument("--bearer-token")
    parser.add_argument("--headers-json", default="")
    parser.add_argument("--timeout-seconds", type=float, default=15.0)
    parser.add_argument("--max-attempts", type=int, default=4)
    parser.add_argument("--initial-backoff-seconds", type=float, default=2.0)
    parser.add_argument("--max-backoff-seconds", type=float, default=30.0)
    parser.add_argument("--backoff-multiplier", type=float, default=2.0)
    parser.add_argument("--output-path")
    parser.add_argument("--allow-failure", type=parse_bool, default=False)
    args = parser.parse_args()

    metadata_path = Path(args.metadata_path).resolve()
    if not metadata_path.exists():
        raise SystemExit(f"Metadata file does not exist: {metadata_path}")

    provider = detect_provider(args.provider, os.environ)
    metadata = load_json(metadata_path)
    artifact_paths = {
        "metadataPath": metadata_path,
        "summaryPath": Path(args.summary_path).resolve() if args.summary_path else None,
        "snapshotPath": Path(args.snapshot_path).resolve() if args.snapshot_path else None,
        "diffPath": Path(args.diff_path).resolve() if args.diff_path else None,
        "reportLocalPath": Path(args.report_local_path).resolve() if args.report_local_path else None,
    }
    payload = build_payload(args.event_name, provider, os.environ, metadata, artifact_paths)
    payload_bytes = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
    delivery_id = str(uuid.uuid4())

    headers = {
        "Content-Type": "application/json",
        "User-Agent": "mcp-zap-server-webhook/1.0",
        "X-MCP-ZAP-Event": args.event_name,
        "X-MCP-ZAP-Delivery-Id": delivery_id,
    }
    headers.update(parse_headers_json(args.headers_json))
    if args.secret:
        headers["X-MCP-ZAP-Signature-Sha256"] = compute_signature(args.secret, payload_bytes)
    if args.bearer_token:
        headers["Authorization"] = f"Bearer {args.bearer_token}"

    retry_policy = RetryPolicy(
        max_attempts=max(1, args.max_attempts),
        initial_backoff_seconds=max(0.1, args.initial_backoff_seconds),
        max_backoff_seconds=max(0.1, args.max_backoff_seconds),
        backoff_multiplier=max(1.0, args.backoff_multiplier),
    )

    delivered, status_code, attempts = deliver_with_retries(
        args.webhook_url,
        payload_bytes,
        headers,
        args.timeout_seconds,
        retry_policy,
    )

    output_path = Path(args.output_path).resolve() if args.output_path else None
    if output_path:
        record = DeliveryRecord(
            delivered=delivered,
            attempts=len(attempts),
            final_status_code=status_code,
            event_name=args.event_name,
            provider=provider,
            delivery_id=delivery_id,
            webhook_target=redact_webhook_url(args.webhook_url),
            metadata_path=str(metadata_path),
            output_path=str(output_path),
            attempts_detail=attempts,
        )
        write_json(output_path, asdict(record))

    summary_lines = [
        "## Webhook Callback",
        "",
        f"- Event: `{args.event_name}`",
        f"- Provider: `{provider}`",
        f"- Delivered: `{'yes' if delivered else 'no'}`",
        f"- Attempts: `{len(attempts)}`",
        f"- Final status code: `{status_code if status_code is not None else 'n/a'}`",
        f"- Target: `{redact_webhook_url(args.webhook_url)}`",
    ]
    if output_path:
        summary_lines.append(f"- Delivery record: `{output_path}`")
    append_github_summary("\n".join(summary_lines) + "\n")

    append_github_output("delivered", "true" if delivered else "false")
    append_github_output("attempt_count", str(len(attempts)))
    append_github_output("status_code", "" if status_code is None else str(status_code))
    append_github_output("output_path", "" if output_path is None else str(output_path))

    if not delivered and not args.allow_failure:
        print(
            f"Webhook delivery failed after {len(attempts)} attempts to {redact_webhook_url(args.webhook_url)}.",
            file=sys.stderr,
        )
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
