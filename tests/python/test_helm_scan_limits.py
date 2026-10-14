from pathlib import Path
import shutil
import subprocess
import unittest

from test_helm_security import env_values, find_resource, parse_manifests

ROOT = Path(__file__).resolve().parents[2]
CHART = ROOT / "helm" / "mcp-zap-server"
HELM = shutil.which("helm")


@unittest.skipUnless(HELM, "Helm is required for chart rendering tests")
class HelmScanLimitsTest(unittest.TestCase):
    def render(self, *arguments):
        result = subprocess.run(
            [
                HELM, "template", "scan-limits-test", str(CHART),
                "--values", str(CHART / "values-ci.yaml"), *arguments,
            ],
            cwd=ROOT, text=True, capture_output=True, timeout=30,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout

    def test_custom_and_unlimited_crawl_limits_are_forwarded_independently(self):
        for depth, children in ((1, 8), (0, 4), (2, 0)):
            with self.subTest(depth=depth, children=children):
                rendered = self.render(
                    "--set", f"mcp.scan.limits.spiderMaxDepth={depth}",
                    "--set", f"mcp.scan.limits.spiderMaxChildren={children}",
                )
                deployment = find_resource(
                    parse_manifests(rendered), "Deployment", component="mcp-server"
                )
                containers = deployment["spec"]["template"]["spec"]["containers"]
                container = next(item for item in containers if item["name"] == "mcp-server")
                environment = env_values(container)
                self.assertEqual(environment["ZAP_SPIDER_MAX_DEPTH"], str(depth))
                self.assertEqual(environment["ZAP_SPIDER_MAX_CHILDREN"], str(children))


if __name__ == "__main__":
    unittest.main()
