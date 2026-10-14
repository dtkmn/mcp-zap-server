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
class ComposeAutomationConfigurationTest(unittest.TestCase):
    def render_environment(self, extra_settings="", compose_path=None, example_path=None):
        # Render only; these tests never start containers or need a Docker daemon.
        environment = {
            name: value
            for name, value in os.environ.items()
            if not name.startswith(("MCP_", "ZAP_", "JWT_", "LOCAL_ZAP_"))
        }
        with tempfile.TemporaryDirectory() as temporary_directory:
            dotenv = Path(temporary_directory) / "compose.env"
            dotenv.write_text(
                (example_path or ROOT / ".env.example").read_text() + "\n" + extra_settings,
                encoding="utf-8",
            )
            result = subprocess.run(
                [
                    DOCKER,
                    "compose",
                    "--env-file",
                    str(dotenv),
                    "-f",
                    str(compose_path or ROOT / "docker-compose.yml"),
                    "config",
                    "--format",
                    "json",
                ],
                cwd=ROOT,
                env=environment,
                text=True,
                capture_output=True,
                timeout=30,
            )
        self.assertEqual(result.returncode, 0, result.stderr)
        return json.loads(result.stdout)["services"]["mcp-server"]["environment"]

    def test_client_and_report_root_drive_both_automation_paths(self):
        for settings, root, client in (
            ("", "/zap/wrk", "default-client"),
            (
                "MCP_CLIENT_ID=build-client\nZAP_REPORT_DIRECTORY=/zap/wrk/reports\n",
                "/zap/wrk/reports",
                "build-client",
            ),
        ):
            with self.subTest(client=client, root=root):
                environment = self.render_environment(settings)
                automation = f"{root}/workspaces/{client}/automation"
                self.assertEqual(environment["ZAP_REPORT_DIRECTORY"], root)
                self.assertEqual(environment["ZAP_AUTOMATION_LOCAL_DIRECTORY"], automation)
                self.assertEqual(environment["ZAP_AUTOMATION_ZAP_DIRECTORY"], automation)

    def test_explicit_directory_settings_reach_the_container(self):
        environment = self.render_environment(
            "ZAP_AUTOMATION_LOCAL_DIRECTORY=/zap/wrk/workspaces/default-client/local-plans\n"
            "ZAP_AUTOMATION_ZAP_DIRECTORY=/zap/wrk/workspaces/default-client/zap-plans\n"
        )
        self.assertEqual(
            environment["ZAP_AUTOMATION_LOCAL_DIRECTORY"],
            "/zap/wrk/workspaces/default-client/local-plans",
        )
        self.assertEqual(
            environment["ZAP_AUTOMATION_ZAP_DIRECTORY"],
            "/zap/wrk/workspaces/default-client/zap-plans",
        )

    def test_root_and_ec2_compose_forward_independent_crawl_limits(self):
        configurations = (
            (ROOT / "docker-compose.yml", ROOT / ".env.example", ""),
            (
                ROOT / "examples/aws-ec2/compose.yaml",
                ROOT / "examples/aws-ec2/.env.example",
                "MCP_API_KEY=ci-only-mcp-key\nZAP_API_KEY=ci-only-zap-key\n"
                "MCP_ZAP_IMAGE=example.invalid/mcp-zap-server@sha256:" + "0" * 64 + "\n",
            ),
        )
        for compose_path, example_path, credentials in configurations:
            for settings, depth, children in (
                ("ZAP_SPIDER_MAX_DEPTH=1\nZAP_SPIDER_MAX_CHILDREN=8\n", "1", "8"),
                ("ZAP_SPIDER_MAX_DEPTH=0\nZAP_SPIDER_MAX_CHILDREN=4\n", "0", "4"),
                ("ZAP_SPIDER_MAX_DEPTH=2\nZAP_SPIDER_MAX_CHILDREN=0\n", "2", "0"),
            ):
                with self.subTest(compose=compose_path.name, depth=depth, children=children):
                    environment = self.render_environment(
                        credentials + settings, compose_path, example_path
                    )
                    self.assertEqual(environment["ZAP_SPIDER_MAX_DEPTH"], depth)
                    self.assertEqual(environment["ZAP_SPIDER_MAX_CHILDREN"], children)


if __name__ == "__main__":
    unittest.main()
