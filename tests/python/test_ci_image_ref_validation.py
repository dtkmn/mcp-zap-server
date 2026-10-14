import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
GITHUB_GATE_SCRIPT = ROOT / ".github" / "actions" / "zap-security-gate" / "run-gate.sh"
GITLAB_GATE_SCRIPT = ROOT / "examples" / "gitlab" / "run-zap-security-gate.sh"
IMAGE = "ghcr.io/dtkmn/mcp-zap-server"


class CiImageRefValidationTest(unittest.TestCase):
    def assert_rejected_refs(self, provider, malformed_digests):
        cases = (
            (f"{IMAGE}:dev", "mutable :dev tag"),
            (IMAGE, "Bare image refs are not allowed"),
            (f"{IMAGE}:<release-tag>", "still contains placeholder text"),
            *((f"{IMAGE}@sha256:{digest}", "exactly 64 hexadecimal characters")
              for digest in malformed_digests),
        )
        for image_ref, error in cases:
            with self.subTest(provider=provider, image_ref=image_ref), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                workspace = root / "work"
                output = root / "out"
                environment = os.environ.copy()
                if provider == "github":
                    script = GITHUB_GATE_SCRIPT
                    environment.update({
                        "GITHUB_ACTION_PATH": str(script.parent),
                        "GITHUB_WORKSPACE": str(ROOT),
                        "INPUT_START_STACK": "true",
                        "INPUT_MCP_SERVER_IMAGE": image_ref,
                        "INPUT_LOCAL_ZAP_WORKSPACE_FOLDER": str(workspace),
                        "INPUT_OUTPUT_DIR": str(output),
                    })
                else:
                    script = GITLAB_GATE_SCRIPT
                    environment.update({
                        "CI_PROJECT_DIR": str(ROOT),
                        "ZAP_TARGET_URL": "http://example.com",
                        "MCP_SERVER_IMAGE": image_ref,
                        "ZAP_LOCAL_WORKSPACE_FOLDER": str(workspace),
                        "ZAP_OUTPUT_DIR": str(output),
                    })
                result = subprocess.run(
                    ["bash", str(script)], cwd=ROOT, env=environment,
                    text=True, capture_output=True, timeout=15,
                )
                self.assertEqual(result.returncode, 1, result.stderr)
                self.assertIn(error, result.stderr)
                self.assertFalse(workspace.exists())
                self.assertFalse(output.exists())

    def test_github_gate_rejects_invalid_image_refs_before_workspace_mutation(self):
        self.assert_rejected_refs("github", ("", "a" * 63, "a" * 63 + "g"))

    def test_gitlab_gate_rejects_invalid_image_refs_before_workspace_mutation(self):
        self.assert_rejected_refs("gitlab", ("", "b" * 65, "z" * 64))


if __name__ == "__main__":
    unittest.main()
