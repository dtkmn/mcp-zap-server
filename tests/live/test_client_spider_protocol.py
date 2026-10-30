"""Offline protocol and behavioral fixtures for the guided live harness."""

import contextlib
import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from client_spider_juice_shop import McpClient, Suite, content_text, field, main, requested_resources, rpc_response


class Response(io.BytesIO):
    def __init__(self, body, content_type):
        super().__init__(body.encode())
        self.headers = {"Content-Type": content_type}
        self.status = 200


class ProtocolTests(unittest.TestCase):
    def test_json_result(self):
        response = Response('{"jsonrpc":"2.0","id":4,"result":{"tools":[]}}', "application/json")
        self.assertEqual({"tools": []}, rpc_response(response, 4)["result"])

    def test_sse_skips_notifications_and_unrelated_responses(self):
        response = Response(
            ': keepalive\r\n\r\n'
            'event: message\r\ndata: {"method":"notifications/progress"}\r\n\r\n'
            'data: {"id":3,"result":{}}\r\n\r\n'
            'event: message\r\ndata: {"jsonrpc":"2.0",\r\n'
            'data: "id":4,"result":{"ok":true}}\r\n\r\n'
            'data: never-read\r\n\r\n', "text/event-stream; charset=utf-8")
        self.assertEqual({"ok": True}, rpc_response(response, 4)["result"])

    def test_empty_json_notification_response(self):
        self.assertIsNone(rpc_response(Response('', "application/json"), None))

    def test_unfinished_sse_fails_instead_of_reporting_success(self):
        with self.assertRaisesRegex(RuntimeError, "before the matching"):
            rpc_response(Response('data: {"id":2}\n\n', "text/event-stream"), 4)

    def test_plain_and_json_encoded_tool_text(self):
        for value in ['Scan ID: 2\nProgress: 100%', '"Scan ID: 2\\nProgress: 100%"']:
            result = {"content": [{"type": "text", "text": value}]}
            self.assertEqual("2", field(content_text(result), "Scan ID"))

    def test_native_scan_id_is_not_confused_with_job_id(self):
        response = 'Job ID: abc\nZAP Scan ID: 2\nProgress: 0%'
        self.assertEqual("2", field(response, "ZAP Scan ID"))
        with self.assertRaises(AssertionError):
            field(response, "Scan ID")

    def test_blank_field_cannot_consume_next_line(self):
        for response in ["Scan ID: \nProgress: 100%", "Scan ID:\r\nProgress: 100%"]:
            with self.assertRaises(AssertionError):
                field(response, "Scan ID")
        self.assertEqual("2", field("Scan ID: 2\r\nProgress: 100%", "Scan ID"))

    def test_repeated_requests_and_query_variations_do_not_inflate_resource_coverage(self):
        messages = [{"requestHeader": f"GET /rest/products/search?q={index} HTTP/1.1\r\n"}
                    for index in range(5)]
        self.assertEqual({"http://juice-shop:3000/rest/products/search"},
                         requested_resources(messages, "http://juice-shop:3000"))

    def test_resource_coverage_uses_only_supplied_fresh_requests(self):
        messages = [
            {"requestHeader": "GET http://juice-shop:3000/ HTTP/1.1\r\n"},
            {"requestHeader": "GET /main.js HTTP/1.1\r\n"},
            {"requestHeader": "GET /rest/products/search HTTP/1.1\r\n"},
            {"requestHeader": ""},
        ]
        self.assertEqual({"http://juice-shop:3000/", "http://juice-shop:3000/main.js",
                          "http://juice-shop:3000/rest/products/search"},
                         requested_resources(messages, "http://juice-shop:3000"))


