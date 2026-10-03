from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


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
        return {
            match.group(1): document
            for document in result.stdout.split("\n---")
            if (match := re.search(r"# Source: mcp-zap-server/templates/([^\n]+)", document))
        }

    def annotation(self, document, name):
        match = re.search(r'^    "' + re.escape(name) + r'": (.+)$', document, re.MULTILINE)
        self.assertIsNotNone(match, f"Missing {name} annotation")
        return match.group(1).strip('"')

    def test_sql_hook_precedes_job_and_survives_until_job_mount(self):
        for upgrade in (False, True):
            with self.subTest(upgrade=upgrade):
                resources = self.render(*(["--is-upgrade"] if upgrade else []))
                sql = resources["db-migration-configmap.yaml"]
                job = resources["db-migration-job.yaml"]
                for resource in (sql, job):
                    self.assertEqual(
                        self.annotation(resource, "helm.sh/hook"),
                        "pre-install,pre-upgrade",
                    )
                self.assertLess(
                    int(self.annotation(sql, "helm.sh/hook-weight")),
                    int(self.annotation(job, "helm.sh/hook-weight")),
                )
                self.assertEqual(
                    self.annotation(sql, "helm.sh/hook-delete-policy"),
                    "before-hook-creation",
                )
                self.assertEqual(
                    self.annotation(job, "helm.sh/hook-delete-policy"),
                    "before-hook-creation,hook-succeeded",
                )
                sql_name = re.search(r"^  name: (.+)$", sql, re.MULTILINE).group(1)
                self.assertIn(f"            name: {sql_name}\n", job)

    def test_upgrade_sql_hook_includes_new_chart_migration(self):
        with tempfile.TemporaryDirectory() as directory:
            chart = Path(directory) / "chart"
            shutil.copytree(CHART, chart)
            new_migration = chart / "files/db/migration/V999__helm_test.sql"
            new_migration.write_text("SELECT 'new-upgrade-sql';\n", encoding="utf-8")
            sql = self.render("--is-upgrade", chart=chart)["db-migration-configmap.yaml"]
            self.assertEqual(self.annotation(sql, "helm.sh/hook"), "pre-install,pre-upgrade")
            self.assertIn("V999__helm_test.sql:", sql)
            self.assertIn("SELECT 'new-upgrade-sql';", sql)

    def test_hook_does_not_depend_on_chart_service_account(self):
        job = self.render(
            "--set-string", "serviceAccount.name=created-after-hooks",
            "--set", "serviceAccount.automountServiceAccountToken=true",
        )["db-migration-job.yaml"]
        self.assertIn("      automountServiceAccountToken: false\n", job)
        self.assertNotIn("serviceAccountName:", job)
        self.assertIn("        runAsNonRoot: true\n", job)
        self.assertIn("            allowPrivilegeEscalation: false\n", job)
        self.assertIn("              drop:\n              - ALL\n", job)
        self.assertIn("              type: RuntimeDefault\n", job)

    def test_hook_preserves_credentials_options_and_resource_configuration(self):
        job = self.render(
            "--set-string", "migrations.postgres.existingSecret.usernameKey=migration-user",
            "--set-string", "migrations.postgres.existingSecret.passwordKey=migration-password",
            "--set", "migrations.connectRetries=7",
            "--set", "migrations.validateOnMigrate=false",
            "--set", "migrations.baselineOnMigrate=true",
            "--set-string", "migrations.resources.requests.cpu=125m",
            "--set-string", "migrations.resources.limits.memory=768Mi",
        )["db-migration-job.yaml"]
        self.assertIn("- -url=jdbc:postgresql://database:5432/mcp\n", job)
        self.assertIn("- -user=$(DB_MIGRATION_USERNAME)\n", job)
        self.assertIn("- -password=$(DB_MIGRATION_PASSWORD)\n", job)
        for option in ("connectRetries=7", "validateOnMigrate=false", "baselineOnMigrate=true"):
            self.assertIn(f"- -{option}\n", job)
        self.assertEqual(job.count("name: migration-credentials\n"), 2)
        self.assertIn("key: migration-user\n", job)
        self.assertIn("key: migration-password\n", job)
        self.assertIn("cpu: 125m\n", job)
        self.assertIn("memory: 768Mi\n", job)

    def test_disabled_migrations_render_neither_hook(self):
        resources = self.render(migrations=False)
        self.assertNotIn("db-migration-configmap.yaml", resources)
        self.assertNotIn("db-migration-job.yaml", resources)


if __name__ == "__main__":
    unittest.main()
