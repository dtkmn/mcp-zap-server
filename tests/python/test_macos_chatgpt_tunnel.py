"""Exercise tunnel initialization with private temporary state and a fake client."""

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "packaging/macos/chatgpt-tunnel.sh"
SUFFIX = "0123456789abcdefghijklmnopqrstuv"
VALID_ID = "tunnel_az09_" + SUFFIX
KEY = "fixture-private-mcp-key"
RUNTIME_KEY = "fixture-private-runtime-key"
OVERRIDES = (
    "TUNNEL_CLIENT_CONFIG", "TUNNEL_CLIENT_PROFILE", "TUNNEL_CLIENT_PROFILE_FILE",
    "CONTROL_PLANE_TUNNEL_ID", "MCP_COMMAND",
)

FAKE_CLIENT = '''#!PYTHON
import json, os, sys
from pathlib import Path
Path(os.environ["TUNNEL_TEST_CALL"]).write_text(json.dumps({
    "args": sys.argv[1:],
    "runtime_key_matches": os.environ.get("MCP_ZAP_TUNNEL_API_KEY") == os.environ["CONTROL_PLANE_API_KEY"],
    "mcp_key_matches": os.environ.get("MCP_API_KEY") == "fixture-private-mcp-key",
    "overrides": [key for key in (
        "TUNNEL_CLIENT_CONFIG", "TUNNEL_CLIENT_PROFILE", "TUNNEL_CLIENT_PROFILE_FILE",
        "CONTROL_PLANE_TUNNEL_ID", "MCP_COMMAND",
    ) if key in os.environ],
}))
'''


class MacosChatgptTunnelTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="mcp-tunnel-fixture-")
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name).resolve()
        self.data = self.directory / "data"
        self.data.mkdir(mode=0o700)
        self.tunnel = self.data / "tunnel"
        self.profile = self.tunnel / "chatgpt-local.yaml"
        self.call = self.directory / "client-call.json"
        client = self.directory / "fake-tunnel-client"
        client.write_text(FAKE_CLIENT.replace("#!PYTHON", "#!" + sys.executable, 1))
        client.chmod(0o700)
        self.environment = {
            "PATH": "/usr/bin:/bin", "LC_ALL": "C",
            "MCP_ZAP_DATA_DIR": str(self.data),
            "MCP_ZAP_SERVER_URL": "http://127.0.0.1:7456/mcp",
            "MCP_API_KEY": KEY,
            "OPENAI_API_KEY": "fixture-unused-openai-key",
            "CONTROL_PLANE_API_KEY": RUNTIME_KEY,
            "MCP_ZAP_TUNNEL_CLIENT": str(client),
            "TUNNEL_TEST_CALL": str(self.call),
            **{key: "unrelated-caller-override" for key in OVERRIDES},
        }

    def run_init(self, tunnel_id, *extra):
        result = subprocess.run(
            ["/bin/bash", str(SCRIPT), "init", tunnel_id, *extra],
            cwd=self.directory, env=self.environment, capture_output=True, text=True, timeout=5,
        )
        for secret in (KEY, RUNTIME_KEY, self.environment["OPENAI_API_KEY"]):
            self.assertNotIn(secret, result.stdout + result.stderr)
        return result

    def assert_forwarded(self, tunnel_id, *extra):
        result = self.run_init(tunnel_id, *extra)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(result.stderr, "")
        call = json.loads(self.call.read_text())
        expected = [
            "init", "--sample", "sample_mcp_remote_no_auth",
            "--profile", "chatgpt-local", "--profile-dir", str(self.tunnel),
            "--tunnel-id", tunnel_id, "--mcp-server-url", "http://127.0.0.1:7456/mcp",
            "--control-plane-api-key-ref", "env:MCP_ZAP_TUNNEL_API_KEY",
            "--health-listen-addr", "127.0.0.1:0",
        ]
        if extra:
            expected.append("--force")
        self.assertEqual(call["args"], expected)
        self.assertTrue(call["runtime_key_matches"])
        self.assertTrue(call["mcp_key_matches"])
        self.assertEqual(call["overrides"], [])
        self.assertEqual(self.tunnel.stat().st_mode & 0o777, 0o700)
        self.call.unlink()

    def test_documented_forms_pass_complete_id_without_normalization(self):
        for tunnel_id in (
            "tunnel_0123456789abcdef0123456789abcdef",
            "tunnel_" + SUFFIX,
            VALID_ID,
            "tunnel_0000_" + "z" * 32,
        ):
            with self.subTest(tunnel_id=tunnel_id):
                self.assert_forwarded(tunnel_id)

    def test_malformed_ids_fail_before_storage_or_client_changes(self):
        for tunnel_id in (
            "", "tunnel_", "tunnel_" + SUFFIX[:-1], "tunnel_" + SUFFIX + "0",
            "tunnel_abc_" + SUFFIX, "tunnel_abcde_" + SUFFIX,
            "tunnel_abcd_" + SUFFIX[:-1], "tunnel_abcd_" + SUFFIX + "0",
            "tunnel__" + SUFFIX, "tunnel_abcd__" + SUFFIX,
            "TUNNEL_" + SUFFIX, "tunnel_ABC0_" + SUFFIX,
            "tunnel_" + SUFFIX.upper(), "tunnel_" + "é" + SUFFIX[1:],
            "tunnel_ábcd_" + SUFFIX, "tunnel_" + "０" + SUFFIX[1:],
            VALID_ID + "\n", " " + VALID_ID, VALID_ID + " ",
            VALID_ID + "/../profile", VALID_ID + ";touch unexpected", "--replace",
        ):
            with self.subTest(tunnel_id=tunnel_id):
                result = self.run_init(tunnel_id)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Use the complete tunnel ID", result.stderr)
                self.assertEqual(list(self.data.iterdir()), [])
                self.assertFalse(self.call.exists())

    def test_ascii_validation_is_unchanged_in_utf8_locale(self):
        locales = subprocess.run(
            ["locale", "-a"], env={"PATH": "/usr/bin:/bin", "LC_ALL": "C"},
            capture_output=True, text=True, check=True, timeout=5,
        ).stdout.splitlines()
        selected = next((name for name in ("en_US.UTF-8", "en_US.utf8", "C.UTF-8", "C.utf8")
                         if name in locales), None)
        if selected is None:
            self.skipTest("No supported UTF-8 locale is installed")
        self.environment["LC_ALL"] = selected
        for tunnel_id in ("tunnel_" + "é" + SUFFIX[1:], "tunnel_ábcd_" + SUFFIX,
                          "tunnel_" + "０" + SUFFIX[1:], "tunnel_İbcd_" + SUFFIX):
            with self.subTest(tunnel_id=tunnel_id, locale=selected):
                result = self.run_init(tunnel_id)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Use the complete tunnel ID", result.stderr)
                self.assertEqual(list(self.data.iterdir()), [])
                self.assertFalse(self.call.exists())
        self.assert_forwarded(VALID_ID)

    def test_existing_profile_requires_explicit_replace(self):
        self.tunnel.mkdir(mode=0o700)
        self.profile.write_text("existing private profile\n")
        result = self.run_init(VALID_ID)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Existing tunnel profile preserved", result.stderr)
        self.assertFalse(self.call.exists())
        self.assert_forwarded(VALID_ID, "--replace")
        self.assertEqual(self.profile.read_text(), "existing private profile\n")

    def test_invalid_replace_does_not_touch_profile_or_directory_mode(self):
        self.tunnel.mkdir(mode=0o755)
        self.profile.write_text("existing private profile\n")
        self.profile.chmod(0o600)
        before = (self.profile.read_bytes(), self.profile.stat().st_mtime_ns)
        result = self.run_init(VALID_ID[:-1], "--replace")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Use the complete tunnel ID", result.stderr)
        self.assertEqual((self.profile.read_bytes(), self.profile.stat().st_mtime_ns), before)
        self.assertEqual(self.profile.stat().st_mode & 0o777, 0o600)
        self.assertEqual(self.tunnel.stat().st_mode & 0o777, 0o755)
        self.assertFalse(self.call.exists())

    def test_linked_tunnel_storage_is_rejected_before_client_call(self):
        outside = self.directory / "unrelated"
        outside.mkdir(mode=0o755)
        self.tunnel.symlink_to(outside, target_is_directory=True)
        result = self.run_init(VALID_ID)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Refusing linked or invalid tunnel storage", result.stderr)
        self.assertEqual(list(outside.iterdir()), [])
        self.assertEqual(outside.stat().st_mode & 0o777, 0o755)
        self.assertFalse(self.call.exists())

    def test_non_loopback_endpoint_and_missing_key_remain_rejected(self):
        for setting, value, error in (
            ("MCP_ZAP_SERVER_URL", "http://example.com:7456/mcp", "127.0.0.1"),
            ("MCP_API_KEY", "", "local MCP API key"),
        ):
            with self.subTest(setting=setting):
                original = self.environment[setting]
                self.environment[setting] = value
                result = self.run_init(VALID_ID)
                self.environment[setting] = original
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(error, result.stderr)
                self.assertEqual(list(self.data.iterdir()), [])
                self.assertFalse(self.call.exists())


if __name__ == "__main__":
    unittest.main()
