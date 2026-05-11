from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]


class CiToolSurfaceDefaultsTest(unittest.TestCase):
    def test_github_ci_compose_uses_expert_surface(self):
        text = (ROOT / ".github" / "actions" / "zap-security-gate" / "docker-compose.ci.yml").read_text()
        self.assertIn("MCP_SERVER_TOOLS_SURFACE: expert", text)

    def test_gitlab_ci_compose_uses_expert_surface(self):
        text = (ROOT / "examples" / "gitlab" / "docker-compose.gitlab-ci.yml").read_text()
        self.assertIn("MCP_SERVER_TOOLS_SURFACE: expert", text)


if __name__ == "__main__":
    unittest.main()
