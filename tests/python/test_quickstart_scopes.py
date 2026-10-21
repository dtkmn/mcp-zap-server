import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
DOCKER = shutil.which("docker")
NATIVE_ALIASES = (
    "MCP_SERVER_AUTH_APIKEYS_0_CLIENTID",
    "MCP_SERVER_AUTH_APIKEYS_0_KEY",
    "MCP_SERVER_AUTH_APIKEYS_0_SCOPES",
)


class QuickStartScopesTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / "bin").mkdir()
        shutil.copy2(ROOT / "bin/bootstrap-local.sh", self.root / "bin/bootstrap-local.sh")
        shutil.copy2(ROOT / ".env.example", self.root / ".env.example")
        shutil.copy2(ROOT / "docker-compose.yml", self.root / "docker-compose.yml")
        self.environment = {
            name: value
            for name, value in os.environ.items()
            if not name.startswith(("MCP_", "ZAP_", "JWT_", "LOCAL_ZAP_", "COMPOSE_"))
            and name not in ("BASH_ENV", "ENV", "CDPATH")
        }

    def bootstrap(self, *arguments):
        result = subprocess.run(
            ["bash", str(self.root / "bin/bootstrap-local.sh"), *arguments],
            cwd=self.root,
            env=self.environment,
            capture_output=True,
            text=True,
            timeout=15,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        return result

    def settings(self):
        return dict(
            line.split("=", 1)
            for line in (self.root / ".env").read_text().splitlines()
            if line and not line.startswith("#") and "=" in line
        )

    def test_fresh_bootstrap_keeps_the_wildcard_default_without_a_scope_override(self):
        self.bootstrap()
        settings = self.settings()
        self.assertNotIn("MCP_CLIENT_SCOPES", settings)
        self.assertNotIn("MCP_CLIENT_CONFIG_FILE", settings)
        for name in NATIVE_ALIASES:
            self.assertNotIn(name, settings)
        self.assertRegex(settings["MCP_API_KEY"], r"^[a-f0-9]{64}$")
        self.assertRegex(settings["ZAP_API_KEY"], r"^[a-f0-9]{64}$")
        self.assertNotEqual(settings["MCP_API_KEY"], settings["ZAP_API_KEY"])
        self.assertEqual(settings["MCP_SECURITY_MODE"], "api-key")
        self.assertEqual(settings["MCP_SECURITY_ENABLED"], "true")
        self.assertEqual(settings["JWT_ENABLED"], "false")
        self.assertEqual((self.root / ".env").stat().st_mode & 0o777, 0o600)
        self.assertTrue((self.root / "zap-workplace/zap-wrk").is_dir())

    def test_existing_legacy_settings_and_keys_are_not_rewritten(self):
        existing = b"# operator settings\nMCP_API_KEY=existing-key\nMCP_CLIENT_ID=custom-client\n"
        path = self.root / ".env"
        path.write_bytes(existing)
        path.chmod(0o640)
        before = path.stat()
        result = self.bootstrap()
        self.assertEqual(path.read_bytes(), existing)
        self.assertEqual(path.stat().st_mtime_ns, before.st_mtime_ns)
        self.assertEqual(path.stat().st_mode & 0o777, 0o640)
        self.assertIn("preserved", result.stdout)
        self.assertFalse((self.root / "zap-workplace").exists())

    def test_existing_explicit_scopes_and_native_multi_client_configuration_are_preserved(self):
        for scopes in ("zap:report:read", "*", ""):
            with self.subTest(scopes=scopes):
                existing = (
                    "MCP_API_KEY=existing-key\n"
                    f"MCP_SERVER_AUTH_APIKEYS_0_SCOPES={scopes}\n"
                    "MCP_SERVER_AUTH_APIKEYS_0_CLIENTID=reader\n"
                    "MCP_SERVER_AUTH_APIKEYS_0_KEY=reader-key\n"
                    "MCP_SERVER_AUTH_APIKEYS_1_CLIENTID=admin\n"
                    "MCP_SERVER_AUTH_APIKEYS_1_KEY=admin-key\n"
                    "MCP_SERVER_AUTH_APIKEYS_1_SCOPES=*\n"
                ).encode()
                (self.root / ".env").write_bytes(existing)
                self.bootstrap()
                self.assertEqual((self.root / ".env").read_bytes(), existing)

    def test_fresh_bootstrap_rerun_is_byte_for_byte_unchanged(self):
        self.bootstrap()
        before = (self.root / ".env").read_bytes()
        self.bootstrap()
        self.assertEqual((self.root / ".env").read_bytes(), before)

    def test_explicit_force_replaces_legacy_keys_without_adding_scope_overrides(self):
        (self.root / ".env").write_text(
            "MCP_API_KEY=legacy-key\nZAP_API_KEY=legacy-zap-key\n"
        )
        self.bootstrap("--force")
        settings = self.settings()
        self.assertRegex(settings["MCP_API_KEY"], r"^[a-f0-9]{64}$")
        self.assertRegex(settings["ZAP_API_KEY"], r"^[a-f0-9]{64}$")
        self.assertNotIn("MCP_CLIENT_SCOPES", settings)
        self.assertNotIn("MCP_CLIENT_CONFIG_FILE", settings)
        self.assertEqual((self.root / ".env").stat().st_mode & 0o777, 0o600)

    @unittest.skipUnless(DOCKER, "Docker Compose is required for configuration rendering")
    def test_direct_example_copy_does_not_add_scope_or_native_client_overrides(self):
        shutil.copy2(self.root / ".env.example", self.root / ".env")
        path = self.root / ".env"
        values = path.read_text()
        values = re.sub(r"^MCP_API_KEY=.*$", "MCP_API_KEY='literal-$-key'", values, flags=re.MULTILINE)
        values = re.sub(r"^MCP_CLIENT_ID=.*$", "MCP_CLIENT_ID=manual-client", values, flags=re.MULTILINE)
        path.write_text(values)
        environment = self.render_compose()
        self.assertEqual(environment["MCP_CLIENT_ID"], "manual-client")
        # Compose config can escape dollars for reuse as Compose input.
        self.assertEqual(environment["MCP_API_KEY"].replace("$$", "$"), "literal-$-key")
        self.assertNotIn("MCP_CLIENT_SCOPES", environment)
        for name in NATIVE_ALIASES:
            self.assertNotIn(name, environment)
        self.assertFalse((self.root / "zap-workplace").exists())

    @unittest.skipUnless(DOCKER, "Docker Compose is required for configuration rendering")
    def test_fresh_settings_forward_identity_and_key_without_scope_overrides(self):
        self.bootstrap()
        settings = self.settings()
        environment = self.render_compose()
        self.assertEqual(environment["MCP_API_KEY"], settings["MCP_API_KEY"])
        self.assertEqual(environment["MCP_CLIENT_ID"], "default-client")
        self.assertEqual(environment["MCP_SERVER_TOOLS_SURFACE"], "guided")
        self.assertNotIn("MCP_CLIENT_SCOPES", environment)
        for name in NATIVE_ALIASES:
            self.assertNotIn(name, environment)

    @unittest.skipUnless(DOCKER, "Docker Compose is required for configuration rendering")
    def test_legacy_override_env_file_retains_native_clients_and_custom_settings(self):
        existing = b"MCP_API_KEY=legacy-key\nZAP_API_KEY=legacy-zap-key\n"
        (self.root / ".env").write_bytes(existing)
        clients = {
            "MCP_SERVER_AUTH_APIKEYS_0_CLIENTID": "reader",
            "MCP_SERVER_AUTH_APIKEYS_0_KEY": "reader-key",
            "MCP_SERVER_AUTH_APIKEYS_0_SCOPES_0": "zap:report:read",
            "MCP_SERVER_AUTH_APIKEYS_0_SCOPES_1": "mcp:tools:list",
            "MCP_SERVER_AUTH_APIKEYS_0_NAME": "Report reader",
            "MCP_SERVER_AUTH_APIKEYS_0_WORKSPACEID": "report-workspace",
            "MCP_SERVER_AUTH_APIKEYS_1_CLIENTID": "legacy-client",
            "MCP_SERVER_AUTH_APIKEYS_1_KEY": "second-key",
            "MCP_SERVER_AUTH_APIKEYS_1_WORKSPACEID": "legacy-workspace",
            "CUSTOM_SETTING": "preserve-me",
        }
        (self.root / "clients.env").write_text("".join(f"{name}={value}\n" for name, value in clients.items()))
        override = self.root / "custom-compose.yml"
        override.write_text("services:\n  mcp-server:\n    env_file:\n      - clients.env\n")
        self.bootstrap()
        environment = self.render_compose(override)
        for name, value in clients.items():
            self.assertEqual(environment[name], value, name)
        self.assertNotIn("MCP_SERVER_AUTH_APIKEYS_1_SCOPES", environment)
        self.assertEqual((self.root / ".env").read_bytes(), existing)

    @unittest.skipUnless(DOCKER, "Docker Compose is required for configuration rendering")
    def test_legacy_override_env_file_retains_an_explicit_custom_json_client_list(self):
        (self.root / ".env").write_text("MCP_API_KEY=unused-key\nZAP_API_KEY=legacy-zap-key\n")
        configuration = json.dumps({"mcp": {"server": {"auth": {"apiKeys": [
            {"clientId": "reader", "key": "reader-key", "scopes": ["zap:report:read"],
             "name": "Report reader", "workspaceId": "report-workspace"},
            {"clientId": "legacy-client", "key": "legacy-key", "workspaceId": "legacy-workspace"},
        ]}}}}, separators=(",", ":"))
        (self.root / "clients.env").write_text(f"SPRING_APPLICATION_JSON='{configuration}'\n")
        override = self.root / "custom-compose.yml"
        override.write_text("services:\n  mcp-server:\n    env_file:\n      - clients.env\n")
        environment = self.render_compose(override)
        self.assertEqual(json.loads(environment["SPRING_APPLICATION_JSON"]), json.loads(configuration))
        for name in NATIVE_ALIASES:
            self.assertNotIn(name, environment)

    @unittest.skipUnless(DOCKER, "Docker Compose is required for configuration rendering")
    def test_existing_key_and_identity_shell_overrides_still_apply(self):
        self.bootstrap()
        self.environment.update({
            "MCP_API_KEY": "shell-key",
            "MCP_CLIENT_ID": "shell-client",
        })
        environment = self.render_compose()
        self.assertEqual(environment["MCP_API_KEY"], "shell-key")
        self.assertEqual(environment["MCP_CLIENT_ID"], "shell-client")
        self.assertNotIn("MCP_CLIENT_SCOPES", environment)
        for name in NATIVE_ALIASES:
            self.assertNotIn(name, environment)

    def render_compose(self, *override_files):
        # Configuration only: never starts containers or contacts a Docker daemon.
        arguments = [DOCKER, "compose", "--project-directory", str(self.root), "--env-file", str(self.root / ".env"),
                     "-f", str(self.root / "docker-compose.yml")]
        for override in override_files:
            arguments.extend(["-f", str(override)])
        result = subprocess.run(
            [*arguments, "config", "--format", "json"],
            cwd=self.root,
            env=self.environment,
            text=True,
            capture_output=True,
            timeout=30,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        return json.loads(result.stdout)["services"]["mcp-server"]["environment"]


if __name__ == "__main__":
    unittest.main()
