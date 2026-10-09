#!/usr/bin/env python3
"""Maintainer builder; the generated package itself requires no Python."""

import argparse
import hashlib
import json
import pathlib
import platform
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request
import zipfile


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def fetch_component(cache, component):
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / component["filename"]
    if not archive.exists():
        print("Downloading " + component["filename"], flush=True)
        temporary = archive.with_suffix(archive.suffix + ".partial")
        try:
            with urllib.request.urlopen(component["url"], timeout=60) as response, temporary.open("wb") as destination:
                shutil.copyfileobj(response, destination)
            if digest(temporary) != component["sha256"]:
                raise ValueError("Upstream checksum mismatch: " + component["filename"])
            temporary.rename(archive)
        finally:
            temporary.unlink(missing_ok=True)
    if digest(archive) != component["sha256"]:
        raise ValueError("Cached component checksum mismatch: " + component["filename"])
    return archive


def write_archive(directory):
    archive = pathlib.Path(str(directory) + ".tar.gz")
    subprocess.run(["tar", "--uid", "0", "--gid", "0", "--uname", "root", "--gname", "root",
                    "--no-acls", "--no-xattrs", "--no-mac-metadata", "--no-fflags",
                    "-czf", str(archive), "-C", str(directory.parent), directory.name], check=True)
    archive.with_suffix(archive.suffix + ".sha256").write_text(digest(archive) + "  " + archive.name + "\n")
    return archive


