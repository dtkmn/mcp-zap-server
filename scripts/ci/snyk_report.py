#!/usr/bin/env python3
"""Run the configured Snyk checks with stable SARIF identities and coverage gates."""

import argparse
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile


SOURCE_TARGETS = (
    ("gradle-main", "build.gradle", "gradle", ()),
    ("gradle-extension", "examples/extensions/standalone-policy-metadata-extension/build.gradle",
     "gradle", ("--configuration-matching=^compileClasspath$",)),
    ("npm-docs", "docs/package-lock.json", "npm", ("--dev",)),
    ("npm-desktop", "packaging/claude-desktop/package-lock.json", "npm", ("--dev",)),
    ("go-healthcheck", "tools/healthcheck/go.mod", "gomodules", ()),
)
CONTAINER_PROJECTS = {
    "docker-image|mcp-zap-server": ("os", "deb"),
    "mcp-zap-server:snyk-scan:/app": ("application-java", "maven"),
    "github.com/dtkmn/mcp-zap-server/tools/healthcheck": ("healthcheck-go", "gomodules"),
}


def read_json(path):
    with path.open() as stream:
        return json.load(stream)


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


def usable_sarif_run(run):
    if not isinstance(run, dict) or not isinstance(run.get("results"), list):
        return False
    tool = run.get("tool")
    driver = tool.get("driver") if isinstance(tool, dict) else None
    if not isinstance(driver, dict) or not isinstance(driver.get("name"), str) or not driver["name"]:
        return False
    automation = run.get("automationDetails", {})
    return (isinstance(automation, dict) and
            ("id" not in automation or isinstance(automation["id"], str)) and
            all(isinstance(result, dict) for result in run["results"]))


def dependency_packages(result, manager):
    """Validate the emitted graph rather than treating a nonempty report as coverage."""
    if result.get("error") or result.get("packageManager") != manager:
        raise ValueError("missing or failed expected package-manager result")
    graph = result.get("depGraph", {})
    packages = graph.get("pkgs", [])
    nodes = graph.get("graph", {}).get("nodes", [])
    root = graph.get("graph", {}).get("rootNodeId")
    if not packages or not nodes or graph.get("pkgManager", {}).get("name") != manager:
        raise ValueError("dependency graph missing; require --print-deps and --json-file-output")
    package_ids = {package["id"] for package in packages}
    by_id = {node["nodeId"]: node for node in nodes}
    if len(package_ids) != len(packages) or len(by_id) != len(nodes) or root not in by_id:
        raise ValueError("dependency graph has missing or duplicate identities")
    for node in nodes:
        if node["pkgId"] not in package_ids or any(dep["nodeId"] not in by_id for dep in node.get("deps", [])):
            raise ValueError("dependency graph has dangling references")
    if package_ids != {node["pkgId"] for node in nodes}:
        raise ValueError("dependency graph contains packages outside its dependency nodes")
    reached, pending = set(), [root]
    while pending:
        node_id = pending.pop()
        if node_id not in reached:
            reached.add(node_id)
            pending.extend(dep["nodeId"] for dep in by_id[node_id].get("deps", []))
    if reached != set(by_id):
        raise ValueError("dependency graph contains unreachable dependencies")
    if result.get("dependencyCount") != len(packages) - 1:
        raise ValueError("dependency count does not agree with the emitted graph")
    root_package = by_id[root]["pkgId"]
    return [package["info"] for package in packages if package["id"] != root_package]


def source_manifest_gaps(root):
    tracked = subprocess.run(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
        cwd=root, check=True, capture_output=True, text=True,
    ).stdout.split("\0")
    manifests = {name for name in tracked if Path(name).name in {
        "build.gradle", "build.gradle.kts", "package.json", "go.mod",
    }}
    expected = {path.replace("package-lock.json", "package.json") for _, path, _, _ in SOURCE_TARGETS}
    return sorted(manifests ^ expected)


