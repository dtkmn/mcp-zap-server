"""Offline regression fixtures for Snyk reporting, coverage and failure handling."""

import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import yaml


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("snyk_report", ROOT / "scripts/ci/snyk_report.py")
SNYK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SNYK)
WORKFLOW = yaml.load((ROOT / ".github/workflows/snyk.yml").read_text(), Loader=yaml.BaseLoader)


def graph_result(manager, project="project", packages=None):
    if packages is None:
        packages = [{"name": "example:library", "version": "1.0"}]
    return {
        "packageManager": manager, "projectName": project, "ok": True,
        "dependencyCount": len(packages), "vulnerabilities": [],
        "depGraph": {"schemaVersion": "1.2.0", "pkgManager": {"name": manager},
            "pkgs": [{"id": "root", "info": {"name": project, "version": "unknown"}}] +
                    [{"id": str(n), "info": package} for n, package in enumerate(packages)],
            "graph": {"rootNodeId": "root", "nodes": [
                {"nodeId": "root", "pkgId": "root", "deps": [{"nodeId": str(n)} for n in range(len(packages))]},
                *[{"nodeId": str(n), "pkgId": str(n), "deps": []} for n in range(len(packages))]]}},
    }


def sarif_run(driver, project="project", finding=False, index=0):
    family = {"Snyk Open Source": "Open Source", "Snyk Container": "Container", "Snyk IaC": "IaC"}[driver]
    return {
        "tool": {"driver": {"name": driver, "rules": [{"id": "EXAMPLE-1"}] if finding else []}},
        "automationDetails": {"id": f"Snyk/{family}/{project}/{index}/timestamp", "description": {"text": "fixture"}},
        "results": [{"ruleId": "EXAMPLE-1", "message": {"text": "Example finding"},
                     "partialFingerprints": {"primaryLocationLineHash": "preserve-me"},
                     "locations": [{"physicalLocation": {"artifactLocation": {"uri": "Dockerfile"}}}]}] if finding else [],
    }


class SnykReportingTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.output = self.root / "reports"
        self.calls, self.overrides = [], {}
        self.extra_manifest = ""
        self.archive = self.root / "image.jar"
        self.write_archive()
        for _, path, _, _ in SNYK.SOURCE_TARGETS:
            source = self.root / path
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text("module example\n" if path.endswith("go.mod") else "fixture")
        self.environment = patch.dict(os.environ, {"SNYK_ORG": "fixture-org", "SNYK_TOKEN": "fixture-secret"})
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def write_archive(self, filename="library-1.0.jar", metadata=None):
        nested = io.BytesIO()
        with zipfile.ZipFile(nested, "w") as jar:
            jar.writestr("example.class", "fixture")
            if metadata:
                jar.writestr("META-INF/maven/example/library/pom.properties", metadata)
        with zipfile.ZipFile(self.archive, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", "Main-Class: org.springframework.boot.loader.launch.JarLauncher\nStart-Class: example.Main\n")
            archive.writestr("BOOT-INF/lib/" + filename, nested.getvalue())

    def fake_run(self, command, **kwargs):
        self.calls.append(command)
        if command[0] == "git":
            names = [path.replace("package-lock.json", "package.json") for _, path, _, _ in SNYK.SOURCE_TARGETS]
            return subprocess.CompletedProcess(command, 0, "\0".join(names + [self.extra_manifest]))
        if command[0] == "docker":
            if command[1] == "create":
                return subprocess.CompletedProcess(command, 0, "a" * 64 + "\n")
            if command[1] == "cp":
                Path(command[-1]).write_bytes(self.archive.read_bytes())
            return subprocess.CompletedProcess(command, 0, "")
        if "monitor" in command:
            return subprocess.CompletedProcess(command, self.overrides.get("monitor_exit", 0), "")
        target = next((arg.removeprefix("--file=") for arg in command if arg.startswith("--file=")), "iac")
        if command[1] == "container":
            data = graph_result("deb", "docker-image|mcp-zap-server")
            data["applications"] = [graph_result("maven", "mcp-zap-server:snyk-scan:/app"),
                graph_result("gomodules", "github.com/dtkmn/mcp-zap-server/tools/healthcheck")]
            runs = [sarif_run("Snyk Container", name, finding=n == 0, index=n)
                    for n, name in enumerate(SNYK.CONTAINER_PROJECTS)]
            target = "container"
        elif command[1] == "iac":
            data = [{"ok": True, "targetFile": "snyk-rendered-helm/templates/deployment.yaml", "infrastructureAsCodeIssues": []}]
            runs = [sarif_run("Snyk IaC")]
        else:
            manager = next(manager for _, path, manager, _ in SNYK.SOURCE_TARGETS if path == target)
            data = graph_result(manager)
            runs = [sarif_run("Snyk Open Source", finding=target.startswith("docs/"))]
        override = self.overrides.get(target, {})
        data = copy.deepcopy(override.get("json", data))
        report = copy.deepcopy(override.get("sarif", {"version": "2.1.0", "runs": runs}))
        if not override.get("no_output"):
            Path(next(arg.split("=", 1)[1] for arg in command if arg.startswith("--json-file-output="))).write_text(json.dumps(data))
            Path(next(arg.split("=", 1)[1] for arg in command if arg.startswith("--sarif-file-output="))).write_text(json.dumps(report))
        return subprocess.CompletedProcess(command, override.get("exit", 0), "fixture scan completed\n")

    def execute(self, kind="source"):
        reports = SNYK.Reports(self.root, self.output)
        with patch.object(SNYK.subprocess, "run", side_effect=self.fake_run):
            if kind == "source":
                SNYK.source_reports(reports)
            elif kind == "container":
                SNYK.container_reports(reports, "mcp-zap-server:snyk-scan")
            else:
                SNYK.iac_reports(reports)
        return reports, reports.finish()

    def application_marker_data(self, additional=None):
        data = graph_result("deb", "docker-image|mcp-zap-server")
        java = graph_result("maven", "mcp-zap-server:snyk-scan:/app", packages=[
            {"name": "example:library", "version": "1.0"},
            {"name": "unknown:/app/app.jar", "version": "unknown"}, *(additional or [])])
        java["targetFile"] = "/app"
        java["depGraph"]["graph"]["nodes"][2]["info"] = {"labels": {
            "location": "/app/app.jar", "digest": "a" * 40}}
        data["applications"] = [java, graph_result("gomodules", "github.com/dtkmn/mcp-zap-server/tools/healthcheck")]
        return data

    def test_five_explicit_source_targets_preserve_default_and_compile_only_scope(self):
        reports, result = self.execute()
        self.assertEqual(result, 0, reports.errors)
        commands = [call for call in self.calls if call[0] == "snyk"]
        self.assertEqual(len(commands), 5)
        self.assertNotIn("--configuration-matching=^runtimeClasspath$", commands[0])
        self.assertIn("--configuration-matching=^compileClasspath$", commands[1])
        for command in commands:
            self.assertIn("--print-deps", command)
            self.assertIn("--severity-threshold=high", command)
            self.assertNotIn("fixture-secret", " ".join(command))
        self.assertIn("--dev", commands[2])
        self.assertIn("--dev", commands[3])
        self.assertEqual(len({run["automationDetails"]["id"] for run in reports.runs}), 5)

    def test_finding_exit_preserves_payload_and_fingerprints(self):
        original = sarif_run("Snyk Open Source", finding=True, index=42)
        self.overrides["docs/package-lock.json"] = {"exit": 1, "sarif": {"version": "2.1.0", "runs": [original]}}
        reports, result = self.execute()
        self.assertEqual(result, 0)
        normalized = reports.runs[2]
        self.assertEqual(normalized["automationDetails"]["id"], "snyk-open-source/docs/package-lock.json/")
        expected = copy.deepcopy(original)
        expected["automationDetails"]["id"] = normalized["automationDetails"]["id"]
        self.assertEqual(normalized, expected)

    def test_operational_exits_and_missing_output_fail_without_losing_sibling_findings(self):
        for code in (2, 3, 127):
            with self.subTest(code=code):
                self.calls.clear()
                self.overrides["build.gradle"] = {"exit": code, "no_output": True}
                reports, result = self.execute()
                self.assertEqual(result, 1)
                self.assertEqual(len([call for call in self.calls if call[0] == "snyk"]), 5)
                self.assertTrue(any(run["results"] for run in reports.runs))
                self.assertEqual(len(reports.runs), 4)

    def test_new_manifest_is_visible_and_existing_findings_still_upload(self):
        self.extra_manifest = "new-module/build.gradle.kts"
        reports, result = self.execute()
        self.assertEqual(result, 1)
        self.assertTrue(any(self.extra_manifest in error for error in reports.errors))
        self.assertEqual(len(reports.runs), 5)

    def test_missing_graph_or_unknown_source_package_fails(self):
        data = graph_result("gradle")
        for invalid in ({key: value for key, value in data.items() if key != "depGraph"},
                        graph_result("gradle", packages=[{"name": "unknown-lib", "version": "unknown"}])):
            with self.subTest(invalid=invalid):
                self.overrides["build.gradle"] = {"json": invalid}
                reports, result = self.execute()
                self.assertEqual(result, 1)
                self.assertEqual(len(reports.runs), 5)

    def test_orphan_graph_packages_cannot_inflate_dependency_coverage(self):
        data = graph_result("gradle")
        data["depGraph"]["pkgs"].append({"id": "orphan", "info": {"name": "example:other", "version": "1.0"}})
        data["dependencyCount"] += 1
        with self.assertRaisesRegex(ValueError, "outside its dependency nodes"):
            SNYK.dependency_packages(data, "gradle")

    def test_empty_compile_graph_fails_but_standard_library_only_go_is_explicit(self):
        self.overrides["build.gradle"] = {"json": graph_result("gradle", packages=[])}
        self.assertEqual(self.execute()[1], 1)
        self.overrides.clear()
        self.overrides["tools/healthcheck/go.mod"] = {"json": graph_result("gomodules", packages=[])}
        self.assertEqual(self.execute()[1], 0)
        (self.root / "tools/healthcheck/go.mod").write_text("module example\nrequire example.org/module v1.0.0\n")
        self.assertEqual(self.execute()[1], 1)

    def test_malformed_sarif_does_not_abort_later_source_targets(self):
        for malformed in ([], {"version": "2.1.0", "runs": [None]}, {"version": "2.1.0", "runs": [{"tool": None}]}):
            with self.subTest(malformed=malformed):
                self.overrides["build.gradle"] = {"sarif": malformed}
                reports, result = self.execute()
                self.assertEqual(result, 1)
                self.assertEqual(len(reports.runs), 4)

    def test_stale_files_are_removed_before_failed_scan(self):
        self.output.mkdir()
        for name in ("report.sarif", "gradle-main.sarif", "gradle-main.json"):
            (self.output / name).write_text("stale")
        self.overrides["build.gradle"] = {"no_output": True, "exit": 2}
        reports, result = self.execute()
        self.assertEqual(result, 1)
        self.assertFalse((self.output / "gradle-main.sarif").exists())
        self.assertEqual(len(json.loads((self.output / "report.sarif").read_text())["runs"]), 4)

    def test_missing_executable_is_captured_as_operational_failure(self):
        reports = SNYK.Reports(self.root, self.output)
        with patch.object(SNYK.subprocess, "run", side_effect=FileNotFoundError("fixture missing executable")):
            self.assertEqual(reports.command(["missing-snyk"], "missing").returncode, 127)

    def test_container_maps_actual_archive_and_uses_all_three_layers(self):
        reports, result = self.execute("container")
        self.assertEqual(result, 0, reports.errors)
        self.assertEqual({run["automationDetails"]["id"] for run in reports.runs}, {
            "snyk-container/os/", "snyk-container/application-java/", "snyk-container/healthcheck-go/"})
        self.assertTrue((self.output / "runtime-coverage.json").exists())
        self.assertEqual([call[1] for call in self.calls if call[0] == "docker"], ["create", "cp", "rm"])
        self.assertFalse(any("start" in call or "run" in call for call in self.calls if call[0] == "docker"))

    def test_classifier_archives_are_mapped_without_counts_only_assumptions(self):
        self.write_archive("library-1.0-linux-x86_64.jar", "groupId=example\nartifactId=library\nversion=1.0\n")
        reports, result = self.execute("container")
        self.assertEqual(result, 0, reports.errors)
        self.write_archive("other-library-1.0.jar")
        self.assertEqual(self.execute("container")[1], 1)

    def test_metadata_conflicts_and_ambiguous_coordinates_fail(self):
        inventory = {"libraries": ["library-1.0.jar"], "coordinates": {"library-1.0.jar": {"name": "expected:library", "version": "1.0"}}}
        with self.assertRaises(ValueError):
            SNYK.validate_archive_coverage(inventory, [{"name": "other:library", "version": "1.0"}])
        with self.assertRaises(ValueError):
            SNYK.validate_archive_coverage({"libraries": ["library-1.0.jar"]}, [
                {"name": "first:library", "version": "1.0"}, {"name": "second:library", "version": "1.0"}])

    def test_unknown_dependency_is_not_hidden_by_matching_dependency_count(self):
        with self.assertRaisesRegex(ValueError, "unidentified application package"):
            SNYK.validate_archive_coverage({"libraries": ["library-1.0.jar"]}, [{"name": "app.jar", "version": "unknown"}])

    def test_validated_outer_application_is_classified_without_changing_reports(self):
        data = self.application_marker_data()
        self.overrides["container"] = {"json": data}
        reports, result = self.execute("container")
        self.assertEqual(result, 0, reports.errors)
        self.assertEqual(reports.checks[-1]["application_archive"], {
            "name": "unknown:/app/app.jar", "path": "/app/app.jar"})
        self.assertEqual(json.loads((self.output / "container.json").read_text()), data)
        self.assertEqual(json.loads((self.output / "runtime-coverage.json").read_text()), {
            "library-1.0.jar": "example:library@1.0"})
        self.assertTrue(any(run["results"] for run in reports.runs))

    def test_outer_marker_does_not_exempt_other_unknowns_or_missing_libraries(self):
        cases = []
        for path in ("/app/other.jar", "/app/app.jar/BOOT-INF/lib/nested.jar"):
            data = self.application_marker_data()
            java = data["applications"][0]
            java["depGraph"]["pkgs"][2]["info"]["name"] = "unknown:" + path
            java["depGraph"]["graph"]["nodes"][2]["info"]["labels"]["location"] = path
            cases.append(data)
        cases.append(self.application_marker_data([{"name": "example:unidentified", "version": "unknown"}]))
        missing = self.application_marker_data()
        missing["applications"][0]["depGraph"]["pkgs"][1]["info"]["name"] = "example:other-library"
        cases.append(missing)
        for data in cases:
            with self.subTest(data=data):
                self.overrides["container"] = {"json": data}
                reports, result = self.execute("container")
                self.assertEqual(result, 1)
                self.assertEqual(len(reports.runs), 3)

    def test_outer_marker_requires_image_inventory_proof(self):
        self.overrides["container"] = {"json": self.application_marker_data()}
        valid = {"path": "/app/app.jar", "sha256": "a" * 64, "libraries": ["library-1.0.jar"]}
        for field, value in (("path", "/app/other.jar"), ("sha256", None),
                             ("sha256", "invalid"), ("libraries", [])):
            with self.subTest(field=field, value=value):
                inventory = valid | {field: value}
                with patch.object(SNYK, "image_inventory", return_value=inventory):
                    reports, result = self.execute("container")
                self.assertEqual(result, 1)
                self.assertEqual(len(reports.runs), 3)
        with patch.object(SNYK, "image_inventory", return_value=None):
            self.assertEqual(self.execute("container")[1], 1)

    def test_outer_marker_requires_unique_leaf_node_with_exact_root_and_location(self):
        cases = []
        for location in (None, "/app/other.jar", "/app/app.jar/BOOT-INF/lib/nested.jar"):
            data = self.application_marker_data()
            data["applications"][0]["depGraph"]["graph"]["nodes"][2]["info"]["labels"]["location"] = location
            cases.append(data)
        nonleaf = self.application_marker_data()
        nonleaf["applications"][0]["depGraph"]["graph"]["nodes"][2]["deps"] = [{"nodeId": "0"}]
        cases.append(nonleaf)
        indirect = self.application_marker_data()
        nodes = indirect["applications"][0]["depGraph"]["graph"]["nodes"]
        nodes[0]["deps"] = [{"nodeId": "0"}]
        nodes[1]["deps"] = [{"nodeId": "1"}]
        cases.append(indirect)
        duplicate = self.application_marker_data([{"name": "unknown:/app/app.jar", "version": "unknown"}])
        cases.append(duplicate)
        wrong_target = self.application_marker_data()
        wrong_target["applications"][0]["targetFile"] = "/another-directory"
        cases.append(wrong_target)
        missing_label = self.application_marker_data()
        del missing_label["applications"][0]["depGraph"]["graph"]["nodes"][2]["info"]
        cases.append(missing_label)
        for data in cases:
            with self.subTest(data=data):
                self.overrides["container"] = {"json": data}
                reports, result = self.execute("container")
                self.assertEqual(result, 1)
                self.assertEqual(len(reports.runs), 3)

    def test_missing_or_unknown_os_dependencies_fail_and_keep_findings(self):
        for packages in ([], [{"name": "libc", "version": "unknown"}]):
            with self.subTest(packages=packages):
                data = graph_result("deb", "docker-image|mcp-zap-server", packages=packages)
                data["applications"] = [graph_result("maven", "mcp-zap-server:snyk-scan:/app"),
                    graph_result("gomodules", "github.com/dtkmn/mcp-zap-server/tools/healthcheck")]
                self.overrides["container"] = {"json": data}
                reports, result = self.execute("container")
                self.assertEqual(result, 1)
                self.assertTrue(any(run["results"] for run in reports.runs))

    def test_unknown_container_sarif_layer_keeps_findings_and_native_identity(self):
        unknown = sarif_run("Snyk Container", "new-layer", finding=True, index=99)
        known = [sarif_run("Snyk Container", name, index=n) for n, name in enumerate(SNYK.CONTAINER_PROJECTS)]
        self.overrides["container"] = {"sarif": {"version": "2.1.0", "runs": [unknown, *reversed(known)]}}
        reports, result = self.execute("container")
        self.assertEqual(result, 1)
        self.assertEqual(reports.runs[0], unknown)
        self.assertEqual(len(reports.runs), 4)

    def test_malformed_container_identity_retains_valid_sibling_findings(self):
        valid = sarif_run("Snyk Container", "docker-image|mcp-zap-server", finding=True)
        for identity in (None, 123, []):
            with self.subTest(identity=identity):
                invalid = sarif_run("Snyk Container", "invalid")
                invalid["automationDetails"]["id"] = identity
                self.overrides["container"] = {"sarif": {"version": "2.1.0", "runs": [invalid, valid]}}
                reports, result = self.execute("container")
                self.assertEqual(result, 1)
                self.assertEqual(len(reports.runs), 1)
                self.assertEqual(reports.runs[0]["results"], valid["results"])
        invalid = sarif_run("Snyk Container")
        invalid["tool"]["driver"]["name"] = ["unexpected"]
        self.overrides["container"] = {"sarif": {"version": "2.1.0", "runs": [invalid, valid]}}
        self.assertEqual(self.execute("container")[1], 1)

    def test_malformed_archive_metadata_still_scans_and_preserves_findings(self):
        for bom in (None, [], {"components": None}, {"components": [None]}):
            with self.subTest(bom=bom):
                self.write_archive()
                with zipfile.ZipFile(self.archive, "a") as archive:
                    archive.writestr("META-INF/sbom/application.cdx.json", json.dumps(bom))
                reports, result = self.execute("container")
                self.assertEqual(result, 1)
                self.assertEqual(len(reports.runs), 3)
                self.assertTrue(any(run["results"] for run in reports.runs))
                self.assertTrue(any("runtime archive inventory failed" in error for error in reports.errors))

    def test_iac_findings_are_allowed_but_partial_errors_fail(self):
        self.overrides["iac"] = {"exit": 1, "json": [{"ok": False, "targetFile": "deployment.yaml", "infrastructureAsCodeIssues": [{"id": "EXAMPLE-IAC"}]}]}
        reports, result = self.execute("iac")
        self.assertEqual(result, 0, reports.errors)
        self.assertNotIn("--print-deps", self.calls[-1])
        for data in ([{"ok": False, "error": "parse failed"}], [{}], None):
            self.overrides["iac"] = {"json": data}
            self.assertEqual(self.execute("iac")[1], 1)

    def test_workflow_preserves_events_guards_and_always_uploads(self):
        self.assertEqual(WORKFLOW["on"]["push"]["branches"], ["main"])
        self.assertEqual(WORKFLOW["on"]["pull_request"]["branches"], ["main", "dev"])
        self.assertEqual(WORKFLOW["env"]["SNYK_SEVERITY_THRESHOLD"], "high")
        for job in WORKFLOW["jobs"].values():
            self.assertIn("github.actor != 'dependabot[bot]'", job["if"])
            self.assertIn("github.event.pull_request.head.repo.full_name == github.repository", job["if"])
            for step in job["steps"]:
                self.assertNotEqual(step.get("continue-on-error"), "true")
                if "upload-sarif@" in step.get("uses", ""):
                    self.assertIn("always()", step["if"])
                if "Monitor" in step.get("name", ""):
                    self.assertEqual(step["if"], "github.event_name != 'pull_request'")


if __name__ == "__main__":
    unittest.main()
