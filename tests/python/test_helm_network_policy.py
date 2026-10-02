import json
from pathlib import Path
import re
import shutil
import subprocess
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]
CHART = ROOT / "helm" / "mcp-zap-server"
HELM = shutil.which("helm")
EXPLICIT_PEERS = [
    {
        "namespaceSelector": {
            "matchLabels": {"kubernetes.io/metadata.name": "ingress-nginx"}
        },
        "podSelector": {"matchLabels": {"app": "ingress-controller"}},
    },
    {"ipBlock": {"cidr": "203.0.113.0/24"}},
]


@unittest.skipUnless(HELM, "Helm is required for chart rendering tests")
class HelmMcpNetworkPolicyTest(unittest.TestCase):
    def render_policy(self, *arguments):
        result = subprocess.run(
            [
                HELM,
                "template",
                "network-policy-test",
                str(CHART),
                "--values",
                str(CHART / "values-ci.yaml"),
                *arguments,
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
            timeout=30,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        policies = [
            document
            for document in result.stdout.split("\n---")
            if "# Source: mcp-zap-server/templates/mcp-networkpolicy.yaml" in document
        ]
        self.assertLessEqual(len(policies), 1)
        return policies[0] if policies else None

    def ingress_block(self, policy):
        self.assertIsNotNone(policy, "MCP NetworkPolicy was not rendered")
        match = re.search(r"^  ingress:.*?(?=^  \w|\Z)", policy, re.MULTILINE | re.DOTALL)
        self.assertIsNotNone(match, "MCP NetworkPolicy has no ingress field")
        return textwrap.dedent(match.group(0)).strip()

    def test_no_allowed_sources_renders_empty_ingress(self):
        for peers in ([], None):
            with self.subTest(extra_ingress=peers):
                policy = self.render_policy(
                    "--set",
                    "networkPolicy.mcp.allowSameNamespace=false",
                    "--set-json",
                    "networkPolicy.mcp.extraIngress=" + json.dumps(peers),
                )
                self.assertEqual(self.ingress_block(policy), "ingress: []")
                self.assertIn("  policyTypes:\n    - Ingress\n    - Egress\n", policy)
                self.assertIn("  egress:\n", policy)

    def test_default_allows_same_namespace_on_mcp_port(self):
        policy = self.render_policy()
        self.assertEqual(
            self.ingress_block(policy),
            textwrap.dedent(
                """\
                ingress:
                  - from:
                      - podSelector: {}
                    ports:
                      - protocol: TCP
                        port: 7456
                """
            ).strip(),
        )

    def test_explicit_peers_preserve_selectors_and_configured_port(self):
        policy = self.render_policy(
            "--set",
            "networkPolicy.mcp.allowSameNamespace=false",
            "--set",
            "mcp.service.targetPort=8443",
            "--set-json",
            "networkPolicy.mcp.extraIngress=" + json.dumps(EXPLICIT_PEERS),
        )
        self.assertEqual(
            self.ingress_block(policy),
            textwrap.dedent(
                """\
                ingress:
                  - from:
                      - namespaceSelector:
                          matchLabels:
                            kubernetes.io/metadata.name: ingress-nginx
                        podSelector:
                          matchLabels:
                            app: ingress-controller
                      - ipBlock:
                          cidr: 203.0.113.0/24
                    ports:
                      - protocol: TCP
                        port: 8443
                """
            ).strip(),
        )

    def test_same_namespace_and_explicit_peers_are_combined(self):
        policy = self.render_policy(
            "--set-json",
            "networkPolicy.mcp.extraIngress=" + json.dumps(EXPLICIT_PEERS),
        )
        self.assertEqual(
            self.ingress_block(policy),
            textwrap.dedent(
                """\
                ingress:
                  - from:
                      - podSelector: {}
                      - namespaceSelector:
                          matchLabels:
                            kubernetes.io/metadata.name: ingress-nginx
                        podSelector:
                          matchLabels:
                            app: ingress-controller
                      - ipBlock:
                          cidr: 203.0.113.0/24
                    ports:
                      - protocol: TCP
                        port: 7456
                """
            ).strip(),
        )

    def test_disabled_policy_or_mcp_component_omits_policy(self):
        for setting in ("networkPolicy.mcp.enabled=false", "mcp.enabled=false"):
            with self.subTest(setting=setting):
                self.assertIsNone(self.render_policy("--set", setting))


if __name__ == "__main__":
    unittest.main()
