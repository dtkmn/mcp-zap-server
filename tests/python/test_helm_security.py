import json
from pathlib import Path
import shutil
import subprocess
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[2]
CHART = ROOT / "helm" / "mcp-zap-server"
HELM = shutil.which("helm")


def parse_manifests(rendered):
    return [resource for resource in yaml.safe_load_all(rendered) if resource is not None]


def find_resource(resources, kind, component=None, name=None):
    matches = [resource for resource in resources
               if resource["kind"] == kind
               and (component is None or resource.get("metadata", {}).get("labels", {}).get(
                   "app.kubernetes.io/component") == component)
               and (name is None or resource.get("metadata", {}).get("name") == name)]
    if len(matches) != 1:
        raise AssertionError(f"Expected one {kind} resource for component={component}, name={name}; got {len(matches)}")
    return matches[0]


def env_values(container):
    return {entry["name"]: entry["value"] if "value" in entry else entry["valueFrom"]
            for entry in container.get("env", [])}


@unittest.skipUnless(HELM, "Helm is required for chart rendering tests")
class HelmSecurityTest(unittest.TestCase):
    def render(self, *arguments, preset="values-ci.yaml", succeeds=True):
        result = subprocess.run(
            [HELM, "template", "security-test", str(CHART), "--values", str(CHART / preset), *arguments],
            cwd=ROOT, text=True, capture_output=True, timeout=30,
        )
        if succeeds:
            self.assertEqual(result.returncode, 0, result.stderr)
            return parse_manifests(result.stdout)
        self.assertNotEqual(result.returncode, 0, "Unsafe configuration unexpectedly rendered")
        return result.stderr

    def multi(self, *arguments, succeeds=True):
        return self.render(
            "--set", "mcp.replicaCount=2",
            "--set", "mcp.streamableHttp.sessionAffinity.enabled=true",
            "--set", "mcp.streamableHttp.sessionAffinity.provider=service-client-ip",
            "--set", "zap.persistence.shareWithMcp=false",
            "--set", "mcp.security.jwt.enabled=true",
            "--set-string", "mcp.security.jwt.secret=ci-only-jwt-signing-key-not-for-production",
            *arguments, succeeds=succeeds,
        )

    def mcp_container(self, resources):
        deployment = find_resource(resources, "Deployment", component="mcp-server")
        return next(container for container in deployment["spec"]["template"]["spec"]["containers"]
                    if container["name"] == "mcp-server")

    def test_shipped_presets_use_single_mcp_replica_and_internal_service(self):
        for preset in ("values-ci.yaml", "values-aws.yaml", "values-gcp.yaml", "values-secure-existing-secret.yaml"):
            with self.subTest(preset=preset):
                resources = self.render(preset=preset)
                deployment = find_resource(resources, "Deployment", component="mcp-server")
                self.assertEqual(deployment["spec"]["replicas"], 1)
                service = find_resource(resources, "Service", component="mcp-server")
                self.assertEqual(service["spec"]["type"], "ClusterIP")
                self.assertFalse(any(resource["kind"] == "HorizontalPodAutoscaler" for resource in resources))

    def test_ha_emits_shared_revocation_tls_and_stable_affinity(self):
        resources = self.render(preset="values-ha.yaml")
        environment = env_values(self.mcp_container(resources))
        self.assertEqual(environment["JWT_REVOCATION_STORE_BACKEND"], "postgres")
        self.assertEqual(environment["JWT_REVOCATION_STORE_POSTGRES_PASSWORD"], {
            "secretKeyRef": {"name": "mcp-zap-rds", "key": "RDS_PASSWORD"}})
        ingress = find_resource(resources, "Ingress", component="mcp-server")
        self.assertEqual(ingress["spec"]["tls"], [{
            "secretName": "mcp-zap-tls", "hosts": ["mcp-zap.example.com"]}])
        annotations = ingress["metadata"]["annotations"]
        self.assertEqual(annotations["nginx.ingress.kubernetes.io/ssl-redirect"], "true")
        self.assertEqual(annotations["nginx.ingress.kubernetes.io/upstream-hash-by"],
                         "$remote_addr$http_user_agent")

    def test_hpa_maximum_requires_affinity(self):
        error = self.render("--set", "mcp.autoscaling.enabled=true", "--set", "mcp.autoscaling.minReplicas=1", "--set", "mcp.autoscaling.maxReplicas=3", succeeds=False)
        self.assertIn("requires mcp.streamableHttp.sessionAffinity.enabled", error)

    def test_hpa_ignores_deployment_replica_count(self):
        resources = self.render("--set", "mcp.replicaCount=3", "--set", "mcp.autoscaling.enabled=true", "--set", "mcp.autoscaling.maxReplicas=1")
        self.assertNotIn("replicas", find_resource(resources, "Deployment", component="mcp-server")["spec"])
        self.assertEqual(find_resource(resources, "HorizontalPodAutoscaler")["spec"]["maxReplicas"], 1)

    def test_multi_jwt_rejects_local_state_even_outside_jwt_mode(self):
        for mode in ("api-key", "jwt", "none"):
            with self.subTest(mode=mode):
                self.assertIn("multi-replica JWT requires", self.multi("--set", "mcp.security.mode=" + mode, succeeds=False))

    def test_multi_jwt_accepts_explicit_postgres_url_only_configuration(self):
        resources = self.multi("--set", "mcp.security.jwt.revocation.backend=postgres", "--set-string", "mcp.security.jwt.revocation.postgres.url=jdbc:postgresql://database/test")
        environment = env_values(self.mcp_container(resources))
        self.assertEqual(environment["JWT_REVOCATION_STORE_BACKEND"], "postgres")
        self.assertEqual(environment["JWT_REVOCATION_STORE_POSTGRES_URL"], "jdbc:postgresql://database/test")

    def test_postgres_requires_url(self):
        error = self.multi("--set", "mcp.security.jwt.revocation.backend=postgres", succeeds=False)
        self.assertIn("postgres.url is required", error)

    def test_explicit_security_env_and_spring_aliases_cannot_bypass_values(self):
        for name in ("JWT_ENABLED", "JWT_REVOCATION_STORE_BACKEND", "JWT_REVOCATION_STORE_POSTGRES_URL", "MCP_SECURITY_MODE", "MCP_SERVER_AUTH_JWT_REVOCATION_BACKEND", "MCP_SERVER_SECURITY_ENABLED", "mcp.server.auth.jwt.revocation.backend"):
            with self.subTest(name=name):
                error = self.render("--set-json", "mcp.env=" + json.dumps([{"name": name, "value": "in-memory"}]), succeeds=False)
                self.assertIn("must not override chart-owned security", error)

    def test_spring_json_rejects_nested_dotted_and_relaxed_security_keys(self):
        for value in ({"mcp": {"server": {"auth": {"jwt": {"revocation": {"backend": "in-memory"}}}}}}, {"mcp.server.auth.jwt.revocation.backend": "in-memory"}, {"mcp": {"server": {"security": {"enabled": False}}}}, {"mcp_server_auth_jwt_enabled": True}, {"JWT_REVOCATION_STORE_BACKEND": "in-memory"}, {"JWT_ENABLED": True}):
            with self.subTest(value=value):
                env = [{"name": "SPRING_APPLICATION_JSON", "value": json.dumps(value)}]
                error = self.render("--set-json", "mcp.env=" + json.dumps(env), succeeds=False)
                self.assertIn("SPRING_APPLICATION_JSON must not override", error)

    def test_spring_json_preserves_bootstrap_profiles_and_custom_client_configuration(self):
        value = {"mcp": {"server": {"auth": {"bootstrap": {"profiles": []}, "apiKeys": [{"clientId": "test-client", "key": "ci-only-test-key"}]}}}}
        resources = self.render("--set-json", "mcp.env=" + json.dumps([{"name": "SPRING_APPLICATION_JSON", "value": json.dumps(value)}]))
        environment = env_values(self.mcp_container(resources))
        self.assertEqual(json.loads(environment["SPRING_APPLICATION_JSON"]), value)

    def test_malformed_or_non_object_json_is_rejected(self):
        for value in ("{broken", "[]", "null"):
            with self.subTest(value=value):
                self.render("--set-json", "mcp.env=" + json.dumps([{"name": "SPRING_APPLICATION_JSON", "value": value}]), succeeds=False)

    def test_jvm_property_overrides_cannot_bypass_chart_controls(self):
        for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
            for property_name in ("mcp.server.auth.jwt.revocation.backend", "JWT_REVOCATION_STORE_BACKEND", '"mcp.server.auth.jwt.enabled', "spring.application.json", "zap.report.directory", "ZAP_AUTOMATION_LOCAL_DIRECTORY"):
                with self.subTest(name=name, property_name=property_name):
                    env = [{"name": name, "value": "-D" + property_name + "=in-memory"}]
                    self.render("--set-json", "mcp.env=" + json.dumps(env), succeeds=False)
            env = [{"name": name, "value": "-Xmx512m -XX:+UseG1GC -Duser.timezone=UTC"}]
            self.render("--set-json", "mcp.env=" + json.dumps(env))

    def test_shared_workspace_cannot_be_silently_overridden(self):
        for name in ("ZAP_REPORT_DIRECTORY", "ZAP_AUTOMATION_LOCAL_DIRECTORY", "ZAP_AUTOMATION_ZAP_DIRECTORY"):
            with self.subTest(name=name):
                error = self.render("--set-json", "mcp.env=" + json.dumps([{"name": name, "value": "/different"}]), succeeds=False)
                self.assertIn("must not override shared workspace", error)
                env = [{"name": "SPRING_APPLICATION_JSON", "value": json.dumps({name: "/different"})}]
                error = self.render("--set-json", "mcp.env=" + json.dumps(env), succeeds=False)
                self.assertIn("must not override shared workspace", error)

    def test_shared_workspace_rejects_rwop_and_multi_replica_rwo(self):
        for arguments in (("zap.persistence.accessMode=ReadWriteOncePod",), ("mcp.replicaCount=2", "mcp.streamableHttp.sessionAffinity.enabled=true", "mcp.streamableHttp.sessionAffinity.provider=service-client-ip")):
            with self.subTest(arguments=arguments):
                self.render(*[part for argument in arguments for part in ("--set", argument)], succeeds=False)

    def test_affinity_provider_requires_its_exposure_route(self):
        for provider, expected in (("ingress-nginx", "ingress.enabled=true"), ("aws-nlb", "service.type=LoadBalancer")):
            with self.subTest(provider=provider):
                error = self.render("--set", "mcp.replicaCount=2", "--set", "zap.persistence.shareWithMcp=false", "--set", "mcp.streamableHttp.sessionAffinity.enabled=true", "--set", "mcp.streamableHttp.sessionAffinity.provider=" + provider, succeeds=False)
                self.assertIn(expected, error)

    def test_ingress_requires_tls(self):
        self.assertIn("MCP ingress requires TLS", self.render("--set", "mcp.ingress.enabled=true", succeeds=False))

    def test_nginx_cannot_disable_https_or_hash_on_new_session_id(self):
        for annotation, value in (("nginx.ingress.kubernetes.io/ssl-redirect", "false"), ("nginx.ingress.kubernetes.io/upstream-hash-by", "$http_mcp_session_id")):
            with self.subTest(annotation=annotation):
                self.render("--set-json", "mcp.ingress.annotations=" + json.dumps({annotation: value}), preset="values-ha.yaml", succeeds=False)

    def test_aws_tls_listener_cannot_claim_multi_replica_source_ip_affinity(self):
        error = self.render("--set", "mcp.replicaCount=2", "--set", "zap.persistence.shareWithMcp=false", "--set", "mcp.streamableHttp.sessionAffinity.enabled=true", "--set", "mcp.streamableHttp.sessionAffinity.provider=aws-nlb", "--set", "mcp.service.type=LoadBalancer", "--set-json", 'mcp.service.annotations={"service.beta.kubernetes.io/aws-load-balancer-ssl-cert":"arn:test"}', succeeds=False)
        self.assertIn("TLS listeners do not support source-IP stickiness", error)

    def test_custom_probes_keep_listener_liveness_separate_from_readiness_health(self):
        resources = self.render(
            "--set", "mcp.service.targetPort=8443",
            "--set", "mcp.livenessProbe.tcpSocket.port=8443",
            "--set", "mcp.readinessProbe.httpGet.port=8443",
            "--set-string", "mcp.readinessProbe.httpGet.path=/actuator/health/readiness")
        container = self.mcp_container(resources)
        self.assertEqual(container["livenessProbe"]["tcpSocket"], {"port": 8443})
        self.assertNotIn("httpGet", container["livenessProbe"])
        self.assertEqual(container["readinessProbe"]["httpGet"], {
            "port": 8443, "path": "/actuator/health/readiness"})
        self.assertEqual(env_values(container)["SERVER_PORT"], "8443")


if __name__ == "__main__":
    unittest.main()
