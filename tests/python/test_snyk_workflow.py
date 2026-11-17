"""Offline regression fixtures for Snyk reporting, coverage and failure handling."""

import copy
import hashlib
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
GATEWAY_MODULES = ("mcp-gateway-core", "mcp-gateway-spring-webflux")
PACKAGED_VERSION = "4.2.1"
SCANNER_VERSION = "4.2.0"


def central_path(module, version):
    return f"/maven2/io/github/dtkmn/{module}/{version}/{module}-{version}.jar"


class CentralResponse(io.BytesIO):
    def __init__(self, status, body):
        super().__init__(body)
        self.status = status
        self.headers = {"Content-Length": str(len(body))}

    def getheader(self, name):
        return self.headers.get(name)


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
        self.published_jars = {}
        central = patch.object(SNYK, "HTTPSConnection")
        self.central = central.start()
        self.addCleanup(central.stop)
        self.central.return_value.getresponse.side_effect = self.central_response

    def central_response(self):
        method, path = self.central.return_value.request.call_args.args
        self.assertEqual(method, "GET")
        if path in self.published_jars:
            return CentralResponse(200, self.published_jars[path])
        return CentralResponse(404, b"")

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

    def gateway_inputs(self):
        source_packages = [{"name": "io.github.dtkmn:" + module, "version": PACKAGED_VERSION}
                           for module in GATEWAY_MODULES]
        source = graph_result("gradle", "mcp-zap-server", source_packages)
        source["displayTargetFile"] = "build.gradle"
        directory = self.root / "source"
        directory.mkdir(exist_ok=True)
        (directory / "gradle-main.json").write_text(json.dumps(source))
        (directory / "coverage.json").write_text(json.dumps({
            "complete": True, "errors": [], "checks": [
                {"target": "gradle-main", "dependencies": len(source_packages), "exit_code": 1}]}))

        java = graph_result("maven", "mcp-zap-server:snyk-scan:/app",
                            [package | {"version": SCANNER_VERSION} for package in source_packages] +
                            [{"name": "example:library", "version": "1.0"}])
        self.write_archive()
        with zipfile.ZipFile(self.archive, "a") as archive:
            components = []
            for node, module in zip(java["depGraph"]["graph"]["nodes"][1:], GATEWAY_MODULES):
                nested = io.BytesIO()
                with zipfile.ZipFile(nested, "w") as jar:
                    jar.writestr("fixture.class", module)
                content = nested.getvalue()
                for version in (PACKAGED_VERSION, SCANNER_VERSION):
                    self.published_jars[central_path(module, version)] = content
                path = f"BOOT-INF/lib/{module}-{PACKAGED_VERSION}.jar"
                archive.writestr(path, content)
                components.append({"group": "io.github.dtkmn", "name": module, "version": PACKAGED_VERSION})
                node["info"] = {"labels": {
                    "location": "/app/app.jar/" + path,
                    "digest": hashlib.sha1(content, usedforsecurity=False).hexdigest()}}
            archive.writestr("META-INF/sbom/application.cdx.json", json.dumps({"components": components}))
        data = graph_result("deb", "docker-image|mcp-zap-server")
        data["applications"] = [java, graph_result("gomodules", "github.com/dtkmn/mcp-zap-server/tools/healthcheck")]
        self.overrides["container"] = {"json": data, "exit": 1}
        return SNYK.archive_inventory(self.archive), java, directory

    def test_gateway_fingerprints_record_both_versions_and_preserve_native_findings(self):
        inventory, _, _ = self.gateway_inputs()
        reports, result = self.execute("container")
        self.assertEqual(result, 0, reports.errors)
        for connection in self.central.call_args_list:
            self.assertEqual(connection.args, ("repo.maven.apache.org",))
            self.assertGreater(connection.kwargs["timeout"], 0)
        self.assertCountEqual([call.args[1] for call in self.central.return_value.request.call_args_list],
                              list(self.published_jars))
        matches = json.loads((self.output / "runtime-coverage.json").read_text())
        evidence = reports.checks[-1]["gateway_fingerprints"]
        self.assertEqual(matches.pop("library-1.0.jar"), "example:library@1.0")
        self.assertEqual(set(matches), set(inventory["coordinates"]))
        self.assertEqual(set(evidence), set(matches))
        for filename, package in inventory["coordinates"].items():
            self.assertEqual(matches[filename], package["name"] + "@" + SCANNER_VERSION)
            self.assertEqual(evidence[filename], {
                "source": package["name"] + "@" + PACKAGED_VERSION, "container": matches[filename]})
        native = json.loads((self.output / "container.sarif").read_text())["runs"]
        self.assertTrue(any(run["results"] for run in native))
        self.assertEqual([r["results"] for r in reports.runs], [r["results"] for r in native])

    def test_exact_gateway_scanner_versions_use_the_standard_mapping_without_source_evidence(self):
        inventory, java, directory = self.gateway_inputs()
        (directory / "gradle-main.json").unlink()
        for package in java["depGraph"]["pkgs"]:
            if package["info"]["name"].startswith("io.github.dtkmn:"):
                package["info"]["version"] = PACKAGED_VERSION
        reports, result = self.execute("container")
        self.assertEqual(result, 0, reports.errors)
        self.central.assert_not_called()
        self.assertNotIn("gateway_fingerprints", reports.checks[-1])
        matches = json.loads((self.output / "runtime-coverage.json").read_text())
        for filename, package in inventory["coordinates"].items():
            self.assertEqual(matches[filename], package["name"] + "@" + PACKAGED_VERSION)

    def test_changed_gateway_jar_fails_even_with_exact_source_and_archive_coordinates(self):
        expected_inventory, _, _ = self.gateway_inputs()
        artifact = "mcp-gateway-core"
        filename = f"{artifact}-{PACKAGED_VERSION}.jar"
        self.write_archive(filename, f"groupId=io.github.dtkmn\nartifactId={artifact}\nversion={PACKAGED_VERSION}\n")
        with zipfile.ZipFile(self.archive) as archive:
            digest = hashlib.sha256(archive.read("BOOT-INF/lib/" + filename)).hexdigest()
        reports, result = self.execute("container")
        self.assertEqual(result, 1)
        inventory = json.loads((self.output / "image-inventory.json").read_text())
        self.assertEqual(inventory["library_sha256"][filename], digest)
        self.assertNotEqual(digest, expected_inventory["library_sha256"][filename])
        self.assertTrue(any(filename in message for message in reports.errors))
        self.assertTrue(any(run["results"] for run in reports.runs))

    def test_both_published_gateway_versions_must_match_the_packaged_bytes(self):
        for version in (PACKAGED_VERSION, SCANNER_VERSION):
            with self.subTest(version=version):
                self.gateway_inputs()
                self.published_jars[central_path("mcp-gateway-core", version)] = b"different published bytes"
                reports, status = self.execute("container")
                self.assertEqual(status, 1)
                self.assertTrue(any(run["results"] for run in reports.runs))

    def test_maven_central_failure_preserves_container_findings(self):
        self.gateway_inputs()
        self.central.return_value.getresponse.side_effect = TimeoutError("fixture timeout")
        reports, status = self.execute("container")
        self.assertEqual(status, 1)
        self.assertTrue(any("Maven Central" in error for error in reports.errors))
        self.assertTrue(any(run["results"] for run in reports.runs))

    def test_only_fixed_gateway_modules_and_release_versions_can_contact_maven_central(self):
        module = "io.github.dtkmn:mcp-gateway-core"
        for name, version in ((module, "../4.2.1"), (module, "4.2.1?other"), (module, "4.2.1-SNAPSHOT"),
                              ("elsewhere:mcp-gateway-core", PACKAGED_VERSION),
                              ("io.github.dtkmn:other", PACKAGED_VERSION)):
            with self.subTest(name=name, version=version), self.assertRaises(ValueError):
                SNYK.published_gateway_hashes(name, version)
        self.central.assert_not_called()

    def test_download_rejects_redirects_and_incomplete_or_oversized_responses(self):
        redirect = CentralResponse(302, b"")
        redirect.headers["Location"] = "https://untrusted.invalid/jar"
        truncated = CentralResponse(200, b"short")
        truncated.headers["Content-Length"] = "7"
        for response in (redirect, truncated, CentralResponse(200, b"oversized")):
            with self.subTest(response=response), patch.object(SNYK, "MAX_GATEWAY_JAR_BYTES", 8):
                self.central.reset_mock()
                self.central.return_value.getresponse.side_effect = [response]
                with self.assertRaises(ValueError):
                    SNYK.published_gateway_hashes("io.github.dtkmn:mcp-gateway-core", PACKAGED_VERSION)
                self.assertEqual(self.central.call_count, 1)

    def test_gateway_fingerprints_require_completed_source_evidence_for_each_exact_version(self):
        for case in ("missing-file", "partial", "different-project", "wrong-version", "unreachable"):
            with self.subTest(case=case):
                inventory, _, directory = self.gateway_inputs()
                graph_file = directory / "gradle-main.json"
                source = json.loads(graph_file.read_text())
                if case == "missing-file":
                    graph_file.unlink()
                elif case == "partial":
                    path = directory / "coverage.json"
                    path.write_text(json.dumps(json.loads(path.read_text()) | {"complete": False}))
                else:
                    if case == "different-project":
                        source["projectName"] = "other-project"
                    elif case == "wrong-version":
                        source["depGraph"]["pkgs"][2]["info"]["version"] = SCANNER_VERSION
                    elif case == "unreachable":
                        source["depGraph"]["graph"]["nodes"][0]["deps"].pop()
                    graph_file.write_text(json.dumps(source))
                with patch.object(SNYK, "image_inventory", return_value=inventory):
                    reports, result = self.execute("container")
                self.assertEqual(result, 1)
                self.assertTrue(any(run["results"] for run in reports.runs))

    def test_gateway_fingerprint_cannot_cover_another_or_ambiguous_library(self):
        for case in ("unpublished-version", "wrong-location", "duplicate-location", "shared-location",
                     "conflicting-scanner-digest", "wrong-group", "unrelated-jar"):
            with self.subTest(case=case):
                inventory, java, _ = self.gateway_inputs()
                nodes = java["depGraph"]["graph"]["nodes"]
                if case == "unpublished-version":
                    java["depGraph"]["pkgs"][1]["info"]["version"] = "4.1.9"
                elif case == "wrong-location":
                    nodes[1]["info"]["labels"]["location"] = nodes[2]["info"]["labels"]["location"]
                elif case == "duplicate-location":
                    nodes.append(nodes[1] | {"nodeId": "duplicate"})
                    nodes[0]["deps"].append({"nodeId": "duplicate"})
                elif case == "shared-location":
                    nodes[3]["info"] = copy.deepcopy(nodes[1]["info"])
                elif case == "conflicting-scanner-digest":
                    nodes[1]["info"]["labels"]["digest"] = "0" * 40
                elif case == "wrong-group":
                    inventory["coordinates"][f"mcp-gateway-core-{PACKAGED_VERSION}.jar"]["name"] = "other:mcp-gateway-core"
                elif case == "unrelated-jar":
                    inventory["libraries"][0] = "library-1.1.jar"
                with patch.object(SNYK, "image_inventory", return_value=inventory):
                    reports, result = self.execute("container")
                self.assertEqual(result, 1)
                self.assertTrue(reports.errors)

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

    def test_container_uses_same_run_source_artifact_and_still_scans_after_download_failure(self):
        job = WORKFLOW["jobs"]["container"]
        self.assertIn("open-source", job["needs"])
        self.assertIn("!cancelled()", job["if"])
        steps = job["steps"]
        artifact = next(step for step in steps if step.get("uses", "").startswith("actions/download-artifact@"))
        self.assertEqual(artifact["with"], {"name": "snyk-source-diagnostics", "path": "build/snyk-reports/source"})
        self.assertIn("needs.open-source.result == 'success'", artifact["if"])
        build = next(step for step in steps if step.get("id") == "image-build")
        scan = next(step for step in steps if "snyk_report.py container" in step.get("run", ""))
        self.assertLess(steps.index(build), steps.index(artifact))
        self.assertLess(steps.index(artifact), steps.index(scan))
        self.assertIn("!cancelled()", scan["if"])
        self.assertIn("steps.image-build.outcome == 'success'", scan["if"])


if __name__ == "__main__":
    unittest.main()
