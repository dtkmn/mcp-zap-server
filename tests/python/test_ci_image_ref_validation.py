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
            self.assertEqual((workspace_dir / "zap-home").stat().st_mode & 0o777, 0o777)

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
            self.assertEqual((workspace_dir / "zap-home").stat().st_mode & 0o777, 0o777)

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
            self.assertIn("Replace <release-tag> with a real release tag or digest", result.stderr)
            self.assertEqual((workspace_dir / "zap-home").stat().st_mode & 0o777, 0o777)

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
            self.assertEqual((workspace_dir / "zap-home").stat().st_mode & 0o777, 0o777)

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
            self.assertIn("Replace <release-tag> with a real release tag or digest", result.stderr)
            self.assertEqual((workspace_dir / "zap-home").stat().st_mode & 0o777, 0o777)


if __name__ == "__main__":
    unittest.main()