def deliver_source(source_file, entry, destination):
    omitted = []
    target = destination / entry.get("delivered_filename", source_file.name)
    if entry.get("source_only") or entry.get("omit_files"):
        binary_suffixes = {".jar", ".class", ".so", ".dll", ".dylib", ".exe"}
        with tarfile.open(source_file, "r:gz") as original, tarfile.open(target, "w:gz") as delivered:
            for member in original:
                relative = pathlib.PurePosixPath(member.name)
                local_path = str(pathlib.PurePosixPath(*relative.parts[1:]))
                excluded = any(local_path == prefix.rstrip("/") or local_path.startswith(prefix.rstrip("/") + "/")
                               for prefix in entry.get("omit_directories", []))
                if excluded or local_path in entry.get("omit_files", []) or (
                        entry.get("source_only") and relative.suffix.lower() in binary_suffixes):
                    omitted.append(member.name)
                    continue
                delivered.addfile(member, original.extractfile(member) if member.isfile() else None)
    else:
        shutil.copy2(source_file, target)
    return {"id": entry["id"], "filename": target.name, "sha256": digest(target),
            "upstream_sha256": entry["sha256"], "omitted_entries": omitted}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-jar", type=pathlib.Path, required=True)
    parser.add_argument("--cache-dir", type=pathlib.Path)
    parser.add_argument("--output-dir", type=pathlib.Path)
    args = parser.parse_args()
    if platform.system() != "Darwin" or platform.machine() != "arm64":
        parser.error("The initial preview is built and verified on Apple Silicon macOS.")

    source = pathlib.Path(__file__).resolve().parent
    repo = source.parent.parent
    components = json.loads((source / "components.json").read_text())
    source_manifest = json.loads((source / "source-manifest.json").read_text())
    cache = args.cache_dir or repo / "build/macos/cache"
    output = args.output_dir or repo / "build/macos"
    cache.mkdir(parents=True, exist_ok=True)
    output.mkdir(parents=True, exist_ok=True)
    for component_name in ("java", "zap"):
        component = components[component_name]
        if source_manifest["reviewed_binary_inputs"][component_name] != component["sha256"]:
            parser.error("Bundled input changed; review its notices and corresponding sources before packaging.")
        fetch_component(cache, component)

    with zipfile.ZipFile(args.server_jar) as jar:
        manifest = jar.read("META-INF/MANIFEST.MF").decode()
        runtime_jars = sorted(pathlib.PurePosixPath(name).name for name in jar.namelist()
                              if name.startswith("BOOT-INF/lib/") and name.endswith(".jar"))
        for library in source_manifest["server_libraries"]:
            path = "BOOT-INF/lib/" + library["jar"]
            if path not in jar.namelist() or hashlib.sha256(jar.read(path)).hexdigest() != library["sha256"]:
                parser.error("Source-bearing server library changed; review the source manifest before packaging.")
    server_notices = source / "THIRD_PARTY_NOTICES.md"
    notice_text = server_notices.read_text()
    inventory = notice_text.split("<!-- BEGIN SERVER_RUNTIME_JAR_INVENTORY -->", 1)[-1].split(
        "<!-- END SERVER_RUNTIME_JAR_INVENTORY -->", 1)[0]
    noticed_jars = sorted(line.split("`", 2)[1] for line in inventory.splitlines() if line.startswith("| `"))
    if noticed_jars != runtime_jars:
        parser.error("Server dependency notices do not match the bootJar; update the versioned runtime inventory and license texts before packaging.")
    attributes = dict(line.split(": ", 1) for line in manifest.splitlines() if ": " in line)
    if attributes.get("Start-Class") != "mcp.server.zap.McpServerApplication" or attributes.get("Build-Jdk-Spec") != "25":
        parser.error("Provide this project's Java 25 executable bootJar, not its plain or extension JAR.")
    version = attributes["Implementation-Version"]
    if not version or any(character not in "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.-" for character in version):
        parser.error("Invalid application version in JAR manifest.")
    name = "mcp-zap-server-" + version + "-macos-arm64-preview"
    package = output / name
    archive = output / (name + ".tar.gz")
    source_package = output / (name + "-sources")
    source_archive = output / (name + "-sources.tar.gz")
    if any(path.exists() for path in (package, archive, source_package, source_archive)):
        parser.error("Output already exists; use a new --output-dir to preserve previous artifacts.")
    source_files = [fetch_component(cache / "sources", entry) for entry in source_manifest["sources"]]

    with tempfile.TemporaryDirectory(prefix="mcp-zap-package-") as staging:
        staging = pathlib.Path(staging)
        subprocess.run(["tar", "-xzf", str(cache / components["java"]["filename"]), "-C", str(staging)], check=True)
        subprocess.run(["unzip", "-q", str(cache / components["zap"]["filename"]), "-d", str(staging)], check=True)
        runtime = next(staging.glob("*-jre"))
        zap = staging / ("ZAP_" + components["zap"]["version"])
        # This HTTP preview omits browser helpers/callbacks and the optional
        # Import/Export add-on whose historical source mapping needs separate review.
        excluded = []
        for addon in zap.joinpath("plugin").glob("*.zap"):
            if addon.name.split("-", 1)[0] in {
                "authhelper", "client", "domxss", "exim", "hud", "selenium", "sequence",
                "spiderAjax", "webdriverlinux", "webdrivermacos", "webdriverwindows", "zest",
            }:
                excluded.append(addon.name)
                addon.unlink()
        zap_archives = sorted(str(path.relative_to(zap)) for path in zap.rglob("*")
                              if path.suffix in {".jar", ".zap"} and path.is_file())
        if zap_archives != source_manifest["retained_zap_archives"]:
            parser.error("Retained ZAP components changed; review their notices and sources before packaging.")
        package.mkdir()
        subprocess.run(["cp", "-Rp", str(runtime), str(package / "runtime")], check=True)
        shutil.copytree(zap, package / "zap", symlinks=True)
        (package / "app").mkdir()
        shutil.copy2(args.server_jar, package / "app/server.jar")
        (package / "bin").mkdir()
        (package / "lib").mkdir()
        for original, destination in [
            (source / "mcp-zap", package / "bin/mcp-zap"),
            (repo / "bin/self-serve-doctor.sh", package / "lib/self-serve-doctor.sh"),
            (source / "chatgpt-tunnel.sh", package / "lib/chatgpt-tunnel.sh"),
        ]:
            shutil.copy2(original, destination)
            destination.chmod(0o755)
        shutil.copy2(repo / "LICENSE", package / "LICENSE")
        shutil.copy2(server_notices, package / "SERVER_THIRD_PARTY_NOTICES.md")
        shutil.copy2(source / "DISTRIBUTION_NOTICES.md", package / "DISTRIBUTION_NOTICES.md")
        shutil.copy2(source / "SOURCE_DISTRIBUTION.md", package / "SOURCE_DISTRIBUTION.md")
        shutil.copy2(source / "source-manifest.json", package / "SOURCE_MANIFEST.json")
        shutil.copy2(repo / "docs/getting-started/MACOS_PACKAGE.md", package / "MACOS_PACKAGE.md")
        shutil.copy2(repo / "docs/getting-started/CHATGPT_LOCAL_TUNNEL.md", package / "CHATGPT_LOCAL_TUNNEL.md")
        (package / "README.md").write_text(
            "# MCP ZAP Server local macOS preview\n\n"
            "Run ./bin/mcp-zap start from a logged-in macOS desktop session. "
            "Startup already checks authenticated readiness; use doctor for diagnostics.\n\n"
            "Read [installation and limitations](MACOS_PACKAGE.md) and "
            "[private ChatGPT connection](CHATGPT_LOCAL_TUNNEL.md).\n"
        )
        java = package / "runtime/Contents/Home/bin/java"
        subprocess.run([str(java), "-version"], check=True)
        subprocess.run(["codesign", "--verify", "--deep", "--strict", str(package / "runtime")], check=True)
        components.update({
            "server_version": version,
            "server_sha256": digest(package / "app/server.jar"),
            "distribution_status": "unsigned-preview; browser-downloaded clean-install validation pending; not signed/notarized",
            "source_companion": source_archive.name,
            "source_manifest_sha256": digest(source / "source-manifest.json"),
            "excluded_addons": sorted(excluded),
            "included_addons": sorted(addon.name for addon in package.joinpath("zap/plugin").glob("*.zap")),
        })
        (package / "COMPONENTS.json").write_text(json.dumps(components, indent=2) + "\n")
        (package / "THIRD_PARTY_NOTICES.md").write_text(
            "# Bundled components\n\n"
            "Supplemental JRE/ZAP/add-on notices: see DISTRIBUTION_NOTICES.md. "
            "Source delivery and library replacement: see SOURCE_DISTRIBUTION.md and SOURCE_MANIFEST.json. "
            "Distribute the matching source companion beside this binary archive: " + source_archive.name + ".\n\n"
            "MCP ZAP Server: see LICENSE, SERVER_THIRD_PARTY_NOTICES.md and app/server.jar's embedded CycloneDX SBOM.\n\n"
            "Eclipse Temurin/OpenJDK: preserve runtime/Contents/Home/NOTICE and its legal/ directory. "
            "GPLv2 with the Classpath Exception and component-specific notices apply. "
            "Corresponding-source assets and release metadata: " + components["java"]["source_release"] + ".\n\n"
            "ZAP and included add-ons: preserve zap/license/ and each add-on's embedded notices. "
            "Release source and component metadata: " + components["zap"]["source_release"] + ".\n\n"
            "The OpenAI tunnel client is installed separately and is not redistributed in this archive.\n"
        )
        with (package / "SHA256SUMS").open("w") as sums:
            for file in sorted(package.rglob("*")):
                if file.is_file() and not file.is_symlink() and file.name != "SHA256SUMS":
                    sums.write(digest(file) + "  " + file.relative_to(package).as_posix() + "\n")
    archive = write_archive(package)
    (source_package / "archives").mkdir(parents=True)
    delivered = [deliver_source(path, entry, source_package / "archives")
                 for path, entry in zip(source_files, source_manifest["sources"])]
    (source_package / "DELIVERED_SOURCES.json").write_text(json.dumps(delivered, indent=2) + "\n")
    for filename in ("SOURCE_DISTRIBUTION.md", "DISTRIBUTION_NOTICES.md", "SOURCE_MANIFEST.json", "SERVER_THIRD_PARTY_NOTICES.md"):
        shutil.copy2(package / filename, source_package / filename)
    (source_package / "packaging").mkdir()
    for filename in ("build-package.py", "components.json", "source-manifest.json", "mcp-zap", "chatgpt-tunnel.sh"):
        shutil.copy2(source / filename, source_package / "packaging" / filename)
    shutil.copy2(repo / "bin/self-serve-doctor.sh", source_package / "packaging/self-serve-doctor.sh")
    shutil.copy2(repo / "LICENSE", source_package / "LICENSE")
    (source_package / "BINARY_PACKAGE.json").write_text(json.dumps({
        "filename": archive.name, "sha256": digest(archive),
        "source_manifest_sha256": digest(source / "source-manifest.json"),
    }, indent=2) + "\n")
    source_archive = write_archive(source_package)
    print("Local preview created: " + str(archive))
    print("Matching source companion: " + str(source_archive))
    print("Publish both archives and checksums together after browser-downloaded installation checks; signing/notarization is a separate milestone.")


if __name__ == "__main__":
    main()
