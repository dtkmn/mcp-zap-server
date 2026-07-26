import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
GITHUB_GATE_SCRIPT = ROOT / ".github" / "actions" / "zap-security-gate" / "run-gate.sh"
GITLAB_GATE_SCRIPT = ROOT / "examples" / "gitlab" / "run-zap-security-gate.sh"


class CiImageRefValidationTest(unittest.TestCase):
    def test_github_gate_rejects_mutable_dev_tag(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            workspace_dir = Path(tmpdir) / "work"
            env = os.environ.copy()
            env.update(
                {
                    "GITHUB_ACTION_PATH": str(ROOT / ".github" / "actions" / "zap-security-gate"),
                    "GITHUB_WORKSPACE": str(ROOT),
                    "INPUT_START_STACK": "true",
                    "INPUT_MCP_SERVER_IMAGE": "ghcr.io/dtkmn/mcp-zap-server:dev",
                    "INPUT_LOCAL_ZAP_WORKSPACE_FOLDER": str(workspace_dir),
                    "INPUT_OUTPUT_DIR": str(Path(tmpdir) / "out"),
                }
            )

            result = subprocess.run(
                ["bash", str(GITHUB_GATE_SCRIPT)],
                cwd=ROOT,
                env=env,
                text=True,
                capture_output=True,
            )

            self.assertEqual(result.returncode, 1)
            self.assertIn("mutable :dev tag", result.stderr)
            self.assertFalse(workspace_dir.exists())

    def test_github_gate_rejects_bare_image_ref(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            workspace_dir = Path(tmpdir) / "work"
            env = os.environ.copy()
            env.update(
                {
                    "GITHUB_ACTION_PATH": str(ROOT / ".github" / "actions" / "zap-security-gate"),
                    "GITHUB_WORKSPACE": str(ROOT),
                    "INPUT_START_STACK": "true",
                    "INPUT_MCP_SERVER_IMAGE": "ghcr.io/dtkmn/mcp-zap-server",
                    "INPUT_LOCAL_ZAP_WORKSPACE_FOLDER": str(workspace_dir),
                    "INPUT_OUTPUT_DIR": str(Path(tmpdir) / "out"),
                }
            )

            result = subprocess.run(
                ["bash", str(GITHUB_GATE_SCRIPT)],
                cwd=ROOT,
                env=env,
                text=True,
                capture_output=True,
            )

            self.assertEqual(result.returncode, 1)
            self.assertIn("Bare image refs are not allowed", result.stderr)
            self.assertFalse(workspace_dir.exists())

    def test_github_gate_rejects_release_tag_placeholder(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            workspace_dir = Path(tmpdir) / "work"
            env = os.environ.copy()
            env.update(
                {
                    "GITHUB_ACTION_PATH": str(ROOT / ".github" / "actions" / "zap-security-gate"),
                    "GITHUB_WORKSPACE": str(ROOT),
                    "INPUT_START_STACK": "true",
                    "INPUT_MCP_SERVER_IMAGE": "ghcr.io/dtkmn/mcp-zap-server:<release-tag>",
                    "INPUT_LOCAL_ZAP_WORKSPACE_FOLDER": str(workspace_dir),
                    "INPUT_OUTPUT_DIR": str(Path(tmpdir) / "out"),
                }
            )

            result = subprocess.run(
                ["bash", str(GITHUB_GATE_SCRIPT)],
                cwd=ROOT,
                env=env,
                text=True,
                capture_output=True,
            )

            self.assertEqual(result.returncode, 1)
            self.assertIn("still contains placeholder text", result.stderr)
            self.assertIn("Replace <release-tag> with a pinned release tag or sha256 digest", result.stderr)

    def test_github_gate_rejects_malformed_sha256_digests_before_workspace_mutation(self):
        malformed_digests = (
            "ghcr.io/dtkmn/mcp-zap-server@sha256:",
            "ghcr.io/dtkmn/mcp-zap-server@sha256:" + "a" * 63,
            "ghcr.io/dtkmn/mcp-zap-server@sha256:" + "a" * 63 + "g",
        )

        for index, image_ref in enumerate(malformed_digests):
            with self.subTest(image_ref=image_ref), tempfile.TemporaryDirectory() as tmpdir:
                workspace_dir = Path(tmpdir) / f"work-{index}"
                env = os.environ.copy()
                env.update(
                    {
                        "GITHUB_ACTION_PATH": str(ROOT / ".github" / "actions" / "zap-security-gate"),
                        "GITHUB_WORKSPACE": str(ROOT),
                        "INPUT_START_STACK": "true",
                        "INPUT_MCP_SERVER_IMAGE": image_ref,
                        "INPUT_LOCAL_ZAP_WORKSPACE_FOLDER": str(workspace_dir),
                        "INPUT_OUTPUT_DIR": str(Path(tmpdir) / "out"),
                    }
                )

                result = subprocess.run(
                    ["bash", str(GITHUB_GATE_SCRIPT)],
                    cwd=ROOT,
                    env=env,
                    text=True,
                    capture_output=True,
                )

                self.assertEqual(result.returncode, 1)
                self.assertIn("exactly 64 hexadecimal characters", result.stderr)
                self.assertFalse(workspace_dir.exists())

    def test_gitlab_gate_rejects_bare_image_ref(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            workspace_dir = Path(tmpdir) / "work"
            env = os.environ.copy()
            env.update(
                {
                    "CI_PROJECT_DIR": str(ROOT),
                    "ZAP_TARGET_URL": "http://example.com",
                    "MCP_SERVER_IMAGE": "ghcr.io/dtkmn/mcp-zap-server",
                    "ZAP_LOCAL_WORKSPACE_FOLDER": str(workspace_dir),
                    "ZAP_OUTPUT_DIR": str(Path(tmpdir) / "out"),
                }
            )

            result = subprocess.run(
                ["bash", str(GITLAB_GATE_SCRIPT)],
                cwd=ROOT,
                env=env,
                text=True,
                capture_output=True,
            )

            self.assertEqual(result.returncode, 1)
            self.assertIn("Bare image refs are not allowed", result.stderr)

    def test_gitlab_gate_rejects_release_tag_placeholder(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            workspace_dir = Path(tmpdir) / "work"
            env = os.environ.copy()
            env.update(
                {
                    "CI_PROJECT_DIR": str(ROOT),
                    "ZAP_TARGET_URL": "http://example.com",
                    "MCP_SERVER_IMAGE": "ghcr.io/dtkmn/mcp-zap-server:<release-tag>",
                    "ZAP_LOCAL_WORKSPACE_FOLDER": str(workspace_dir),
                    "ZAP_OUTPUT_DIR": str(Path(tmpdir) / "out"),
                }
            )

            result = subprocess.run(
                ["bash", str(GITLAB_GATE_SCRIPT)],
                cwd=ROOT,
                env=env,
                text=True,
                capture_output=True,
            )

            self.assertEqual(result.returncode, 1)
            self.assertIn("still contains placeholder text", result.stderr)
            self.assertIn("Replace <release-tag> with a pinned release tag or sha256 digest", result.stderr)

    def test_gitlab_gate_rejects_malformed_sha256_digests_before_workspace_mutation(self):
        malformed_digests = (
            "ghcr.io/dtkmn/mcp-zap-server@sha256:",
            "ghcr.io/dtkmn/mcp-zap-server@sha256:" + "b" * 65,
            "ghcr.io/dtkmn/mcp-zap-server@sha256:" + "z" * 64,
        )

        for index, image_ref in enumerate(malformed_digests):
            with self.subTest(image_ref=image_ref), tempfile.TemporaryDirectory() as tmpdir:
                workspace_dir = Path(tmpdir) / f"work-{index}"
                env = os.environ.copy()
                env.update(
                    {
                        "CI_PROJECT_DIR": str(ROOT),
                        "ZAP_TARGET_URL": "http://example.com",
                        "MCP_SERVER_IMAGE": image_ref,
                        "ZAP_LOCAL_WORKSPACE_FOLDER": str(workspace_dir),
                        "ZAP_OUTPUT_DIR": str(Path(tmpdir) / "out"),
                    }
                )

                result = subprocess.run(
                    ["bash", str(GITLAB_GATE_SCRIPT)],
                    cwd=ROOT,
                    env=env,
                    text=True,
                    capture_output=True,
                )

                self.assertEqual(result.returncode, 1)
                self.assertIn("exactly 64 hexadecimal characters", result.stderr)
                self.assertFalse(workspace_dir.exists())


if __name__ == "__main__":
    unittest.main()