class GuidedHarnessTests(unittest.TestCase):
    def setUp(self):
        self.output = contextlib.redirect_stdout(io.StringIO())
        self.output.__enter__()
        self.addCleanup(self.output.__exit__, None, None, None)

    def suite(self, authenticated=False):
        args = SimpleNamespace(
            target="http://juice-shop:3000", mcp_url="http://127.0.0.1:1/mcp", mcp_api_key="",
            zap_url="http://127.0.0.1:2", zap_api_key="", zap_host_header="zap",
            phases="validation,lifecycle,completion", timeout=0.01, poll_interval=0.001,
            evidence="unused-offline-evidence.json", auth_profile="browser" if authenticated else None,
            auth_verification_url="http://juice-shop:3000/rest/user/whoami" if authenticated else None,
            auth_expected_user="test@example.test" if authenticated else None)
        suite = Suite(args)
        suite.mcp, suite.zap, suite.save = Mock(), Mock(), Mock()
        suite.auth_session_id = "auth1" if authenticated else None
        return suite

    @staticmethod
    def status(operation="op1", job="job1", state="RUNNING", native="11", strategy="client", percent=0):
        text = (f"Operation ID: {operation}\nExecution Mode: queue\nStrategy: {strategy}\n"
                f"Authenticated Session: auth1\nJob ID: {job}\nStatus: {state}\nProgress: {percent}%\n")
        if native is not None:
            text += f"ZAP Scan ID: {native}\n"
        return (text + "successful completion is unconfirmed.\n"
                "Review the status/error above before trusting this scan as evidence.\n")

    def lifecycle_suite(self, second_native="12", pending_state="QUEUED", native_after_first_stop="0",
                        pending_claimed=False):
        suite = self.suite()
        s = self.status
        suite.mcp.call.side_effect = [
            s(state="QUEUED", native=None), s(),
            s(operation="replay") + "Admission: existing job returned for idempotent retry\n", s(operation="replay"),
            RuntimeError("Tool zap_crawl_start: idempotency conflict"),
            s("op2", "job2", "QUEUED", None), s("op2", "job2", native=second_native),
            s(), s("op2", "job2", native=second_native),
            s("op3", "job3", "QUEUED", None),
            s("op3", "job3", pending_state, None if pending_state == "QUEUED" else "13")
            + ("Claim Owner: preparing-worker\n" if pending_claimed else ""),
            "stop requested", s("op3", "job3", "CANCELLED", None),
            s(), s("op2", "job2", native=second_native),
            "stop requested", s(state="CANCELLED"),
            s("op2", "job2", native=second_native),
            "stop requested", s("op2", "job2", "CANCELLED", second_native)]
        suite.zap.view.side_effect = [{"status": value} for value in
                                     ["0", "0", "0", "0", "100", native_after_first_stop, "100"]]
        return suite

    def test_full_lifecycle_preserves_pending_and_running_isolation_with_replay_alias(self):
        suite = self.lifecycle_suite()
        suite.lifecycle()
        self.assertEqual({}, suite.guided_ids)
        names = [item.args[0] for item in suite.mcp.call.call_args_list]
        self.assertEqual({"zap_crawl_start", "zap_crawl_status", "zap_crawl_stop"}, set(names))
        starts = [item.args[1] for item in suite.mcp.call.call_args_list if item.args[0] == "zap_crawl_start"]
        self.assertEqual(starts[0], starts[1])
        self.assertEqual(starts[0]["idempotencyKey"], starts[2]["idempotencyKey"])
        self.assertEqual("http", starts[2]["strategy"])
        self.assertEqual(3, len({item["idempotencyKey"] for item in starts}))
        self.assertEqual(3, names.count("zap_crawl_stop"))
        self.assertTrue(all(item.args == ("clientSpider", "status") for item in suite.zap.view.call_args_list))
        self.assertEqual({"11", "12"}, {item.kwargs["scanId"] for item in suite.zap.view.call_args_list})

    def test_full_run_checks_catalog_validation_lifecycle_completion_and_saved_result(self):
        suite = self.lifecycle_suite()
        completion, messages = self.completion_suite()
        properties = {name: {"type": "string"} for name in
                      ["targetUrl", "strategy", "idempotencyKey", "authSessionId"]}
        tools = {"zap_crawl_start": {"inputSchema": {"required": ["targetUrl"], "properties": properties}}}
        tools.update({name: {"inputSchema": {"required": ["operationId"]}}
                      for name in ["zap_crawl_status", "zap_crawl_stop"]})
        tools.update({name: {"inputSchema": {}} for name in ["zap_passive_scan_status", "zap_passive_scan_wait"]})
        validation = [RuntimeError(f"Tool {tool}: operationId is invalid")
                      for tool in ["zap_crawl_status", "zap_crawl_status", "zap_crawl_stop", "zap_crawl_stop"]]
        validation += [RuntimeError("Tool zap_crawl_start: targetUrl is blank"),
                       RuntimeError("Tool zap_crawl_start: strategy is invalid"),
                       RuntimeError("Tool zap_crawl_start: Unknown auth session ID")]
        suite.mcp.call.side_effect = validation + list(suite.mcp.call.side_effect) + list(completion.mcp.call.side_effect)
        suite.mcp.initialize.return_value = {"protocolVersion": "2025-03-26"}
        suite.mcp.tools.return_value = tools
        suite.zap.view.side_effect = [{"version": "2.17.0"}, {"installedAddons": []}] + list(
            suite.zap.view.side_effect) + [{"urls": []}]
        suite.zap.messages.side_effect = [[], messages]
        for name in ["setup", "validation", "lifecycle", "completion"]:
            setattr(suite, name, Mock(wraps=getattr(suite, name)))
        with tempfile.TemporaryDirectory() as directory:
            suite.args.evidence = str(Path(directory) / "evidence.json")
            suite.save = lambda: Suite.save(suite)
            self.assertEqual(0, suite.run())
            saved = json.loads(Path(suite.args.evidence).read_text())
        self.assertTrue(saved["passed"])
        self.assertEqual([], saved["cleanupErrors"])
        self.assertEqual(["validation", "lifecycle", "completion"], saved["phases"])
        for name in ["setup", "validation", "lifecycle", "completion"]:
            getattr(suite, name).assert_called_once()
        self.assertEqual({"zap_crawl_start", "zap_crawl_status", "zap_crawl_stop", "zap_passive_scan_wait"},
                         {item.args[0] for item in suite.mcp.call.call_args_list})
        self.assertTrue(all(item.args[1] in {"version", "installedAddons", "status", "urls"}
                            for item in suite.zap.view.call_args_list))
        bad_catalog = self.suite()
        bad_catalog.mcp.tools.return_value = dict(tools, zap_client_spider_start={"inputSchema": {}})
        with self.assertRaisesRegex(AssertionError, "omits raw Client Spider"):
            bad_catalog.setup()

    def test_lifecycle_rejects_shared_native_id_wrong_pending_state_and_displaced_second_crawl(self):
        for arguments, reason in [({"second_native": "11"}, "distinct jobs and native IDs"),
                                  ({"pending_state": "RUNNING"}, "third job waits"),
                                  ({"native_after_first_stop": "100"}, "native crawl remains in progress"),
                                  ({"pending_claimed": True}, "third job waits")]:
            with self.subTest(arguments=arguments):
                with self.assertRaisesRegex(AssertionError, reason):
                    self.lifecycle_suite(**arguments).lifecycle()

    def test_first_crawl_must_be_running_before_any_replay(self):
        suite = self.suite()
        suite.mcp.call.side_effect = [self.status(state="QUEUED", native=None),
                                      self.status(state="SUCCEEDED", percent=100)]
        with self.assertRaisesRegex(AssertionError, "Expected RUNNING"):
            suite.lifecycle()
        self.assertEqual(1, sum(item.args[0] == "zap_crawl_start" for item in suite.mcp.call.call_args_list))

    def test_natural_completion_rejects_uncertain_failed_cancelled_and_queued_progress100(self):
        for state in ["START_OUTCOME_UNKNOWN", "FAILED", "CANCELLED", "QUEUED"]:
            with self.subTest(state=state):
                suite = self.suite()
                suite.guided_ids["op1"] = "job1"
                suite.mcp.call.return_value = self.status(state=state, percent=100)
                with patch("client_spider_juice_shop.time.monotonic", side_effect=[0, 1]):
                    with self.assertRaisesRegex(AssertionError, "Timed out" if state == "QUEUED" else "Expected SUCCEEDED"):
                        suite.wait_for_state("op1", "SUCCEEDED")
                self.assertIn("op1", suite.guided_ids)

    def test_stop_requires_cancelled_not_merely_succeeded_and_checks_coverage_caveat(self):
        for response, reason in [(self.status(state="SUCCEEDED", percent=100), "Expected CANCELLED"),
                                 (self.status(state="CANCELLED").replace("before trusting this scan as evidence", ""),
                                  "does not establish successful coverage")]:
            suite = self.suite()
            suite.guided_ids["op1"] = "job1"
            suite.mcp.call.side_effect = ["stop requested", response]
            with self.assertRaisesRegex(AssertionError, reason):
                suite.stop_guided("op1", "11")
            self.assertIn("op1", suite.guided_ids)

    def test_failed_or_missing_handle_start_never_retries_and_cleans_only_known_handles(self):
        for outcome in [RuntimeError("connection lost"), "Job ID: unknown\nStatus: STARTING"]:
            with self.subTest(outcome=str(outcome)):
                suite = self.suite()
                suite.guided_ids["owned"] = "owned-job"
                suite.mcp.call.side_effect = [outcome, "stop requested",
                                             self.status("owned", "owned-job", "CANCELLED", None)]
                with self.assertRaises((RuntimeError, AssertionError)):
                    suite.guided_start()
                suite.cleanup()
                starts = [item for item in suite.mcp.call.call_args_list if item.args[0] == "zap_crawl_start"]
                stops = [item.args[1]["operationId"] for item in suite.mcp.call.call_args_list
                         if item.args[0] == "zap_crawl_stop"]
                self.assertEqual(1, len(starts))
                self.assertEqual(["owned"], stops)
                self.assertTrue(suite.evidence["unconfirmedStartRequests"])

    def test_unknown_start_with_handle_is_retained_and_not_replayed(self):
        suite = self.suite()
        unknown = self.status(state="START_OUTCOME_UNKNOWN", native=None, percent=100)
        suite.mcp.call.side_effect = [unknown, "cancellation pending", unknown]
        with self.assertRaises(AssertionError):
            suite.guided_start()
        suite.cleanup()
        self.assertEqual(1, sum(item.args[0] == "zap_crawl_start" for item in suite.mcp.call.call_args_list))
        self.assertIn("op1", suite.guided_ids)
        self.assertEqual(1, len(suite.evidence["cleanupErrors"]))

    def test_unexpected_accepted_conflict_is_tracked_and_cleaned_by_guided_handle(self):
        suite = self.suite()
        suite.mcp.call.side_effect = [self.status("new-op", "new-job", native="22", strategy="http"),
                                     "stop requested", self.status("new-op", "new-job", "CANCELLED", "22", "http")]
        suite.zap.view.return_value = {"status": "100"}
        with self.assertRaises(AssertionError):
            suite.rejected("conflict", "zap_crawl_start", {}, "idempotency")
        self.assertIn("new-op", suite.guided_ids)
        suite.cleanup()
        suite.zap.view.assert_called_once_with("spider", "status", scanId="22")
        self.assertEqual({}, suite.guided_ids)

    def test_transport_error_is_not_accepted_as_input_validation(self):
        suite = self.suite()
        suite.mcp.call.side_effect = RuntimeError("HTTP session operationId connection failed")
        with self.assertRaises(AssertionError):
            suite.rejected("validation", "zap_crawl_status", {}, "operationId")

    @staticmethod
    def message(identifier, path, response="200 OK", body="", headers=""):
        return {"id": identifier, "requestHeader": f"GET {path} HTTP/1.1\r\n{headers}",
                "responseHeader": f"HTTP/1.1 {response}\r\n", "responseBody": body}

    def completion_suite(self, authenticated=False):
        suite = self.suite(authenticated)
        messages = [self.message(index, path) for index, path in enumerate(
            ["/", "/main.js", "/assets/logo.svg", "/rest/products/search?q=fruit", "/rest/categories"], 10)]
        if authenticated:
            messages.append(self.message(30, "/rest/user/whoami", body="test@example.test body-secret",
                                         headers="Authorization: Bearer auth-secret\r\nCookie: token=cookie-secret\r\n"))
        suite.mcp.call.side_effect = [self.status(state="QUEUED", native=None),
                                     self.status(state="SUCCEEDED", percent=100), "Completed: yes"]
        suite.zap.messages.side_effect = [[], messages]
        suite.zap.view.return_value = {"urls": ["http://juice-shop:3000/old-resource"]}
        return suite, messages

    def test_completion_uses_fresh_successful_api_and_resource_evidence(self):
        suite, _ = self.completion_suite()
        suite.completion()
        self.assertEqual({}, suite.guided_ids)
        self.assertEqual(5, len(suite.evidence["coverage"]["freshResourceUrls"]))
        self.assertNotIn("zap_crawl_stop", [item.args[0] for item in suite.mcp.call.call_args_list])

    def test_stale_failed_api_and_insufficient_resource_evidence_fail(self):
        for bad in ["stale", "api-failure", "resources"]:
            with self.subTest(bad=bad):
                suite, messages = self.completion_suite()
                if bad == "stale":
                    suite.zap.messages.side_effect = [messages, messages]
                elif bad == "api-failure":
                    messages[3]["responseHeader"] = "HTTP/1.1 500 Error\r\n"
                else:
                    messages.pop()
                with self.assertRaisesRegex(AssertionError, "fresh successful Juice Shop" if bad != "resources"
                                            else "more than initial HTML"):
                    suite.completion()

    def test_authentication_is_bound_and_evidence_omits_credential_and_body_values(self):
        suite, _ = self.completion_suite(True)
        suite.completion()
        self.assertEqual("auth1", suite.mcp.call.call_args_list[0].args[1]["authSessionId"])
        evidence = json.dumps(suite.evidence)
        for secret in ["auth-secret", "cookie-secret", "body-secret"]:
            self.assertNotIn(secret, evidence)
        verification = suite.evidence["coverage"]["authenticatedVerificationMessages"][0]
        self.assertTrue(verification["hasAuthorizationHeader"])
        self.assertTrue(verification["hasTokenCookie"])
        self.assertTrue(verification["containsExpectedUser"])

    def test_wrong_user_missing_credentials_and_stale_verification_fail(self):
        for bad in ["wrong-user", "no-credentials", "stale-verification"]:
            with self.subTest(bad=bad):
                suite, messages = self.completion_suite(True)
                verification = messages[-1]
                if bad == "wrong-user":
                    verification["responseBody"] = "different@example.test"
                elif bad == "no-credentials":
                    verification["requestHeader"] = "GET /rest/user/whoami HTTP/1.1\r\n"
                else:
                    suite.zap.messages.side_effect = [[verification], messages]
                with self.assertRaisesRegex(AssertionError, "fresh authenticated verification requests"):
                    suite.completion()

    def test_auth_preparation_validates_browser_profile_before_crawl_baseline(self):
        suite = self.suite(True)
        suite.auth_session_id = None
        suite.mcp.call.side_effect = ["Session ID: auth1\nAuth Kind: browser", "Valid: true"]
        suite.prepare_authentication()
        self.assertEqual("auth1", suite.auth_session_id)
        self.assertEqual("zap_auth_session_validate", suite.mcp.call.call_args_list[-1].args[0])
        suite.mcp.call.side_effect = ["Session ID: auth2\nAuth Kind: form"]
        with self.assertRaises(AssertionError):
            suite.prepare_authentication()

    def test_run_saves_failure_and_cancels_only_a_known_operation(self):
        suite = self.suite()
        with tempfile.TemporaryDirectory() as directory:
            suite.args.evidence = str(Path(directory) / "evidence.json")
            suite.save = lambda: Suite.save(suite)
            suite.setup = Mock()
            suite.args.phases = "lifecycle"
            suite.lifecycle = Mock(side_effect=AssertionError("phase failure"))
            suite.guided_ids = {"owned": "owned-job"}
            suite.mcp.call.side_effect = ["stop requested", self.status("owned", "owned-job", "CANCELLED", None)]
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(1, suite.run())
            saved = json.loads(Path(suite.args.evidence).read_text())
            self.assertFalse(saved["passed"])
            self.assertEqual([], saved["cleanupErrors"])
            self.assertEqual("owned", suite.mcp.call.call_args_list[0].args[1]["operationId"])


