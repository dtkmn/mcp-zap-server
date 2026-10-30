import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
DOCKER = shutil.which("docker")


@unittest.skipUnless(DOCKER, "Docker Compose is required for configuration rendering")
class GuidedSurfaceDeploymentTest(unittest.TestCase):
    def test_bundled_composes_preserve_private_launch_storage_without_surface_selector(self):
        # Configuration rendering only: no containers, images or target traffic.
        for name in (
            "docker-compose.yml",
            ".github/actions/zap-security-gate/docker-compose.ci.yml",
            "examples/gitlab/docker-compose.gitlab-ci.yml",
            "examples/aws-ec2/compose.yaml",
        ):
            with self.subTest(compose=name), tempfile.TemporaryDirectory() as temporary:
                settings = Path(temporary) / "settings.env"
                settings.write_text(
                    "MCP_API_KEY=fixture-mcp-key\nZAP_API_KEY=fixture-zap-key\n"
                    "ZAP_IMAGE=example.invalid/zap:fixture\n"
                    "MCP_ZAP_IMAGE=example.invalid/server:fixture\n"
                    "MCP_SERVER_IMAGE=example.invalid/server:fixture\n"
                    "MCP_SERVER_TOOLS_SURFACE=expert\n"
                    "ZAP_SCAN_RECONCILE_OPERATOR_CLIENT_IDS=obsolete-operator\n"
                    f"LOCAL_ZAP_WORKSPACE_FOLDER={temporary}/work\n",
                    encoding="utf-8",
                )
                environment = {key: value for key, value in os.environ.items()
                               if not key.startswith(("MCP_", "ZAP_", "JWT_", "LOCAL_ZAP_"))}
                result = subprocess.run(
                    [DOCKER, "compose", "--env-file", str(settings), "-f", str(ROOT / name),
                     "config", "--format", "json"],
                    cwd=ROOT, env=environment, text=True, capture_output=True, timeout=30,
                )
                self.assertEqual(result.returncode, 0, result.stderr)
                config = json.loads(result.stdout)
                server = config["services"]["mcp-server"]
                self.assertNotIn("MCP_SERVER_TOOLS_SURFACE", server["environment"])
                self.assertNotIn("ZAP_SCAN_RECONCILE_OPERATOR_CLIENT_IDS", server["environment"])
                self.assertEqual(server["environment"]["ZAP_SCAN_LAUNCH_JOURNAL_DIRECTORY"],
                                 "/var/lib/mcp-zap/scan-launch-safety/receipts")
                private_mounts = [volume for volume in server["volumes"]
                                  if volume["target"] == "/var/lib/mcp-zap/scan-launch-safety"]
                self.assertEqual(len(private_mounts), 1)
                private = private_mounts[0]
                self.assertEqual(private["type"], "volume")
                self.assertIn(private["source"], config["volumes"])
                for service_name, service in config["services"].items():
                    if service_name != "mcp-server":
                        self.assertNotIn(private["source"],
                                         [volume.get("source") for volume in service.get("volumes", [])])

    def test_registry_packages_preserve_private_launch_storage_without_surface_selector(self):
        manifest = json.loads((ROOT / ".mcp/server.json").read_text())
        for package in manifest["packages"]:
            environment = {entry["name"]: entry for entry in package.get("environmentVariables", [])}
            self.assertNotIn("MCP_SERVER_TOOLS_SURFACE", environment)
            self.assertEqual(environment["ZAP_SCAN_LAUNCH_JOURNAL_DIRECTORY"]["value"],
                             "/var/lib/mcp-zap/scan-launch-safety/receipts")
            self.assertIn("mcp-launch-safety:/var/lib/mcp-zap/scan-launch-safety",
                          [entry.get("value") for entry in package["runtimeArguments"]
                           if entry.get("name") == "-v"])


if __name__ == "__main__":
    unittest.main()
