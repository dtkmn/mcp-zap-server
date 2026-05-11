import importlib.util
from pathlib import Path
import sys
import unittest


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


if __name__ == "__main__":
    unittest.main()
