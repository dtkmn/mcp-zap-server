from pathlib import Path
import json
import shutil
import subprocess
import unittest

from test_helm_security import env_values, find_resource, parse_manifests


ROOT = Path(__file__).resolve().parents[2]
CHART = ROOT / "helm" / "mcp-zap-server"
HELM = shutil.which("helm")


@unittest.skipUnless(HELM, "Helm is required")
class LaunchSafetyDeploymentTest(unittest.TestCase):
    def render(self, *arguments, error=None):
        result = subprocess.run(
            [HELM, "template", "launch-safety", str(CHART), "--values", str(CHART / "values-ci.yaml"), *arguments],
            cwd=ROOT, text=True, capture_output=True, check=False,
        )
        if error:
            self.assertNotEqual(result.returncode, 0)
            self.assertIn(error, result.stderr)
            return None
        self.assertEqual(result.returncode, 0, result.stderr)
        return parse_manifests(result.stdout)

    def mcp(self, resources):
        return find_resource(resources, "Deployment", name="launch-safety-mcp-zap-server-mcp")

    def journal_claims(self, resources):
        return [r for r in resources if r.get("kind") == "PersistentVolumeClaim"
                and r["metadata"]["name"].endswith("-mcp-launch-safety")]

    def test_memory_has_private_persistent_mcp_only_state_and_recreate(self):
        resources = self.render()
        mcp = self.mcp(resources)
        self.assertEqual(mcp["spec"]["strategy"], {"type": "Recreate"})
        container = mcp["spec"]["template"]["spec"]["containers"][0]
        self.assertEqual(env_values(container)["ZAP_SCAN_JOBS_STORE_BACKEND"], "in-memory")
        self.assertNotIn("ZAP_SCAN_RECONCILE_OPERATOR_CLIENT_IDS", env_values(container))
        self.assertEqual(env_values(container)["ZAP_SCAN_LAUNCH_JOURNAL_DIRECTORY"],
                         "/var/lib/mcp-zap/scan-launch-safety/receipts")
        self.assertEqual(len(self.journal_claims(resources)), 1)
        zap = find_resource(resources, "Deployment", name="launch-safety-mcp-zap-server-zap")
        self.assertNotIn("mcp-launch-safety", [v["name"] for v in zap["spec"]["template"]["spec"].get("volumes", [])])

    def test_postgres_keeps_multiworker_strategy_without_memory_journal(self):
        resources = self.render("--set", "mcp.scan.jobs.store.backend=postgres", *self.multi_flags())
        mcp = self.mcp(resources)
        self.assertEqual(mcp["spec"]["strategy"]["type"], "RollingUpdate")
        self.assertEqual(mcp["spec"]["replicas"], 2)
        self.assertEqual(self.journal_claims(resources), [])
        self.assertNotIn("mcp-launch-safety", [v["name"] for v in mcp["spec"]["template"]["spec"].get("volumes", [])])

    def test_legacy_literal_postgres_override_is_not_shadowed_by_memory_default(self):
        resources = self.render("--set", "mcp.env[0].name=ZAP_SCAN_QUEUE_STORE_BACKEND",
                                "--set", "mcp.env[0].value=postgres", *self.multi_flags())
        container = self.mcp(resources)["spec"]["template"]["spec"]["containers"][0]
        self.assertEqual(env_values(container)["ZAP_SCAN_JOBS_STORE_BACKEND"], "postgres")
        self.assertEqual(self.journal_claims(resources), [])

    def test_canonical_backend_retains_precedence_over_legacy_alias(self):
        resources = self.render("--set", "mcp.env[0].name=ZAP_SCAN_QUEUE_STORE_BACKEND", "--set", "mcp.env[0].value=postgres",
                                "--set", "mcp.env[1].name=ZAP_SCAN_JOBS_STORE_BACKEND", "--set", "mcp.env[1].value=in-memory")
        self.assertEqual(len(self.journal_claims(resources)), 1)

    def test_memory_multiple_controllers_or_autoscaling_fail_clearly(self):
        self.render(*self.multi_flags(), error="requires one MCP controller")
        self.render(*self.multi_flags(), "--set", "mcp.autoscaling.enabled=true", "--set", "mcp.autoscaling.maxReplicas=2",
                    error="requires one MCP controller")

    def test_single_controller_hpa_uses_effective_maximum_instead_of_ignored_replica_count(self):
        resources = self.render("--set", "mcp.replicaCount=3", "--set", "mcp.autoscaling.enabled=true",
                                "--set", "mcp.autoscaling.minReplicas=1", "--set", "mcp.autoscaling.maxReplicas=1")
        self.assertNotIn("replicas", self.mcp(resources)["spec"])
        self.assertEqual(self.mcp(resources)["spec"]["strategy"], {"type": "Recreate"})

    def test_opaque_backend_and_undeclared_envfrom_fail_instead_of_guessing_authority(self):
        self.render("--set", "mcp.env[0].name=ZAP_SCAN_JOBS_STORE_BACKEND",
                    "--set", "mcp.env[0].valueFrom.secretKeyRef.name=backend", error="must be an explicit literal")
        self.render("--set", "mcp.envFrom[0].secretRef.name=runtime", error="Set mcp.scan.jobs.store.backend explicitly")
        self.render("--set", "mcp.scan.jobs.store.backend=postgres", "--set", "mcp.envFrom[0].secretRef.name=runtime")

    def test_existing_private_claim_is_reused_without_publishing_a_new_one(self):
        resources = self.render("--set", "mcp.launchSafety.persistence.existingClaim=operator-private-state")
        self.assertEqual(self.journal_claims(resources), [])
        volumes = self.mcp(resources)["spec"]["template"]["spec"]["volumes"]
        journal = next(v for v in volumes if v["name"] == "mcp-launch-safety")
        self.assertEqual(journal["persistentVolumeClaim"]["claimName"], "operator-private-state")

    def test_journal_claim_cannot_alias_the_zap_workspace(self):
        self.render("--set", "mcp.launchSafety.persistence.existingClaim=shared-sensitive",
                    "--set", "zap.persistence.existingClaim=shared-sensitive", error="MCP-only claim")

    def test_chart_owned_journal_and_authority_cannot_be_overridden_through_extra_env(self):
        for name in ("ZAP_SCAN_LAUNCH_JOURNAL_DIRECTORY", "ZAP_SCAN_JOBS_LAUNCHSAFETY_JOURNALDIRECTORY"):
            with self.subTest(name=name):
                self.render("--set-json", "mcp.env=" + json.dumps([{"name": name, "value": "/tmp/unsafe"}]),
                            error="chart-owned launch safety")

    def test_json_and_jvm_aliases_cannot_change_storage_authority_after_mount_selection(self):
        for value in ({"zap.scan.jobs.store.backend": "in-memory"},
                      {"zap.scan.queue.store.backend": "in-memory"},
                      {"zap": {"scan": {"jobs": {"launch-safety": {"journal-directory": "/tmp/unsafe"}}}}}):
            with self.subTest(value=value):
                self.render("--set", "mcp.scan.jobs.store.backend=postgres", "--set-json",
                            "mcp.env=" + json.dumps([{"name": "SPRING_APPLICATION_JSON", "value": json.dumps(value)}]),
                            error="launch ownership property")
        for name, value in (("JAVA_TOOL_OPTIONS", "-Dzap.scan.jobs.launch-safety.journal-directory=/tmp/unsafe"),
                            ("JDK_JAVA_OPTIONS", "-Dzap.scan.jobs.store.backend=in-memory")):
            with self.subTest(name=name):
                self.render("--set-json", "mcp.env=" + json.dumps([{"name": name, "value": value}]),
                            error="launch ownership property")

    def test_unrelated_jvm_and_bootstrap_json_remain_supported(self):
        self.render("--set-json", "mcp.env=" + json.dumps([
            {"name": "JAVA_TOOL_OPTIONS", "value": "-Xmx256m"},
            {"name": "SPRING_APPLICATION_JSON", "value": json.dumps({"zap": {"auth": {"bootstrap": {"enabled": True}}}})},
        ]))

    def multi_flags(self):
        return ("--set", "mcp.replicaCount=2", "--set", "mcp.streamableHttp.sessionAffinity.enabled=true",
                "--set", "mcp.streamableHttp.sessionAffinity.provider=service-client-ip",
                "--set", "zap.persistence.shareWithMcp=false")


if __name__ == "__main__":
    unittest.main()
