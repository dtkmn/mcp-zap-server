#!/usr/bin/env python3
"""Check shared project/plugin versioning against a local Git baseline."""

import argparse
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys


PLUGIN_NAME = "mcp-zap-connect"
PLUGIN_ROOT = f"plugins/{PLUGIN_NAME}"
MANIFESTS = ("plugin.json", ".claude-plugin/plugin.json", ".cursor-plugin/plugin.json")
GENERATED_DIRECTORIES = {".git", "node_modules", "__pycache__"}
SEMVER = re.compile(
    r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    r"(?:-((?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)"
    r"(?:\.(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*))*))?"
    r"(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"
)
RELEASE_TAG = re.compile(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")


class VersionCheckError(Exception):
    """A missing or inconsistent version contract."""


def git(repo, *args):
    result = subprocess.run(
        ["git", "-C", str(repo), *args], capture_output=True, check=False
    )
    if result.returncode:
        raise VersionCheckError(f"Git inspection failed ({args[0]}); check the repository and ref.")
    return result.stdout


def parse_version(version, label="Plugin version"):
    match = SEMVER.fullmatch(version) if isinstance(version, str) else None
    if match is None:
        raise VersionCheckError(f"{label} must be a valid SemVer string.")
    return tuple(map(int, match.group(1, 2, 3))), match.group(4)


def version_is_greater(current, previous):
    current_core, current_pre = parse_version(current)
    previous_core, previous_pre = parse_version(previous)
    if current_core != previous_core:
        return current_core > previous_core
    if current_pre == previous_pre:
        return False  # Build metadata does not change SemVer precedence.
    if current_pre is None or previous_pre is None:
        return current_pre is None
    current_parts, previous_parts = current_pre.split("."), previous_pre.split(".")
    for left, right in zip(current_parts, previous_parts):
        if left == right:
            continue
        if left.isdigit() and right.isdigit():
            return int(left) > int(right)
        if left.isdigit() != right.isdigit():
            return not left.isdigit()
        return left > right
    return len(current_parts) > len(previous_parts)


def resolve_commit(repo, ref):
    return git(repo, "rev-parse", "--verify", "--end-of-options", f"{ref}^{{commit}}").decode().strip()


def default_baseline(repo):
    """Choose the latest local release tag, excluding the release being checked."""
    head = resolve_commit(repo, "HEAD")
    candidates = []
    for tag in git(repo, "tag", "--list", "v*").decode().splitlines():
        match = RELEASE_TAG.fullmatch(tag)
        if match and resolve_commit(repo, f"refs/tags/{tag}") != head:
            candidates.append((tuple(map(int, match.groups())), tag))
    if not candidates:
        raise VersionCheckError(
            "No other local vX.Y.Z tag is available; fetch release history "
            "or supply --base-ref explicitly. This check does not fetch tags."
        )
    return max(candidates)[1]


def is_payload_path(name):
    return not GENERATED_DIRECTORIES.intersection(Path(name).parts)


def baseline_payload(repo, commit):
    payload = {}
    for entry in git(repo, "ls-tree", "-rz", commit, "--", PLUGIN_ROOT).split(b"\0"):
        if not entry:
            continue
        metadata, raw_name = entry.split(b"\t", 1)
        mode, kind, oid = metadata.decode().split()
        name = os.fsdecode(raw_name)
        if not is_payload_path(name):
            continue
        if kind != "blob" or mode not in ("100644", "100755"):
            raise VersionCheckError(f"Unsupported linked or special baseline plugin file: {name}")
        payload[name] = (mode, git(repo, "cat-file", "blob", oid))
    return payload


def working_payload(repo):
    payload = {}
    names = git(repo, "ls-files", "-z", "--cached", "--others", "--exclude-standard", "--", PLUGIN_ROOT)
    for raw_name in set(names.split(b"\0")) - {b""}:
        name = os.fsdecode(raw_name)
        if not is_payload_path(name):
            continue
        path = repo / name
        if any(parent.is_symlink() for parent in (path, *path.parents) if parent != repo):
            raise VersionCheckError(f"Linked plugin paths are not supported: {name}")
        if not path.exists():
            continue  # A tracked deletion is a payload change.
        if not path.is_file():
            raise VersionCheckError(f"Plugin payload must contain regular files: {name}")
        mode = "100755" if path.stat().st_mode & stat.S_IXUSR else "100644"
        payload[name] = (mode, path.read_bytes())
    return payload


def manifest_version(payload, label):
    versions = []
    for name in MANIFESTS:
        path = f"{PLUGIN_ROOT}/{name}"
        if path not in payload:
            raise VersionCheckError(f"Missing {label} plugin manifest: {name}")
        try:
            manifest = json.loads(payload[path][1])
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise VersionCheckError(f"Invalid {label} plugin manifest: {name}") from error
        if not isinstance(manifest, dict) or manifest.get("name") != PLUGIN_NAME:
            raise VersionCheckError(f"Unexpected plugin name in {label} manifest: {name}")
        version = manifest.get("version")
        parse_version(version)
        versions.append(version)
    if len(set(versions)) != 1:
        raise VersionCheckError(f"The three {label} plugin manifest versions must match.")
    return versions[0]


def project_version(source, label):
    versions = re.findall(r"^version = '([^'\r\n]+)'$", source, re.MULTILINE)
    assignments = re.findall(r"^\s*version\s*=", source, re.MULTILINE)
    if len(versions) != 1 or len(assignments) != 1:
        raise VersionCheckError(f"The {label} build.gradle must declare exactly one literal project version.")
    parse_version(versions[0], "Project version")
    return versions[0]


def check(repo, base_ref=None):
    repo = repo.resolve()
    baseline = base_ref or default_baseline(repo)
    baseline_commit = resolve_commit(repo, baseline)
    previous = baseline_payload(repo, baseline_commit)
    current = working_payload(repo)
    previous_version = manifest_version(previous, "baseline")
    current_version = manifest_version(current, "current")
    current_project = project_version((repo / "build.gradle").read_text(), "current")
    previous_project = project_version(
        git(repo, "show", f"{baseline_commit}:build.gradle").decode(), "baseline"
    )
    if current_version != current_project:
        raise VersionCheckError(
            f"Plugin version {current_version} must match project version {current_project}."
        )
    changed = previous != current
    if changed and not version_is_greater(current_version, previous_version):
        raise VersionCheckError(
            f"Plugin payload changed since {baseline}, but version {current_version} "
            f"does not advance {previous_version}. Bump all three plugin manifests."
        )
    if changed and not version_is_greater(current_project, previous_project):
        raise VersionCheckError(
            f"Plugin payload changed since {baseline}, but shared version {current_project} "
            f"does not advance repository version {previous_project}. Choose a new release version."
        )
    current_tag = f"v{current_version}"
    if current_tag in git(repo, "tag", "--list", current_tag).decode().splitlines():
        tagged = baseline_payload(repo, resolve_commit(repo, f"refs/tags/{current_tag}"))
        if current != tagged:
            raise VersionCheckError(
                f"Plugin payload differs from existing local tag {current_tag}. "
                "Choose a new project/plugin version; do not reuse the tag."
            )
    return current_version, baseline, changed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument(
        "--base-ref",
        help="Exact Git baseline; default: highest local stable vX.Y.Z tag not pointing at HEAD.",
    )
    args = parser.parse_args()
    try:
        version, baseline, changed = check(args.repo, args.base_ref)
    except (VersionCheckError, OSError) as error:
        print(f"Plugin version check failed: {error}", file=sys.stderr)
        return 1
    print(f"Plugin {PLUGIN_NAME} {version}: project and manifests agree; payload "
          f"{'advanced' if changed else 'unchanged'} against {baseline}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
