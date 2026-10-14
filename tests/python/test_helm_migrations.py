from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from test_helm_security import env_values, find_resource, parse_manifests


ROOT = Path(__file__).resolve().parents[2]
CHART = ROOT / "helm" / "mcp-zap-server"
HELM = shutil.which("helm")
MIGRATION_SETTINGS = [
    "--set", "migrations.enabled=true",
    "--set-string", "migrations.postgres.url=jdbc:postgresql://database:5432/mcp",
    "--set-string", "migrations.postgres.existingSecret.name=migration-credentials",
]


@unittest.skipUnless(HELM, "Helm is required for chart rendering tests")
class HelmMigrationTest(unittest.TestCase):
    def render(self, *arguments, chart=CHART, migrations=True):
        result = subprocess.run(
            [
                HELM, "template", "migration-test", str(chart),
                "--values", str(CHART / "values-ci.yaml"),
                *(MIGRATION_SETTINGS if migrations else []),
                *arguments,
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
            timeout=30,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        return parse_manifests(result.stdout)

    def sql_hook(self, resources):
        hooks = [resource for resource in resources
                 if "helm.sh/hook" in resource["metadata"].get("annotations", {})]
        return find_resource(hooks, "ConfigMap")

    def test_sql_hook_precedes_job_and_survives_until_job_mount(self):
        for upgrade in (False, True):
            with self.subTest(upgrade=upgrade):
                resources = self.render(*(["--is-upgrade"] if upgrade else []))
                sql = self.sql_hook(resources)
                job = find_resource(resources, "Job")
                for resource in (sql, job):
                    self.assertCountEqual(
                        resource["metadata"]["annotations"]["helm.sh/hook"].split(","),
                        ["pre-install", "pre-upgrade"],
                    )
                sql_annotations = sql["metadata"]["annotations"]
                job_annotations = job["metadata"]["annotations"]
                self.assertLess(
                    int(sql_annotations["helm.sh/hook-weight"]),
                    int(job_annotations["helm.sh/hook-weight"]),
                )
                self.assertCountEqual(
                    sql_annotations["helm.sh/hook-delete-policy"].split(","),
                    ["before-hook-creation"],
                )
                self.assertCountEqual(
                    job_annotations["helm.sh/hook-delete-policy"].split(","),
                    ["before-hook-creation", "hook-succeeded"],
                )
                pod = job["spec"]["template"]["spec"]
                volumes = {volume["name"]: volume for volume in pod["volumes"]}
                mount = next(mount for mount in pod["containers"][0]["volumeMounts"]
                             if mount["mountPath"] == "/flyway/sql")
                self.assertEqual(volumes[mount["name"]]["configMap"]["name"], sql["metadata"]["name"])

    def test_upgrade_sql_hook_includes_new_chart_migration(self):
        with tempfile.TemporaryDirectory() as directory:
            chart = Path(directory) / "chart"
            shutil.copytree(CHART, chart)
            new_migration = chart / "files/db/migration/V999__helm_test.sql"
            new_migration.write_text("SELECT 'new-upgrade-sql';\n", encoding="utf-8")
            sql = self.sql_hook(self.render("--is-upgrade", chart=chart))
            self.assertEqual(sql["data"]["V999__helm_test.sql"].strip(), "SELECT 'new-upgrade-sql';")

    def test_hook_does_not_depend_on_chart_service_account(self):
        job = find_resource(self.render(
            "--set-string", "serviceAccount.name=created-after-hooks",
            "--set", "serviceAccount.automountServiceAccountToken=true",
        ), "Job")
        pod = job["spec"]["template"]["spec"]
        self.assertIs(pod["automountServiceAccountToken"], False)
        self.assertNotIn("serviceAccountName", pod)
        self.assertIs(pod["securityContext"]["runAsNonRoot"], True)
        container_security = pod["containers"][0]["securityContext"]
        self.assertIs(container_security["allowPrivilegeEscalation"], False)
        self.assertIn("ALL", container_security["capabilities"]["drop"])
        self.assertEqual(container_security["seccompProfile"]["type"], "RuntimeDefault")

    def test_hook_binds_credentials_and_operator_migration_options(self):
        job = find_resource(self.render(
            "--set-string", "migrations.postgres.existingSecret.usernameKey=migration-user",
            "--set-string", "migrations.postgres.existingSecret.passwordKey=migration-password",
            "--set", "migrations.connectRetries=7",
            "--set", "migrations.validateOnMigrate=false",
            "--set", "migrations.baselineOnMigrate=true",
        ), "Job")
        container = job["spec"]["template"]["spec"]["containers"][0]
        self.assertIn("-url=jdbc:postgresql://database:5432/mcp", container["args"])
        self.assertIn("-user=$(DB_MIGRATION_USERNAME)", container["args"])
        self.assertIn("-password=$(DB_MIGRATION_PASSWORD)", container["args"])
        for option in ("connectRetries=7", "validateOnMigrate=false", "baselineOnMigrate=true"):
            self.assertIn(f"-{option}", container["args"])
        environment = env_values(container)
        for variable, key in (("DB_MIGRATION_USERNAME", "migration-user"),
                              ("DB_MIGRATION_PASSWORD", "migration-password")):
            self.assertEqual(environment[variable], {
                "secretKeyRef": {"name": "migration-credentials", "key": key}})

    def test_disabled_migrations_render_neither_hook(self):
        resources = self.render(migrations=False)
        self.assertFalse(any(resource["kind"] in {"ConfigMap", "Job"}
                             and "helm.sh/hook" in resource["metadata"].get("annotations", {})
                             for resource in resources))


if __name__ == "__main__":
    unittest.main()
