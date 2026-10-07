import importlib.util
import contextlib
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch
import urllib.error


MODULE_PATH = Path(__file__).resolve().parents[2] / ".github" / "actions" / "zap-webhook-callback" / "send_zap_webhook.py"
SPEC = importlib.util.spec_from_file_location("send_zap_webhook", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class SendZapWebhookTest(unittest.TestCase):
    def test_detect_provider_prefers_explicit_value(self):
        provider = MODULE.detect_provider("gitlab", {"GITHUB_ACTIONS": "true"})
        self.assertEqual(provider, "gitlab")

    def test_build_payload_includes_gate_and_ci_context(self):
        metadata = {"target_url": "https://example.com", "gate_passed": True, "new_findings": 0}
        payload = MODULE.build_payload(
            "zap_security_gate.completed",
            "github",
            {
                "GITHUB_REPOSITORY": "example/repo",
                "GITHUB_SERVER_URL": "https://github.com",
                "GITHUB_RUN_ID": "42",
                "GITHUB_REF_NAME": "main",
            },
            metadata,
            {"metadataPath": Path("/tmp/meta.json")},
        )

        self.assertEqual(payload["status"], "passed")
        self.assertEqual(payload["ci"]["runId"], "42")
        self.assertEqual(payload["gate"]["target_url"], "https://example.com")
        self.assertTrue(payload["artifacts"]["metadataPath"]["exists"] is False)

    def test_compute_signature_uses_sha256_prefix(self):
        signature = MODULE.compute_signature("secret", b'{"hello":"world"}')
        self.assertTrue(signature.startswith("sha256="))
        self.assertGreater(len(signature), len("sha256="))

    def test_deliver_with_retries_retries_then_succeeds(self):
        calls = []
        sleeps = []

        def fake_sender(*_args, **_kwargs):
            calls.append("call")
            if len(calls) == 1:
                return 503, "busy", {}, None
            return 204, "", {}, None

        delivered, status_code, attempts = MODULE.deliver_with_retries(
            "https://example.com/webhook",
            b"{}",
            {},
            10.0,
            MODULE.RetryPolicy(3, 1.0, 10.0, 2.0),
            sleep_fn=sleeps.append,
            sender=fake_sender,
        )

        self.assertTrue(delivered)
        self.assertEqual(status_code, 204)
        self.assertEqual(len(attempts), 2)
        self.assertEqual(sleeps, [1.0])

    def test_parse_retry_after_supports_seconds(self):
        retry_after = MODULE.parse_retry_after({"Retry-After": "12"})
        self.assertEqual(retry_after, 12.0)

    def test_failed_delivery_outputs_omit_destination_credentials(self):
        for destination, origin in [
            ("https://user:USER_SECRET@notify.example/hooks/PATH_SECRET?token=QUERY_SECRET#FRAGMENT_SECRET",
             "https://notify.example"),
            ("https://[::1]:8443/hooks/%50ATH_SECRET?token=QUERY_SECRET", "https://[::1]:8443"),
            ("https://notify.example:INVALID_PORT/hooks/PATH_SECRET", "[redacted destination]"),
        ]:
            with self.subTest(destination=destination), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                metadata = root / "metadata.json"
                metadata.write_text('{"gate_passed": true}', encoding="utf-8")
                record = root / "delivery.json"
                summary = root / "summary.md"
                output = root / "outputs.txt"
                stderr = io.StringIO()
                argv = ["send_zap_webhook", "--webhook-url", destination,
                        "--metadata-path", str(metadata), "--output-path", str(record),
                        "--max-attempts", "1"]
                with patch.object(sys, "argv", argv), patch.dict(os.environ, {
                    "GITHUB_STEP_SUMMARY": str(summary), "GITHUB_OUTPUT": str(output),
                }), patch.object(MODULE.urllib.request, "urlopen",
                               side_effect=urllib.error.URLError(f"Failed to reach {destination}")), \
                        contextlib.redirect_stderr(stderr):
                    self.assertEqual(MODULE.main(), 1)

                delivery = json.loads(record.read_text(encoding="utf-8"))
                self.assertEqual(delivery["webhook_target"], origin)
                self.assertFalse(delivery["delivered"])
                diagnostics = record.read_text() + summary.read_text() + output.read_text() + stderr.getvalue()
                for secret in ["USER_SECRET", "PATH_SECRET", "%50ATH_SECRET", "QUERY_SECRET", "FRAGMENT_SECRET"]:
                    self.assertNotIn(secret, diagnostics)

    def test_successful_delivery_preserves_destination_and_authentication(self):
        destination = "https://notify.example/hooks/PATH_SECRET?token=QUERY_SECRET"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            metadata = root / "metadata.json"
            metadata.write_text('{"gate_passed": true}', encoding="utf-8")
            record = root / "delivery.json"
            response = MagicMock()
            response.__enter__.return_value = response
            response.getcode.return_value = 204
            response.read.return_value = b""
            response.headers = {}
            with patch.object(sys, "argv", ["send_zap_webhook", "--webhook-url", destination,
                                          "--metadata-path", str(metadata), "--output-path", str(record),
                                          "--bearer-token", "HEADER_SECRET", "--secret", "SIGNING_SECRET"]), \
                    patch.dict(os.environ, {"GITHUB_STEP_SUMMARY": "", "GITHUB_OUTPUT": ""}), \
                    patch.object(MODULE.urllib.request, "urlopen", return_value=response) as sender:
                self.assertEqual(MODULE.main(), 0)
            request = sender.call_args.args[0]
            self.assertEqual(request.full_url, destination)
            self.assertEqual(request.get_header("Authorization"), "Bearer HEADER_SECRET")
            self.assertEqual(request.get_header("X-mcp-zap-signature-sha256"),
                             MODULE.compute_signature("SIGNING_SECRET", request.data))
            delivery = json.loads(record.read_text())
            self.assertTrue(delivery["delivered"])
            self.assertEqual(delivery["webhook_target"], "https://notify.example")


if __name__ == "__main__":
    unittest.main()