def archive_inventory(path):
    """Inventory the executable archive copied from the exact image under test."""
    with zipfile.ZipFile(path) as archive:
        manifest = archive.read("META-INF/MANIFEST.MF").decode()
        if "JarLauncher" not in manifest or "Start-Class:" not in manifest:
            raise ValueError("/app/app.jar is not the expected executable Spring Boot archive")
        libraries = sorted(name.removeprefix("BOOT-INF/lib/") for name in archive.namelist()
                           if name.startswith("BOOT-INF/lib/") and name.endswith(".jar"))
        if not libraries or len(libraries) != len(set(libraries)):
            raise ValueError("runtime archive has missing or duplicate nested library identities")
        coordinates = {}
        bom_path = "META-INF/sbom/application.cdx.json"
        bom = json.loads(archive.read(bom_path)) if bom_path in archive.namelist() else {}
        if not isinstance(bom, dict) or not isinstance(bom.get("components", []), list):
            raise ValueError("runtime archive contains malformed component inventory")
        components = bom.get("components", [])
        if any(not isinstance(component, dict) for component in components):
            raise ValueError("runtime archive contains malformed component metadata")
        for filename in libraries:
            candidates = set()
            with zipfile.ZipFile(io.BytesIO(archive.read("BOOT-INF/lib/" + filename))) as nested:
                for metadata in nested.namelist():
                    if metadata.startswith("META-INF/maven/") and metadata.endswith("/pom.properties"):
                        properties = dict(line.split("=", 1) for line in nested.read(metadata).decode().splitlines()
                                          if "=" in line and not line.startswith("#"))
                        group, name, version = (properties.get(key) for key in ("groupId", "artifactId", "version"))
                        if group and name and version and jar_matches(filename, name, version):
                            candidates.add((group + ":" + name, version))
            if not candidates:
                candidates = {(component["group"] + ":" + component["name"], component["version"])
                              for component in components if component.get("group") and component.get("name")
                              and component.get("version") and jar_matches(filename, component["name"], component["version"])}
            if len(candidates) > 1:
                raise ValueError(f"ambiguous archive metadata for runtime library: {filename}")
            if candidates:
                name, version = candidates.pop()
                coordinates[filename] = {"name": name, "version": version}
        return {"path": "/app/app.jar", "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                "libraries": libraries, "coordinates": coordinates}


def jar_matches(filename, artifact, version):
    basename = artifact + "-" + version
    return filename == basename + ".jar" or bool(re.fullmatch(re.escape(basename) + r"-[A-Za-z0-9_.-]+\.jar", filename))


def validate_archive_coverage(inventory, packages):
    """Match actual nested library names to unique scanner package coordinates."""
    candidates = []
    for package in packages:
        name, version = package.get("name", ""), package.get("version", "")
        if not name or not version or version == "unknown":
            raise ValueError(f"unidentified application package: {name or '<unnamed>'}")
        candidates.append((name, version))
    matched = {}
    for filename in inventory["libraries"]:
        known = inventory.get("coordinates", {}).get(filename)
        identities = [name + "@" + version for name, version in candidates
                      if jar_matches(filename, name.rsplit(":", 1)[-1], version)
                      and (not known or known == {"name": name, "version": version})]
        if len(identities) != 1:
            raise ValueError(f"runtime library has no unique scanner mapping: {filename}")
        matched[filename] = identities[0]
    return matched


def application_dependency_packages(result, inventory):
    """Separate the fingerprinted outer application from its bundled dependencies."""
    packages = dependency_packages(result, "maven")
    path = inventory.get("path") if isinstance(inventory, dict) else None
    marker = "unknown:" + path if isinstance(path, str) else None
    application = [package for package in packages
                   if package.get("name") == marker and package.get("version") == "unknown"]
    if not application:
        return packages, None
    if (len(application) != 1 or path != "/app/app.jar" or
            not isinstance(inventory.get("sha256"), str) or
            not re.fullmatch(r"[0-9a-f]{64}", inventory["sha256"]) or
            not isinstance(inventory.get("libraries"), list) or not inventory["libraries"] or
            result.get("projectName") != "mcp-zap-server:snyk-scan:/app" or
            result.get("targetFile") != "/app"):
        raise ValueError("unidentified outer application does not match the validated image archive")
    graph = result["depGraph"]
    package = [package for package in graph["pkgs"] if package["info"] == application[0]]
    nodes = [node for node in graph["graph"]["nodes"] if node["pkgId"] == package[0]["id"]]
    root = next(node for node in graph["graph"]["nodes"]
                if node["nodeId"] == graph["graph"]["rootNodeId"])
    if (len(package) != 1 or len(nodes) != 1 or nodes[0].get("deps") or
            nodes[0].get("info", {}).get("labels", {}).get("location") != path or
            sum(dep["nodeId"] == nodes[0]["nodeId"] for dep in root.get("deps", [])) != 1):
        raise ValueError("unidentified outer application lacks a unique archive-location fingerprint")
    return [package for package in packages if package is not application[0]], {"name": marker, "path": path}


class Reports:
    def __init__(self, root, output):
        self.root, self.output = root, output
        self.output.mkdir(parents=True, exist_ok=True)
        (self.output / "report.sarif").unlink(missing_ok=True)
        self.errors, self.runs, self.checks = [], [], []

    def error(self, message):
        self.errors.append(message)
        print(f"::error::{message}")

    def identify(self, run, driver, identity):
        if run["tool"]["driver"]["name"] != driver:
            self.error("unexpected SARIF tool; original finding identity will remain uploaded")
            return
        run.setdefault("automationDetails", {})["id"] = identity

    def command(self, command, name):
        try:
            result = subprocess.run(command, cwd=self.root, text=True, stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, check=False)
        except OSError as error:
            result = subprocess.CompletedProcess(command, 127, str(error) + "\n")
        (self.output / f"{name}.log").write_text(result.stdout)
        print(result.stdout, end="")
        return result

    def scan(self, name, command):
        sarif = self.output / f"{name}.sarif"
        report = self.output / f"{name}.json"
        sarif.unlink(missing_ok=True)
        report.unlink(missing_ok=True)
        result = self.command(command + [f"--org={os.environ['SNYK_ORG']}",
            f"--severity-threshold={os.environ.get('SNYK_SEVERITY_THRESHOLD', 'high')}",
            f"--sarif-file-output={sarif}", f"--json-file-output={report}"] +
            (["--print-deps"] if command[1] != "iac" else []), name)
        if result.returncode not in (0, 1):
            self.error(f"{name}: Snyk execution failed with exit {result.returncode}")
        elif result.returncode == 1:
            print(f"::warning::{name}: findings reported; see the uploaded Snyk results.")
        self.checks.append({"target": name, "exit_code": result.returncode})
        runs = []
        try:
            data = read_json(sarif)
            if not isinstance(data, dict) or data.get("version") != "2.1.0" or not isinstance(data.get("runs"), list) or not data["runs"]:
                raise ValueError("missing SARIF 2.1.0 runs")
            for run in data["runs"]:
                if not usable_sarif_run(run):
                    self.error(f"{name}: malformed SARIF run; valid sibling runs will still upload")
                else:
                    runs.append(copy.deepcopy(run))
        except (OSError, ValueError, TypeError, KeyError) as error:
            self.error(f"{name}: unusable SARIF: {error}")
        try:
            data = read_json(report)
        except (OSError, ValueError) as error:
            self.error(f"{name}: unusable JSON: {error}")
            data = None
        return data, runs

    def finish(self):
        if self.runs:
            write_json(self.output / "report.sarif", {"version": "2.1.0", "runs": self.runs})
        write_json(self.output / "coverage.json", {"checks": self.checks, "errors": self.errors,
                    "complete": not self.errors})
        if not self.errors:
            print("Snyk execution and configured dependency coverage completed; findings are reported separately.")
        return int(bool(self.errors))


def source_reports(reports):
    gaps = source_manifest_gaps(reports.root)
    if gaps:
        reports.error("Source manifests require an explicit scan mapping: " + ", ".join(gaps))
    for name, path, manager, options in SOURCE_TARGETS:
        data, runs = reports.scan(name, ["snyk", "test", f"--file={path}", *options])
        if len(runs) != 1:
            reports.error(f"{name}: expected one source SARIF run")
        else:
            reports.identify(runs[0], "Snyk Open Source", f"snyk-open-source/{path}/")
        reports.runs.extend(runs)
        try:
            if isinstance(data, list) and len(data) == 1:
                data = data[0]
            packages = dependency_packages(data, manager)
            if any(not package.get("name") or not package.get("version") or package["version"] == "unknown"
                   for package in packages):
                raise ValueError("one or more source dependency identities are unknown")
            if not packages:
                if manager != "gomodules" or re.search(r"^\s*require\b", (reports.root / path).read_text(), re.M):
                    raise ValueError("expected dependencies were not returned")
            reports.checks[-1]["dependencies"] = len(packages)
        except (AttributeError, KeyError, OSError, TypeError, ValueError) as error:
            reports.error(f"{name}: incomplete dependency coverage: {error}")


def image_inventory(reports, image):
    container = None
    with tempfile.TemporaryDirectory(prefix="snyk-image-inventory-") as temporary:
        try:
            result = reports.command(["docker", "create", image], "image-create")
            if result.returncode:
                raise ValueError("could not create a stopped container for archive inventory")
            container = next((line.strip() for line in reversed(result.stdout.splitlines())
                              if re.fullmatch(r"[0-9a-f]{12,64}", line.strip())), None)
            if not container:
                raise ValueError("stopped inventory container did not return its identity")
            archive = Path(temporary) / "app.jar"
            result = reports.command(["docker", "cp", f"{container}:/app/app.jar", str(archive)], "image-copy")
            if result.returncode:
                raise ValueError("could not copy /app/app.jar from the tested image")
            inventory = archive_inventory(archive)
            write_json(reports.output / "image-inventory.json", inventory)
            return inventory
        except (OSError, ValueError, KeyError, TypeError, AttributeError, zipfile.BadZipFile) as error:
            reports.error(f"container: runtime archive inventory failed: {error}")
            return None
        finally:
            if container:
                result = reports.command(["docker", "rm", "-v", container], "image-remove")
                if result.returncode:
                    reports.error("container: stopped inventory container cleanup failed")


def container_reports(reports, image):
    inventory = image_inventory(reports, image)
    data, runs = reports.scan("container", ["snyk", "container", "test", image,
                                             "--file=Dockerfile", "--app-vulns"])
    seen = set()
    for run in runs:
        native = run.get("automationDetails", {}).get("id", "")
        match = re.fullmatch(r"Snyk/Container/(.+)/\d+/[^/]+", native)
        project = match.group(1) if match else None
        if project not in CONTAINER_PROJECTS or project in seen:
            reports.error("container: unknown or duplicate SARIF layer; its original findings remain uploaded")
        else:
            seen.add(project)
            reports.identify(run, "Snyk Container", f"snyk-container/{CONTAINER_PROJECTS[project][0]}/")
        reports.runs.append(run)
    if seen != set(CONTAINER_PROJECTS):
        reports.error("container: one or more expected SARIF layers are missing")
    try:
        items = [data, *data.get("applications", [])]
        by_name, dependency_layers = {}, {}
        for item in items:
            project = item.get("projectName")
            if project not in CONTAINER_PROJECTS or project in by_name:
                raise ValueError("unknown or duplicate JSON dependency layer")
            by_name[project] = item
            if CONTAINER_PROJECTS[project][1] == "maven":
                packages, application = application_dependency_packages(item, inventory)
                if application:
                    reports.checks[-1]["application_archive"] = application
            else:
                packages = dependency_packages(item, CONTAINER_PROJECTS[project][1])
            dependency_layers[project] = packages
            if not packages:
                raise ValueError(f"expected image dependencies missing from layer: {project}")
            unknown = [package.get("name", "<unnamed>") for package in packages
                       if not package.get("name") or not package.get("version") or package["version"] == "unknown"]
            if unknown:
                raise ValueError(f"unidentified dependencies in {project}: {', '.join(unknown)}")
        if set(by_name) != set(CONTAINER_PROJECTS):
            raise ValueError("one or more expected JSON dependency layers are missing")
        if not inventory:
            raise ValueError("the tested image archive could not be inventoried")
        packages = dependency_layers["mcp-zap-server:snyk-scan:/app"]
        matched = validate_archive_coverage(inventory, packages)
        write_json(reports.output / "runtime-coverage.json", matched)
    except (AttributeError, KeyError, TypeError, ValueError) as error:
        reports.error(f"container: incomplete dependency coverage: {error}")


def iac_reports(reports):
    data, runs = reports.scan("iac", ["snyk", "iac", "test", "snyk-rendered-helm"])
    if len(runs) != 1:
        reports.error("iac: expected one rendered-manifest SARIF run")
    else:
        reports.identify(runs[0], "Snyk IaC", "snyk-iac/helm/")
    reports.runs.extend(runs)
    items = data if isinstance(data, list) else [data]
    if not items or any(not isinstance(item, dict) or item.get("error") or
                        not isinstance(item.get("infrastructureAsCodeIssues"), list) or
                        not isinstance(item.get("targetFile"), str) or not item["targetFile"] or
                        not isinstance(item.get("ok"), bool)
                        for item in items):
        reports.error("iac: missing or failed JSON execution result")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=("source", "container", "iac", "monitor-source"))
    parser.add_argument("--image", default="mcp-zap-server:snyk-scan")
    args = parser.parse_args()
    root = Path.cwd()
    reports = Reports(root, root / "build" / "snyk-reports" / args.kind)
    if args.kind == "monitor-source":
        for name, path, _, options in SOURCE_TARGETS:
            result = reports.command(["snyk", "monitor", f"--file={path}", *options,
                                      f"--org={os.environ['SNYK_ORG']}"], name)
            if result.returncode:
                reports.error(f"{name}: Snyk monitor failed with exit {result.returncode}")
    elif args.kind == "source":
        source_reports(reports)
    elif args.kind == "container":
        container_reports(reports, args.image)
    else:
        iac_reports(reports)
    return reports.finish()


if __name__ == "__main__":
    raise SystemExit(main())
