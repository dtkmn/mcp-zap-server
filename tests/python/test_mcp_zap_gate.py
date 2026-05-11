import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock


MODULE_PATH = Path(__file__).resolve().parents[2] / ".github" / "actions" / "zap-security-gate" / "mcp_zap_gate.py"
SPEC = importlib.util.spec_from_file_location("mcp_zap_gate", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class McpZapGateHelpersTest(unittest.TestCase):
    def test_extract_tool_text_from_mcp_content(self):
        payload = {
            "jsonrpc": "2.0",
            "id": 1,
            "result": {
                "content": [
                    {"type": "text", "text": "Direct spider scan started.\nScan ID: spider-1"},
                    {"type": "text", "text": "Use zap_spider_status next."},
                ]
            },
        }

        text = MODULE.extract_tool_text(payload)

        self.assertIn("Scan ID: spider-1", text)
        self.assertIn("Use zap_spider_status next.", text)

    def test_extract_tool_text_decodes_json_encoded_text_payloads(self):
        payload = {
            "jsonrpc": "2.0",
            "id": 1,
            "result": {
                "content": [
                    {
                        "type": "text",
                        "text": "\"Guided crawl started.\\nOperation ID: zop_123\\nExecution Mode: direct\"",
                    }
                ]
            },
        }

        text = MODULE.extract_tool_text(payload)

        self.assertIn("Guided crawl started.", text)
        self.assertIn("Operation ID: zop_123", text)
        self.assertIn("Execution Mode: direct", text)

    def test_parse_completed_prefers_completed_label(self):
        text = "Direct active scan status:\nScan ID: active-1\nProgress: 45%\nCompleted: yes\n"

        self.assertTrue(MODULE.parse_completed(text))

    def test_unwrap_mcp_body_extracts_sse_data_payload(self):
        body = 'event:message\ndata:{"jsonrpc":"2.0","result":{"ok":true}}\n'

        self.assertEqual(body := MODULE.unwrap_mcp_body(body), '{"jsonrpc":"2.0","result":{"ok":true}}')

    def test_parse_operation_id_reads_guided_operation_id(self):
        text = "Guided crawl started.\nOperation ID: zop_123\nExecution Mode: direct\n"

        self.assertEqual(MODULE.parse_operation_id(text), "zop_123")

    def test_parse_report_path_reads_guided_report_output(self):
        text = "Guided report generated.\nFormat: html\nPath: /tmp/report.html\n"

        self.assertEqual(MODULE.parse_report_path(text), "/tmp/report.html")

    def test_parse_count_reads_findings_diff_summary_lines(self):
        text = (
            "Findings diff summary\n"
            "New Findings: 3\n"
            "Resolved Findings: 1\n"
            "Unchanged Findings: 7\n"
        )

        self.assertEqual(MODULE.parse_count(text, "new"), 3)
        self.assertEqual(MODULE.parse_count(text, "resolved"), 1)
        self.assertEqual(MODULE.parse_count(text, "unchanged"), 7)

    def test_map_report_path_translates_container_root(self):
        mapped = MODULE.map_report_path(
            "/zap/wrk/reports/zap-report-123.html",
            "/zap/wrk/reports",
            "/tmp/zap-work/reports",
        )

        self.assertEqual(mapped, Path("/tmp/zap-work/reports/zap-report-123.html"))

    def test_canonicalize_snapshot_normalizes_legacy_payload_and_dedupes(self):
        payload = {
            "version": 1,
            "baseUrl": "https://Example.com",
            "exportedAt": "2026-03-22T00:00:00Z",
            "fingerprints": [
                {
                    "pluginId": "40018",
                    "alertName": "SQL Injection",
                    "risk": "high",
                    "confidence": "Medium",
                    "url": "HTTPS://EXAMPLE.com",
                    "param": "id",
                    "fingerprint": "legacy-a",
                },
                {
                    "pluginId": "40018",
                    "alertName": "SQL Injection",
                    "risk": "High",
                    "confidence": "Medium",
                    "url": "https://example.com/",
                    "param": "id",
                    "fingerprint": "legacy-b",
                },
            ],
        }

        snapshot, source_contract = MODULE.canonicalize_snapshot(payload)

        self.assertEqual(source_contract, MODULE.LEGACY_FINDINGS_SNAPSHOT_CONTRACT_VERSION)
        self.assertEqual(snapshot["contract_version"], MODULE.CI_GATE_FINDINGS_SNAPSHOT_CONTRACT_VERSION)
        self.assertEqual(snapshot["target_url"], "https://example.com/")
        self.assertEqual(snapshot["finding_count"], 1)
        self.assertEqual(snapshot["findings"][0]["risk"], "High")
        self.assertTrue(snapshot["findings"][0]["fingerprint"].startswith("sha256:"))

    def test_canonicalize_snapshot_normalizes_confidence_and_query_order(self):
        payload = {
            "version": 1,
            "baseUrl": "https://Example.com/search?b=2&a=1",
            "fingerprints": [
                {
                    "pluginId": "40012",
                    "alertName": "Cross Site Scripting",
                    "risk": "medium",
                    "confidence": "high",
                    "url": "https://example.com/search?b=2&a=1",
                    "param": "q",
                },
                {
                    "pluginId": "40012",
                    "alertName": "Cross Site Scripting",
                    "risk": "Medium",
                    "confidence": "High",
                    "url": "HTTPS://EXAMPLE.com/search?a=1&b=2",
                    "param": "q",
                },
            ],
        }

        snapshot, _source_contract = MODULE.canonicalize_snapshot(payload)

        self.assertEqual(snapshot["target_url"], "https://example.com/search?a=1&b=2")
        self.assertEqual(snapshot["finding_count"], 1)
        self.assertEqual(snapshot["findings"][0]["confidence"], "High")
        self.assertEqual(snapshot["findings"][0]["url"], "https://example.com/search?a=1&b=2")

    def test_load_suppressions_contract_tracks_active_and_expired_rules(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            path = Path(tmpdir) / "suppressions.json"
            path.write_text(
                json.dumps(
                    {
                        "contract_version": MODULE.CI_GATE_SUPPRESSIONS_CONTRACT_VERSION,
                        "suppressions": [
                            {
                                "id": "active-sqli",
                                "match": {
                                    "plugin_id": "40018",
                                    "alert_name": "SQL Injection",
                                    "url": "https://example.com/",
                                    "param": "id",
                                },
                                "reason": "Known issue",
                            },
                            {
                                "id": "expired-xss",
                                "fingerprint": "sha256:deadbeef",
                                "expires_at": "2020-01-01T00:00:00Z",
                            },
                        ],
                    }
                ),
                encoding="utf-8",
            )

            contract = MODULE.load_suppressions_contract(path)

            self.assertEqual(contract["rule_count"], 2)
            self.assertEqual(contract["active_rule_count"], 1)
            self.assertEqual(contract["expired_rule_count"], 1)

            findings = [
                {
                    "plugin_id": "40018",
                    "alert_name": "SQL Injection",
                    "risk": "High",
                    "confidence": "Medium",
                    "url": "https://example.com/",
                    "param": "id",
                    "fingerprint": "sha256:match-me",
                },
                {
                    "plugin_id": "40012",
                    "alert_name": "Cross Site Scripting",
                    "risk": "Medium",
                    "confidence": "High",
                    "url": "https://example.com/search",
                    "param": "q",
                    "fingerprint": "sha256:keep-me",
                },
            ]

            filtered, suppressed_count, matched_rule_ids = MODULE.apply_suppressions(findings, contract)

            self.assertEqual(suppressed_count, 1)
            self.assertEqual(matched_rule_ids, ["active-sqli"])
            self.assertEqual([finding["fingerprint"] for finding in filtered], ["sha256:keep-me"])

    def test_build_diff_contract_prefers_normalized_snapshot_target_url(self):
        baseline_snapshot = {
            "contract_version": MODULE.CI_GATE_FINDINGS_SNAPSHOT_CONTRACT_VERSION,
            "target_url": "https://example.com/search?a=1&b=2",
            "finding_count": 0,
            "findings": [],
        }
        current_snapshot = {
            "contract_version": MODULE.CI_GATE_FINDINGS_SNAPSHOT_CONTRACT_VERSION,
            "target_url": "https://example.com/search?a=1&b=2",
            "finding_count": 0,
            "findings": [],
        }

        diff_contract = MODULE.build_diff_contract(
            "HTTPS://EXAMPLE.com/search?b=2&a=1",
            baseline_snapshot,
            current_snapshot,
            {
                "requested": False,
                "used": False,
                "reason": None,
                "matched_rule_ids": [],
                "suppressed_baseline_findings": 0,
                "suppressed_current_findings": 0,
            },
        )

        self.assertEqual(diff_contract["target_url"], "https://example.com/search?a=1&b=2")

    def test_load_seed_requests_defaults_url_and_json_content_type(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            path = Path(tmpdir) / "seed.json"
            path.write_text(
                json.dumps(
                    {
                        "requests": [
                            {
                                "name": "bid-request",
                                "method": "post",
                                "body": {"id": "ci-bid"},
                                "expectedStatus": [200, 204],
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )

            requests = MODULE.load_seed_requests(path, "http://app:8080/bid-request")

            self.assertEqual(requests[0]["name"], "bid-request")
            self.assertEqual(requests[0]["method"], "POST")
            self.assertEqual(requests[0]["url"], "http://app:8080/bid-request")
            self.assertEqual(requests[0]["expected_statuses"], {200, 204})

    def test_execute_seed_requests_replays_through_proxy_without_echoing_body_or_headers(self):
        class FakeResponse:
            def getcode(self):
                return 200

            def read(self):
                return b'{"ok":true}'

            def close(self):
                pass

        class FakeOpener:
            def __init__(self):
                self.requests = []

            def open(self, request, timeout):
                self.requests.append((request, timeout))
                return FakeResponse()

        opener = FakeOpener()
        seed_requests = [
            {
                "name": "bid-request",
                "method": "POST",
                "url": "http://app:8080/bid-request?token=secret&debug=true",
                "headers": {"X-Test": "secret-ish"},
                "body": {"id": "ci-bid"},
                "expected_statuses": {200},
            }
        ]

        result = MODULE.execute_seed_requests(
            seed_requests,
            "http://proxy-user:proxy-pass@127.0.0.1:8090?token=proxy-secret",
            7,
            opener=opener,
        )

        self.assertEqual(result["contract_version"], "ci_gate_seed_requests/v1")
        self.assertEqual(result["proxy_url"], "http://127.0.0.1:8090?redacted")
        self.assertEqual(result["request_count"], 1)
        self.assertEqual(result["failure_count"], 0)
        self.assertEqual(result["requests"][0]["method"], "POST")
        self.assertEqual(result["requests"][0]["url"], "http://app:8080/bid-request?redacted")
        self.assertEqual(result["requests"][0]["status"], 200)
        self.assertEqual(result["requests"][0]["expected_status"], "200")
        self.assertTrue(result["requests"][0]["ok"])
        self.assertNotIn("headers", result["requests"][0])
        self.assertNotIn("body", result["requests"][0])
        self.assertNotIn("secret", json.dumps(result))
        request, timeout = opener.requests[0]
        self.assertEqual(timeout, 7)
        self.assertEqual(request.full_url, "http://app:8080/bid-request?token=secret&debug=true")
        self.assertEqual(request.get_method(), "POST")
        self.assertEqual(request.data, b'{"id":"ci-bid"}')
        self.assertEqual(request.headers["Content-type"], "application/json")

    def test_execute_seed_requests_records_unexpected_status(self):
        class FakeHttpErrorOpener:
            def open(self, _request, timeout):
                raise MODULE.urllib.error.HTTPError(
                    "http://app:8080/bid-request",
                    400,
                    "Bad Request",
                    {},
                    None,
                )

        result = MODULE.execute_seed_requests(
            [
                {
                    "name": "bid-request",
                    "method": "POST",
                    "url": "http://app:8080/bid-request",
                    "headers": {},
                    "body": "{}",
                    "expected_statuses": {200},
                }
            ],
            "http://127.0.0.1:8090",
            30,
            opener=FakeHttpErrorOpener(),
        )

        self.assertEqual(result["failure_count"], 1)
        self.assertFalse(result["requests"][0]["ok"])
        self.assertEqual(result["requests"][0]["status"], 400)

    def test_main_writes_deterministic_gate_contract_artifacts(self):
        class FakeMcpHttpClient:
            snapshot_text = ""
            report_path = ""

            def __init__(self, _server_url: str, _api_key: str, timeout_seconds: int = 60) -> None:
                self.timeout_seconds = timeout_seconds

            def list_tools(self):
                return [
                    "zap_spider_start",
                    "zap_spider_status",
                    "zap_active_scan_start",
                    "zap_active_scan_status",
                    "zap_passive_scan_wait",
                    "zap_get_findings_summary",
                    "zap_findings_snapshot",
                    "zap_generate_report",
                ]

            def call_tool(self, name, _arguments):
                if name == "zap_spider_start":
                    return "Direct spider scan started.\nScan ID: spider-1"
                if name == "zap_spider_status":
                    return "Direct spider scan status:\nScan ID: spider-1\nCompleted: yes\n"
                if name == "zap_active_scan_start":
                    return "Direct active scan started.\nScan ID: active-1"
                if name == "zap_active_scan_status":
                    return "Direct active scan status:\nScan ID: active-1\nCompleted: yes\n"
                if name == "zap_passive_scan_wait":
                    return "Passive scan queue drained."
                if name == "zap_get_findings_summary":
                    return "this output should be ignored in expert mode"
                if name == "zap_findings_snapshot":
                    return self.snapshot_text
                if name == "zap_generate_report":
                    return self.report_path
                raise AssertionError(f"Unexpected tool call: {name}")

        baseline_payload = {
            "version": 1,
            "baseUrl": "https://example.com",
            "exportedAt": "2026-03-21T00:00:00Z",
            "fingerprints": [
                {
                    "pluginId": "40018",
                    "alertName": "SQL Injection",
                    "risk": "High",
                    "confidence": "Medium",
                    "url": "https://example.com/",
                    "param": "id",
                    "fingerprint": "legacy-baseline",
                }
            ],
        }
        snapshot_variant_a = {
            "version": 1,
            "baseUrl": "https://example.com",
            "exportedAt": "2026-03-22T01:00:00Z",
            "fingerprints": [
                {
                    "pluginId": "40012",
                    "alertName": "Cross Site Scripting",
                    "risk": "Medium",
                    "confidence": "high",
                    "url": "https://example.com/search?b=2&a=1",
                    "param": "q",
                    "fingerprint": "legacy-xss-a",
                },
                {
                    "pluginId": "40018",
                    "alertName": "SQL Injection",
                    "risk": "High",
                    "confidence": "Medium",
                    "url": "https://example.com",
                    "param": "id",
                    "fingerprint": "legacy-sqli-a",
                },
            ],
        }
        snapshot_variant_b = {
            "version": 1,
            "baseUrl": "https://example.com",
            "exportedAt": "2026-03-22T02:00:00Z",
            "fingerprints": [
                {
                    "pluginId": "40018",
                    "alertName": "SQL Injection",
                    "risk": "High",
                    "confidence": "Medium",
                    "url": "https://EXAMPLE.com/",
                    "param": "id",
                    "fingerprint": "legacy-sqli-b",
                },
                {
                    "pluginId": "40012",
                    "alertName": "Cross Site Scripting",
                    "risk": "Medium",
                    "confidence": "High",
                    "url": "https://EXAMPLE.com/search?a=1&b=2",
                    "param": "q",
                    "fingerprint": "legacy-xss-b",
                },
            ],
        }

        artifact_names = [
            "artifact-manifest.json",
            "current-findings.json",
            "findings-diff.json",
            "findings-diff.txt",
            "findings-summary.md",
            "gate-metadata.json",
            "gate-summary.md",
            "reports/zap-report.html",
        ]

        with tempfile.TemporaryDirectory() as tmpdir:
            tmp_path = Path(tmpdir)
            baseline_path = tmp_path / "baseline.json"
            baseline_path.write_text(json.dumps(baseline_payload), encoding="utf-8")

            report_root_a = tmp_path / "report-root-a"
            report_root_b = tmp_path / "report-root-b"
            report_root_a.mkdir()
            report_root_b.mkdir()
            (report_root_a / "generated-a.html").write_text("<html>stable report</html>\n", encoding="utf-8")
            (report_root_b / "generated-b.html").write_text("<html>stable report</html>\n", encoding="utf-8")

            output_a = tmp_path / "out-a"
            output_b = tmp_path / "out-b"

            def run_gate(snapshot_payload, report_path, report_root, output_dir):
                FakeMcpHttpClient.snapshot_text = json.dumps(snapshot_payload)
                FakeMcpHttpClient.report_path = report_path
                with mock.patch.object(MODULE, "McpHttpClient", FakeMcpHttpClient):
                    result = MODULE.main(
                        [
                            "--server-url",
                            "http://example.com/mcp",
                            "--api-key",
                            "test-key",
                            "--target-url",
                            "https://example.com",
                            "--baseline-file",
                            str(baseline_path),
                            "--output-dir",
                            str(output_dir),
                            "--report-root-container",
                            "/zap/wrk/reports",
                            "--report-root-local",
                            str(report_root),
                        ]
                    )
                self.assertEqual(result, 1)

            run_gate(snapshot_variant_a, "/zap/wrk/reports/generated-a.html", report_root_a, output_a)
            run_gate(snapshot_variant_b, "/zap/wrk/reports/generated-b.html", report_root_b, output_b)

            for artifact_name in artifact_names:
                self.assertEqual(
                    (output_a / artifact_name).read_text(encoding="utf-8"),
                    (output_b / artifact_name).read_text(encoding="utf-8"),
                    artifact_name,
                )

            metadata = json.loads((output_a / "gate-metadata.json").read_text(encoding="utf-8"))
            self.assertEqual(metadata["contract_version"], MODULE.CI_GATE_RESULT_CONTRACT_VERSION)
            self.assertEqual(metadata["findings"]["new_findings"], 1)
            self.assertFalse(metadata["gate_passed"])
            self.assertEqual(metadata["report"]["artifact_path"], "reports/zap-report.html")

    def test_main_prefers_expert_surface_when_both_surfaces_are_available(self):
        class FakeMcpHttpClient:
            def __init__(self, _server_url: str, _api_key: str, timeout_seconds: int = 60) -> None:
                self.timeout_seconds = timeout_seconds

            def list_tools(self):
                return [
                    "zap_crawl_start",
                    "zap_crawl_status",
                    "zap_attack_start",
                    "zap_attack_status",
                    "zap_findings_summary",
                    "zap_report_generate",
                    "zap_spider_start",
                    "zap_spider_status",
                    "zap_active_scan_start",
                    "zap_active_scan_status",
                    "zap_passive_scan_wait",
                    "zap_get_findings_summary",
                    "zap_findings_snapshot",
                    "zap_generate_report",
                ]

            def call_tool(self, name, _arguments):
                if name == "zap_spider_start":
                    return "Direct spider scan started.\nScan ID: spider-1"
                if name == "zap_spider_status":
                    return "Direct spider scan status:\nScan ID: spider-1\nCompleted: yes\n"
                if name == "zap_passive_scan_wait":
                    return "Passive scan queue drained."
                if name == "zap_findings_snapshot":
                    return json.dumps(
                        {
                            "version": 1,
                            "baseUrl": "https://example.com",
                            "exportedAt": "2026-03-22T00:00:00Z",
                            "fingerprints": [
                                {
                                    "pluginId": "40018",
                                    "alertName": "SQL Injection",
                                    "risk": "High",
                                    "confidence": "Medium",
                                    "url": "https://example.com/",
                                    "param": "id",
                                    "fingerprint": "legacy",
                                }
                            ],
                        }
                    )
                if name == "zap_generate_report":
                    return "/zap/wrk/reports/generated.html"
                raise AssertionError(f"Unexpected tool call: {name}")

        with tempfile.TemporaryDirectory() as tmpdir:
            tmp_path = Path(tmpdir)
            report_root = tmp_path / "reports"
            report_root.mkdir()
            (report_root / "generated.html").write_text("<html>report</html>\n", encoding="utf-8")
            output_dir = tmp_path / "artifacts"

            with mock.patch.object(MODULE, "McpHttpClient", FakeMcpHttpClient):
                result = MODULE.main(
                    [
                        "--server-url",
                        "http://example.com/mcp",
                        "--api-key",
                        "test-key",
                        "--target-url",
                        "https://example.com",
                        "--output-dir",
                        str(output_dir),
                        "--report-root-container",
                        "/zap/wrk/reports",
                        "--report-root-local",
                        str(report_root),
                            "--run-active-scan",
                            "false",
                            "--fail-on-new-findings",
                            "false",
                        ]
                    )

            self.assertEqual(result, 0)
            metadata = json.loads((output_dir / "gate-metadata.json").read_text(encoding="utf-8"))
            self.assertEqual(metadata["tool_surface"], "expert")
            self.assertTrue((output_dir / "current-findings.json").exists())
            self.assertEqual(metadata["report"]["artifact_path"], "reports/zap-report.html")

    def test_main_records_successful_seed_request_artifact(self):
        class FakeMcpHttpClient:
            def __init__(self, _server_url: str, _api_key: str, timeout_seconds: int = 60) -> None:
                self.timeout_seconds = timeout_seconds

            def list_tools(self):
                return [
                    "zap_spider_start",
                    "zap_spider_status",
                    "zap_active_scan_start",
                    "zap_active_scan_status",
                    "zap_passive_scan_wait",
                    "zap_get_findings_summary",
                    "zap_findings_snapshot",
                    "zap_generate_report",
                ]

            def call_tool(self, name, _arguments):
                if name == "zap_spider_start":
                    return "Direct spider scan started.\nScan ID: spider-1"
                if name == "zap_spider_status":
                    return "Direct spider scan status:\nScan ID: spider-1\nCompleted: yes\n"
                if name == "zap_passive_scan_wait":
                    return "Passive scan queue drained."
                if name == "zap_findings_snapshot":
                    return json.dumps({"version": 1, "baseUrl": "https://example.com/api", "fingerprints": []})
                if name == "zap_generate_report":
                    return "/zap/wrk/reports/generated.html"
                raise AssertionError(f"Unexpected tool call: {name}")

        seed_results = {
            "contract_version": "ci_gate_seed_requests/v1",
            "proxy_url": "http://127.0.0.1:8090",
            "request_count": 1,
            "failure_count": 0,
            "requests": [
                {
                    "name": "api-post",
                    "method": "POST",
                    "url": "https://example.com/api",
                    "status": 200,
                    "expected_status": "200",
                    "ok": True,
                    "response_bytes": 2,
                }
            ],
        }

        with tempfile.TemporaryDirectory() as tmpdir:
            tmp_path = Path(tmpdir)
            seed_path = tmp_path / "seed.json"
            seed_path.write_text(
                json.dumps({"requests": [{"name": "api-post", "method": "POST", "body": {"ok": True}}]}),
                encoding="utf-8",
            )
            report_root = tmp_path / "reports"
            report_root.mkdir()
            (report_root / "generated.html").write_text("<html>report</html>\n", encoding="utf-8")
            output_dir = tmp_path / "artifacts"

            with mock.patch.object(MODULE, "McpHttpClient", FakeMcpHttpClient), mock.patch.object(
                    MODULE, "execute_seed_requests", return_value=seed_results) as execute_seed_requests:
                result = MODULE.main(
                    [
                        "--server-url",
                        "http://example.com/mcp",
                        "--api-key",
                        "test-key",
                        "--target-url",
                        "https://example.com/api",
                        "--seed-requests-file",
                        str(seed_path),
                        "--output-dir",
                        str(output_dir),
                        "--report-root-container",
                        "/zap/wrk/reports",
                        "--report-root-local",
                        str(report_root),
                        "--run-active-scan",
                        "false",
                        "--fail-on-new-findings",
                        "false",
                    ]
                )

            self.assertEqual(result, 0)
            execute_seed_requests.assert_called_once()
            metadata = json.loads((output_dir / "gate-metadata.json").read_text(encoding="utf-8"))
            manifest = json.loads((output_dir / "artifact-manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(metadata["seed_requests"]["request_count"], 1)
            self.assertEqual(metadata["seed_requests"]["failure_count"], 0)
            self.assertEqual(metadata["seed_requests"]["result_path"], "seed-requests-results.json")
            self.assertEqual(
                json.loads((output_dir / "seed-requests-results.json").read_text(encoding="utf-8")),
                seed_results,
            )
            self.assertIn("seed_requests", [artifact["name"] for artifact in manifest["artifacts"]])

    def test_main_fails_when_enforced_baseline_file_is_missing(self):
        class FakeMcpHttpClient:
            def __init__(self, _server_url: str, _api_key: str, timeout_seconds: int = 60) -> None:
                self.timeout_seconds = timeout_seconds

            def list_tools(self):
                return [
                    "zap_spider_start",
                    "zap_spider_status",
                    "zap_active_scan_start",
                    "zap_active_scan_status",
                    "zap_passive_scan_wait",
                    "zap_get_findings_summary",
                    "zap_findings_snapshot",
                    "zap_generate_report",
                ]

            def call_tool(self, name, _arguments):
                if name == "zap_spider_start":
                    return "Direct spider scan started.\nScan ID: spider-1"
                if name == "zap_spider_status":
                    return "Direct spider scan status:\nScan ID: spider-1\nCompleted: yes\n"
                if name == "zap_passive_scan_wait":
                    return "Passive scan queue drained."
                if name == "zap_findings_snapshot":
                    return json.dumps({"version": 1, "baseUrl": "https://example.com", "fingerprints": []})
                if name == "zap_generate_report":
                    return "/zap/wrk/reports/generated.html"
                raise AssertionError(f"Unexpected tool call: {name}")

        with tempfile.TemporaryDirectory() as tmpdir:
            tmp_path = Path(tmpdir)
            report_root = tmp_path / "reports"
            report_root.mkdir()
            (report_root / "generated.html").write_text("<html>report</html>\n", encoding="utf-8")
            output_dir = tmp_path / "artifacts"

            with mock.patch.object(MODULE, "McpHttpClient", FakeMcpHttpClient):
                result = MODULE.main(
                    [
                        "--server-url",
                        "http://example.com/mcp",
                        "--api-key",
                        "test-key",
                        "--target-url",
                        "https://example.com",
                        "--baseline-file",
                        str(tmp_path / "missing-baseline.json"),
                        "--output-dir",
                        str(output_dir),
                        "--report-root-container",
                        "/zap/wrk/reports",
                        "--report-root-local",
                        str(report_root),
                        "--run-active-scan",
                        "false",
                    ]
                )

            metadata = json.loads((output_dir / "gate-metadata.json").read_text(encoding="utf-8"))
            self.assertEqual(result, 1)
            self.assertFalse(metadata["gate_passed"])
            self.assertEqual(metadata["baseline_mode"], "enforce")
            self.assertEqual(metadata["baseline"]["diff_unavailable_reason"], "baseline_file_missing")
            self.assertEqual(metadata["enforcement"]["failure_reason"], "baseline_file_missing")
            self.assertTrue((output_dir / "baseline-note.txt").exists())

    def test_main_allows_missing_baseline_in_seed_mode(self):
        class FakeMcpHttpClient:
            def __init__(self, _server_url: str, _api_key: str, timeout_seconds: int = 60) -> None:
                self.timeout_seconds = timeout_seconds

            def list_tools(self):
                return [
                    "zap_spider_start",
                    "zap_spider_status",
                    "zap_active_scan_start",
                    "zap_active_scan_status",
                    "zap_passive_scan_wait",
                    "zap_get_findings_summary",
                    "zap_findings_snapshot",
                    "zap_generate_report",
                ]

            def call_tool(self, name, _arguments):
                if name == "zap_spider_start":
                    return "Direct spider scan started.\nScan ID: spider-1"
                if name == "zap_spider_status":
                    return "Direct spider scan status:\nScan ID: spider-1\nCompleted: yes\n"
                if name == "zap_passive_scan_wait":
                    return "Passive scan queue drained."
                if name == "zap_findings_snapshot":
                    return json.dumps({"version": 1, "baseUrl": "https://example.com", "fingerprints": []})
                if name == "zap_generate_report":
                    return "/zap/wrk/reports/generated.html"
                raise AssertionError(f"Unexpected tool call: {name}")

        with tempfile.TemporaryDirectory() as tmpdir:
            tmp_path = Path(tmpdir)
            report_root = tmp_path / "reports"
            report_root.mkdir()
            (report_root / "generated.html").write_text("<html>report</html>\n", encoding="utf-8")
            output_dir = tmp_path / "artifacts"

            with mock.patch.object(MODULE, "McpHttpClient", FakeMcpHttpClient):
                result = MODULE.main(
                    [
                        "--server-url",
                        "http://example.com/mcp",
                        "--api-key",
                        "test-key",
                        "--target-url",
                        "https://example.com",
                        "--baseline-file",
                        str(tmp_path / "missing-baseline.json"),
                        "--baseline-mode",
                        "seed",
                        "--output-dir",
                        str(output_dir),
                        "--report-root-container",
                        "/zap/wrk/reports",
                        "--report-root-local",
                        str(report_root),
                        "--run-active-scan",
                        "false",
                    ]
                )

            metadata = json.loads((output_dir / "gate-metadata.json").read_text(encoding="utf-8"))
            self.assertEqual(result, 0)
            self.assertTrue(metadata["gate_passed"])
            self.assertEqual(metadata["baseline_mode"], "seed")
            self.assertEqual(metadata["baseline"]["diff_unavailable_reason"], "baseline_file_missing")
            self.assertIsNone(metadata["enforcement"]["failure_reason"])

    def test_main_fails_when_guided_surface_cannot_diff_in_enforce_mode(self):
        class FakeMcpHttpClient:
            def __init__(self, _server_url: str, _api_key: str, timeout_seconds: int = 60) -> None:
                self.timeout_seconds = timeout_seconds

            def list_tools(self):
                return [
                    "zap_crawl_start",
                    "zap_crawl_status",
                    "zap_attack_start",
                    "zap_attack_status",
                    "zap_passive_scan_wait",
                    "zap_findings_summary",
                    "zap_report_generate",
                ]

            def call_tool(self, name, _arguments):
                if name == "zap_crawl_start":
                    return "Guided crawl started.\nOperation ID: crawl-1\n"
                if name == "zap_crawl_status":
                    return "Guided crawl status.\nCompleted: yes\n"
                if name == "zap_passive_scan_wait":
                    return "Passive scan queue drained."
                if name == "zap_findings_summary":
                    return "No findings."
                if name == "zap_report_generate":
                    return "Guided report generated.\nPath: /zap/wrk/reports/generated.html\n"
                raise AssertionError(f"Unexpected tool call: {name}")

        with tempfile.TemporaryDirectory() as tmpdir:
            tmp_path = Path(tmpdir)
            baseline_path = tmp_path / "baseline.json"
            baseline_path.write_text(
                json.dumps({"version": 1, "baseUrl": "https://example.com", "fingerprints": []}),
                encoding="utf-8",
            )
            report_root = tmp_path / "reports"
            report_root.mkdir()
            (report_root / "generated.html").write_text("<html>report</html>\n", encoding="utf-8")
            output_dir = tmp_path / "artifacts"

            with mock.patch.object(MODULE, "McpHttpClient", FakeMcpHttpClient):
                result = MODULE.main(
                    [
                        "--server-url",
                        "http://example.com/mcp",
                        "--api-key",
                        "test-key",
                        "--target-url",
                        "https://example.com",
                        "--baseline-file",
                        str(baseline_path),
                        "--output-dir",
                        str(output_dir),
                        "--report-root-container",
                        "/zap/wrk/reports",
                        "--report-root-local",
                        str(report_root),
                        "--run-active-scan",
                        "false",
                    ]
                )

            metadata = json.loads((output_dir / "gate-metadata.json").read_text(encoding="utf-8"))
            self.assertEqual(result, 1)
            self.assertFalse(metadata["gate_passed"])
            self.assertEqual(metadata["tool_surface"], "guided")
            self.assertEqual(
                metadata["enforcement"]["failure_reason"],
                "guided_surface_without_snapshot_support",
            )


if __name__ == "__main__":
    unittest.main()