class ClientAndCliTests(unittest.TestCase):
    def test_rpc_keeps_api_key_and_session_headers_out_of_recorded_evidence(self):
        events = []
        client = McpClient("http://127.0.0.1:1/mcp", "api-key-secret", record=events.append)
        client.session = "session-secret"
        with patch("client_spider_juice_shop.urllib.request.urlopen",
                   return_value=Response('{"id":1,"result":{"ok":true}}', "application/json")) as request:
            self.assertEqual({"ok": True}, client.rpc("tools/list"))
        self.assertIn("api-key-secret", request.call_args.args[0].headers.values())
        self.assertNotIn("api-key-secret", json.dumps(events))
        self.assertNotIn("session-secret", json.dumps(events))

    def test_tool_and_json_rpc_errors_remain_failures(self):
        client = McpClient("http://127.0.0.1:1/mcp")
        with patch("client_spider_juice_shop.urllib.request.urlopen",
                   return_value=Response('{"id":1,"error":{"code":-32602,"message":"Unknown tool"}}',
                                         "application/json")):
            with self.assertRaisesRegex(RuntimeError, "JSON-RPC error"):
                client.rpc("tools/call")
        client.rpc = Mock(return_value={"isError": True, "content": [{"type": "text", "text": "invalid input"}]})
        with self.assertRaisesRegex(RuntimeError, "Tool zap_crawl_start: invalid input"):
            client.call("zap_crawl_start")

    def test_cli_defaults_to_guided_full_phases(self):
        with patch("client_spider_juice_shop.Suite") as suite:
            suite.return_value.run.return_value = 0
            self.assertEqual(0, main(["--mcp-url", "http://127.0.0.1:1/mcp", "--zap-url", "http://127.0.0.1:2"]))
            args = suite.call_args.args[0]
            self.assertEqual("validation,lifecycle,completion", args.phases)
            self.assertFalse(hasattr(args, "mode"))

    def test_removed_routes_and_invalid_or_nonlocal_cli_inputs_are_rejected(self):
        cases = [["--mode", "expert"], ["--expected-execution-mode", "direct"], ["--phases", "direct"],
                 ["--phases", "queue"], ["--timeout", "0"], ["--poll-interval", "0"],
                 ["--mcp-url", "https://remote.example/mcp"], ["--zap-url", "https://remote.example"],
                 ["--auth-profile", "only-one-option"],
                 ["--auth-profile", "browser", "--auth-verification-url", "https://foreign.example/whoami",
                  "--auth-expected-user", "test@example.test"]]
        for arguments in cases:
            with self.subTest(arguments=arguments), patch("client_spider_juice_shop.Suite") as suite:
                with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
                    main(["--mcp-url", "http://127.0.0.1:1/mcp", "--zap-url", "http://127.0.0.1:2"] + arguments)
                self.assertEqual(2, error.exception.code)
                suite.assert_not_called()


if __name__ == "__main__":
    unittest.main()
