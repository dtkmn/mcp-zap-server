import json
from pathlib import Path
import shutil
import subprocess
import unittest

from test_helm_security import env_values, find_resource, parse_manifests


ROOT = Path(__file__).resolve().parents[2]
CHART = ROOT / "helm" / "mcp-zap-server"
HELM = shutil.which("helm")
RELEASE = "storage-test"
WORKSPACE = f"{RELEASE}-mcp-zap-server"
WORKSPACE_LABEL = "mcp-zap-server.io/workspace"
WORKSPACE_AFFINITY = {
    "labelSelector": {"matchLabels": {WORKSPACE_LABEL: WORKSPACE}},
    "topologyKey": "kubernetes.io/hostname",
}
COMPONENTS = (("mcp-server", "mcp-server"), ("zap-proxy", "zap"))


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

    def container(self, deployment, name):
        matches = [container for container in deployment["spec"]["template"]["spec"]["containers"]
                   if container["name"] == name]
        self.assertEqual(len(matches), 1)
        return matches[0]

    def assert_workspace(self, deployment, container_name, claim, mount_path):
        container = self.container(deployment, container_name)
        mounts = [mount for mount in container.get("volumeMounts", [])
                  if mount["name"] == "zap-data"]
        self.assertEqual(len(mounts), 1)
        self.assertEqual(mounts[0]["mountPath"], mount_path)
        volumes = [volume for volume in deployment["spec"]["template"]["spec"].get("volumes", [])
                   if volume["name"] == "zap-data"]
        self.assertEqual(len(volumes), 1)
        self.assertEqual(volumes[0]["persistentVolumeClaim"]["claimName"], claim)

    def test_default_shares_retained_claim_and_requires_same_workspace_node(self):
        resources = parse_manifests(self.render())
        claim = f"{RELEASE}-mcp-zap-server-zap-pvc"
        for component, container_name in COMPONENTS:
            deployment = find_resource(resources, "Deployment", component=component)
            self.assert_workspace(deployment, container_name, claim, "/zap/wrk")
            template = deployment["spec"]["template"]
            self.assertEqual(template["metadata"]["labels"][WORKSPACE_LABEL], WORKSPACE)
            self.assertEqual(template["spec"]["affinity"]["podAffinity"]
                             ["requiredDuringSchedulingIgnoredDuringExecution"], [WORKSPACE_AFFINITY])
        pvc = find_resource(resources, "PersistentVolumeClaim", name=claim)
        self.assertEqual(pvc["spec"]["accessModes"], ["ReadWriteOnce"])
        self.assertEqual(pvc["metadata"]["annotations"]["helm.sh/resource-policy"], "keep")

    def test_custom_path_updates_both_mounts_and_application_directories(self):
        resources = parse_manifests(self.render("--set", "zap.persistence.mountPath=/shared/zap/"))
        for component, container_name in COMPONENTS:
            self.assert_workspace(
                find_resource(resources, "Deployment", component=component), container_name,
                f"{RELEASE}-mcp-zap-server-zap-pvc",
                "/shared/zap/",
            )
        mcp = find_resource(resources, "Deployment", component="mcp-server")
        environment = env_values(self.container(mcp, "mcp-server"))
        self.assertEqual(environment["ZAP_REPORT_DIRECTORY"], "/shared/zap/")
        for name in ("ZAP_AUTOMATION_LOCAL_DIRECTORY", "ZAP_AUTOMATION_ZAP_DIRECTORY"):
            self.assertEqual(environment[name], "/shared/zap/automation")

    def test_existing_claim_is_shared_without_creating_a_claim(self):
        resources = parse_manifests(self.render("--set", "zap.persistence.existingClaim=existing-workspace"))
        self.assertFalse(any(resource["kind"] == "PersistentVolumeClaim" for resource in resources))
        for component, container_name in COMPONENTS:
            self.assert_workspace(
                find_resource(resources, "Deployment", component=component), container_name,
                "existing-workspace", "/zap/wrk"
            )

    def test_rwx_does_not_force_same_node(self):
        resources = parse_manifests(self.render("--set", "zap.persistence.accessMode=ReadWriteMany"))
        for component, container_name in COMPONENTS:
            deployment = find_resource(resources, "Deployment", component=component)
            self.assert_workspace(deployment, container_name, f"{WORKSPACE}-zap-pvc", "/zap/wrk")
            required = deployment["spec"]["template"]["spec"].get("affinity", {}).get("podAffinity", {}).get(
                "requiredDuringSchedulingIgnoredDuringExecution", [])
            self.assertNotIn(WORKSPACE_AFFINITY, required)
        self.assertEqual(find_resource(resources, "PersistentVolumeClaim")["spec"]["accessModes"],
                         ["ReadWriteMany"])

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
        resources = parse_manifests(self.render(
            "--set-json", "mcp.affinity=" + json.dumps(affinity),
            "--set-json", "zap.affinity=" + json.dumps(affinity),
        ))
        for component, _ in COMPONENTS:
            deployment = find_resource(resources, "Deployment", component=component)
            actual = deployment["spec"]["template"]["spec"]["affinity"]
            self.assertEqual(actual["nodeAffinity"], affinity["nodeAffinity"])
            self.assertEqual(actual["podAntiAffinity"], affinity["podAntiAffinity"])
            required = actual["podAffinity"]["requiredDuringSchedulingIgnoredDuringExecution"]
            expected = affinity["podAffinity"]["requiredDuringSchedulingIgnoredDuringExecution"]
            self.assertCountEqual(required, [*expected, WORKSPACE_AFFINITY])

    def test_operator_labels_cannot_replace_shared_workspace_identity(self):
        self.render(
            "--set-json", "podLabels=" + json.dumps({WORKSPACE_LABEL: "another-workspace"}),
            expected_error=f"podLabels must not override the chart-owned {WORKSPACE_LABEL} label",
        )

    def test_opt_out_keeps_zap_storage_and_removes_mcp_workspace(self):
        resources = parse_manifests(self.render("--set", "zap.persistence.shareWithMcp=false"))
        mcp = find_resource(resources, "Deployment", component="mcp-server")
        container = self.container(mcp, "mcp-server")
        self.assertFalse(container.get("volumeMounts"))
        self.assertFalse(mcp["spec"]["template"]["spec"].get("volumes"))
        for name in ("ZAP_REPORT_DIRECTORY", "ZAP_AUTOMATION_LOCAL_DIRECTORY", "ZAP_AUTOMATION_ZAP_DIRECTORY"):
            self.assertNotIn(name, env_values(container))
        required = mcp["spec"]["template"]["spec"].get("affinity", {}).get("podAffinity", {}).get(
            "requiredDuringSchedulingIgnoredDuringExecution", [])
        self.assertNotIn(WORKSPACE_AFFINITY, required)
        zap = find_resource(resources, "Deployment", component="zap-proxy")
        self.assert_workspace(zap, "zap", f"{WORKSPACE}-zap-pvc", "/zap/wrk")
        find_resource(resources, "PersistentVolumeClaim", name=f"{WORKSPACE}-zap-pvc")

    def test_disabled_persistence_omits_all_workspace_resources(self):
        resources = parse_manifests(self.render(
            "--set", "zap.persistence.enabled=false",
            "--set", "zap.persistence.shareWithMcp=false",
        ))
        self.assertFalse(any(resource["kind"] == "PersistentVolumeClaim" for resource in resources))
        for component, container_name in COMPONENTS:
            deployment = find_resource(resources, "Deployment", component=component)
            self.assertFalse(self.container(deployment, container_name).get("volumeMounts"))
            self.assertFalse(deployment["spec"]["template"]["spec"].get("volumes"))

    def test_claim_retention_can_be_disabled(self):
        resources = parse_manifests(self.render("--set", "zap.persistence.retainOnDelete=false"))
        pvc = find_resource(resources, "PersistentVolumeClaim", name=f"{WORKSPACE}-zap-pvc")
        self.assertNotIn("helm.sh/resource-policy", pvc["metadata"].get("annotations", {}))

    def test_zap_only_deployment_keeps_its_workspace(self):
        resources = parse_manifests(self.render("--set", "mcp.enabled=false"))
        self.assertFalse(any(resource["kind"] == "Deployment"
                             and resource["metadata"]["labels"].get("app.kubernetes.io/component") == "mcp-server"
                             for resource in resources))
        zap = find_resource(resources, "Deployment", component="zap-proxy")
        self.assert_workspace(zap, "zap", f"{WORKSPACE}-zap-pvc", "/zap/wrk")


if __name__ == "__main__":
    unittest.main()
