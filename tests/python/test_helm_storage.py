import json
from pathlib import Path
import re
import shutil
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[2]
CHART = ROOT / "helm" / "mcp-zap-server"
HELM = shutil.which("helm")
RELEASE = "storage-test"
WORKSPACE = f"{RELEASE}-mcp-zap-server"
WORKSPACE_LABEL = "mcp-zap-server.io/workspace"


@unittest.skipUnless(HELM, "Helm is required for chart rendering tests")
class HelmSharedWorkspaceTest(unittest.TestCase):
    def render(self, *arguments, expected_error=None):
        result = subprocess.run(
            [
                HELM,
                "template",
                RELEASE,
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
        if expected_error is not None:
            self.assertNotEqual(result.returncode, 0, "Invalid values unexpectedly rendered")
            self.assertIn(expected_error, result.stderr)
            return result.stderr
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout

    def resource(self, rendered, filename):
        resources = [
            document
            for document in rendered.split("\n---")
            if f"# Source: mcp-zap-server/templates/{filename}" in document
        ]
        self.assertLessEqual(len(resources), 1)
        return resources[0] if resources else None

    def assert_workspace(self, deployment, claim, mount_path):
        self.assertIsNotNone(deployment)
        self.assertRegex(
            deployment,
            r'volumeMounts:\n\s+- name: zap-data\n\s+mountPath: "'
            + re.escape(mount_path)
            + '"',
        )
        self.assertRegex(
            deployment,
            r'volumes:\n\s+- name: zap-data\n\s+persistentVolumeClaim:\n\s+claimName: "'
            + re.escape(claim)
            + '"',
        )

    def test_default_shares_retained_claim_and_requires_same_workspace_node(self):
        rendered = self.render()
        mcp = self.resource(rendered, "mcp-deployment.yaml")
        zap = self.resource(rendered, "zap-deployment.yaml")
        claim = f"{RELEASE}-mcp-zap-server-zap-pvc"
        for deployment in (mcp, zap):
            self.assert_workspace(deployment, claim, "/zap/wrk")
            self.assertIn(f'{WORKSPACE_LABEL}: "{WORKSPACE}"', deployment)
            affinity = deployment.split("affinity:", 1)[1]
            self.assertRegex(
                affinity,
                r"requiredDuringSchedulingIgnoredDuringExecution:\n"
                r"\s+- labelSelector:\n\s+matchLabels:\n\s+"
                + re.escape(f"{WORKSPACE_LABEL}: {WORKSPACE}")
                + r"\n\s+topologyKey: kubernetes.io/hostname",
            )
        pvc = self.resource(rendered, "zap-pvc.yaml")
        self.assertIn("helm.sh/resource-policy: keep", pvc)

    def test_custom_path_updates_both_mounts_and_application_directories(self):
        rendered = self.render("--set", "zap.persistence.mountPath=/shared/zap/")
        mcp = self.resource(rendered, "mcp-deployment.yaml")
        for filename in ("mcp-deployment.yaml", "zap-deployment.yaml"):
            self.assert_workspace(
                self.resource(rendered, filename),
                f"{RELEASE}-mcp-zap-server-zap-pvc",
                "/shared/zap/",
            )
        self.assertIn('- name: ZAP_REPORT_DIRECTORY\n          value: "/shared/zap/"', mcp)
        for name in ("ZAP_AUTOMATION_LOCAL_DIRECTORY", "ZAP_AUTOMATION_ZAP_DIRECTORY"):
            self.assertIn(f'- name: {name}\n          value: "/shared/zap/automation"', mcp)

    def test_existing_claim_is_shared_without_creating_a_claim(self):
        rendered = self.render("--set", "zap.persistence.existingClaim=existing-workspace")
        self.assertIsNone(self.resource(rendered, "zap-pvc.yaml"))
        for filename in ("mcp-deployment.yaml", "zap-deployment.yaml"):
            self.assert_workspace(
                self.resource(rendered, filename), "existing-workspace", "/zap/wrk"
            )

    def test_rwx_does_not_force_same_node(self):
        rendered = self.render("--set", "zap.persistence.accessMode=ReadWriteMany")
        for filename in ("mcp-deployment.yaml", "zap-deployment.yaml"):
            deployment = self.resource(rendered, filename)
            self.assertIn("volumeMounts:", deployment)
            self.assertNotIn("requiredDuringSchedulingIgnoredDuringExecution:", deployment)

    def test_rwo_affinity_preserves_operator_constraints(self):
        affinity = {
            "nodeAffinity": {
                "requiredDuringSchedulingIgnoredDuringExecution": {
                    "nodeSelectorTerms": [{"matchExpressions": [{
                        "key": "workload", "operator": "In", "values": ["scanner"]
                    }]}]
                }
            },
            "podAffinity": {
                "requiredDuringSchedulingIgnoredDuringExecution": [{
                    "labelSelector": {"matchLabels": {"app": "operator-helper"}},
                    "topologyKey": "topology.kubernetes.io/zone",
                }]
            },
            "podAntiAffinity": {
                "preferredDuringSchedulingIgnoredDuringExecution": [{
                    "weight": 50,
                    "podAffinityTerm": {
                        "labelSelector": {"matchLabels": {"app": "busy-workload"}},
                        "topologyKey": "kubernetes.io/hostname",
                    },
                }]
            },
        }
        rendered = self.render(
            "--set-json", "mcp.affinity=" + json.dumps(affinity),
            "--set-json", "zap.affinity=" + json.dumps(affinity),
        )
        for filename in ("mcp-deployment.yaml", "zap-deployment.yaml"):
            component_affinity = self.resource(rendered, filename).split("affinity:", 1)[1]
            for expected in (
                "key: workload", "scanner", "app: operator-helper", "app: busy-workload",
                "topologyKey: topology.kubernetes.io/zone",
                f"{WORKSPACE_LABEL}: {WORKSPACE}",
                "topologyKey: kubernetes.io/hostname",
            ):
                self.assertIn(expected, component_affinity)

    def test_operator_labels_cannot_replace_shared_workspace_identity(self):
        self.render(
            "--set-json", "podLabels=" + json.dumps({WORKSPACE_LABEL: "another-workspace"}),
            expected_error=f"podLabels must not override the chart-owned {WORKSPACE_LABEL} label",
        )

    def test_opt_out_keeps_zap_storage_and_removes_mcp_workspace(self):
        rendered = self.render("--set", "zap.persistence.shareWithMcp=false")
        mcp = self.resource(rendered, "mcp-deployment.yaml")
        self.assertNotIn("volumeMounts:", mcp)
        self.assertNotIn("ZAP_REPORT_DIRECTORY", mcp)
        self.assertNotIn("ZAP_AUTOMATION_LOCAL_DIRECTORY", mcp)
        self.assertNotIn("requiredDuringSchedulingIgnoredDuringExecution:", mcp)
        self.assertIn("volumeMounts:", self.resource(rendered, "zap-deployment.yaml"))
        self.assertIsNotNone(self.resource(rendered, "zap-pvc.yaml"))

    def test_disabled_persistence_omits_all_workspace_resources(self):
        rendered = self.render(
            "--set", "zap.persistence.enabled=false",
            "--set", "zap.persistence.shareWithMcp=false",
        )
        self.assertIsNone(self.resource(rendered, "zap-pvc.yaml"))
        for filename in ("mcp-deployment.yaml", "zap-deployment.yaml"):
            self.assertNotIn("volumeMounts:", self.resource(rendered, filename))

    def test_claim_retention_can_be_disabled(self):
        rendered = self.render("--set", "zap.persistence.retainOnDelete=false")
        self.assertNotIn("helm.sh/resource-policy", self.resource(rendered, "zap-pvc.yaml"))

    def test_zap_only_deployment_keeps_its_workspace(self):
        rendered = self.render("--set", "mcp.enabled=false")
        self.assertIsNone(self.resource(rendered, "mcp-deployment.yaml"))
        self.assertIn("volumeMounts:", self.resource(rendered, "zap-deployment.yaml"))


if __name__ == "__main__":
    unittest.main()
