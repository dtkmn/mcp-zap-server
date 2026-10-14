import json
from pathlib import Path
import shutil
import subprocess
import unittest

from test_helm_security import find_resource, parse_manifests


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
        return parse_manifests(result.stdout)

    def assert_ingress(self, resources, peers, port):
        policy = find_resource(resources, "NetworkPolicy", component="mcp-server")
        ingress = policy["spec"]["ingress"]
        self.assertEqual(len(ingress), 1)
        self.assertCountEqual(ingress[0]["from"], peers)
        self.assertEqual(ingress[0]["ports"], [{"protocol": "TCP", "port": port}])

    def test_no_allowed_sources_renders_empty_ingress(self):
        for peers in ([], None):
            with self.subTest(extra_ingress=peers):
                resources = self.render_policy(
                    "--set",
                    "networkPolicy.mcp.allowSameNamespace=false",
                    "--set-json",
                    "networkPolicy.mcp.extraIngress=" + json.dumps(peers),
                )
                policy = find_resource(resources, "NetworkPolicy", component="mcp-server")
                self.assertEqual(policy["spec"]["ingress"], [])
                self.assertCountEqual(policy["spec"]["policyTypes"], ["Ingress", "Egress"])

    def test_same_namespace_allows_only_configured_mcp_port(self):
        resources = self.render_policy("--set", "mcp.service.targetPort=8443")
        self.assert_ingress(resources, [{"podSelector": {}}], 8443)

    def test_explicit_peers_preserve_selectors_and_configured_port(self):
        resources = self.render_policy(
            "--set",
            "networkPolicy.mcp.allowSameNamespace=false",
            "--set",
            "mcp.service.targetPort=8443",
            "--set-json",
            "networkPolicy.mcp.extraIngress=" + json.dumps(EXPLICIT_PEERS),
        )
        self.assert_ingress(resources, EXPLICIT_PEERS, 8443)

    def test_same_namespace_and_explicit_peers_are_combined(self):
        resources = self.render_policy(
            "--set", "mcp.service.targetPort=8443",
            "--set-json",
            "networkPolicy.mcp.extraIngress=" + json.dumps(EXPLICIT_PEERS),
        )
        self.assert_ingress(resources, [{"podSelector": {}}, *EXPLICIT_PEERS], 8443)

    def test_disabled_policy_or_mcp_component_omits_policy(self):
        for setting in ("networkPolicy.mcp.enabled=false", "mcp.enabled=false"):
            with self.subTest(setting=setting):
                resources = self.render_policy("--set", setting)
                self.assertFalse(any(resource["kind"] == "NetworkPolicy" and resource["metadata"]["labels"].get(
                    "app.kubernetes.io/component") == "mcp-server" for resource in resources))


if __name__ == "__main__":
    unittest.main()
