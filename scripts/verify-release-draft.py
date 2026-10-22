#!/usr/bin/env python3
"""Read-only preflight for the four Desktop files on an existing release draft."""

import argparse
import base64
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import threading
from urllib.parse import quote


VERSION = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?"
MAX_ASSET_BYTES = 128 * 1024 * 1024


class PreflightError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise PreflightError(message)


class GitHub:
    """Every request, including asset downloads, is an explicit GitHub GET."""

    def __init__(self, repository):
        self.prefix = f"repos/{repository}"

    def _get(self, endpoint, destination, *, limit, binary=False, paginate=False):
        command = ["gh", "api", "--hostname", "github.com", "--method", "GET",
                   "--header", "Accept: application/octet-stream" if binary
                   else "Accept: application/vnd.github+json", endpoint]
        if paginate:
            command += ["--paginate", "--slurp"]
        with tempfile.TemporaryFile() as errors:
            try:
                process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=errors)
            except FileNotFoundError as exc:
                raise PreflightError("Install and authenticate GitHub CLI before this read-only check") from exc
            timer = threading.Timer(90, process.kill)
            timer.daemon = True
            timer.start()
            try:
                total = 0
                with process.stdout:
                    while True:
                        chunk = process.stdout.read(min(65536, limit + 1 - total))
                        if not chunk:
                            break
                        total += len(chunk)
                        require(total <= limit, "GitHub response exceeds its permitted size")
                        destination.write(chunk)
                require(process.wait() == 0, "GitHub GET failed or timed out; check CLI access and retry the preflight")
            finally:
                timer.cancel()
                if process.poll() is None:
                    process.kill()
                process.wait()

    def json(self, suffix, *, paginate=False):
        output = io.BytesIO()
        self._get(f"{self.prefix}/{suffix}", output, limit=16 * 1024 * 1024,
                  paginate=paginate)
        try:
            value = json.loads(output.getvalue())
        except (ValueError, UnicodeError) as exc:
            raise PreflightError("GitHub returned malformed JSON") from exc
        if paginate:
            require(isinstance(value, list) and all(isinstance(page, list) for page in value),
                    "Unexpected paginated GitHub response")
            return [item for page in value for item in page]
        return value

    def download(self, asset, destination):
        with destination.open("xb") as output:
            self._get(f"{self.prefix}/releases/assets/{asset['id']}", output,
                      limit=asset["size"], binary=True)


def validate_inputs(tag, commit, repository):
    require(re.fullmatch("v" + VERSION, tag), "Use a strict vMAJOR.MINOR.PATCH scanner tag")
    require(re.fullmatch(r"[0-9a-f]{40}", commit), "Use the approved full lowercase commit SHA")
    require(re.fullmatch(r"[A-Za-z0-9_-][A-Za-z0-9_.-]*/[A-Za-z0-9_-][A-Za-z0-9_.-]*", repository),
            "Use an owner/repository name")


def verify_source(github, tag, commit):
    resolved = github.json(f"commits/refs%2Ftags%2F{quote(tag, safe='')}")
    require(resolved.get("sha") == commit, "Tag does not resolve to the approved commit")
    comparison = github.json(f"compare/{commit}...main")
    require(comparison.get("merge_base_commit", {}).get("sha") == commit,
            "Approved commit is not reachable from main")


def source_text(github, filename, commit):
    value = github.json(f"contents/{filename}?ref={commit}")
    require(value.get("encoding") == "base64", f"Unexpected source encoding: {filename}")
    try:
        return base64.b64decode(value["content"], validate=False).decode("utf-8")
    except (KeyError, ValueError, UnicodeError) as exc:
        raise PreflightError(f"Cannot decode tagged source: {filename}") from exc


def desktop_base(github, tag, commit):
    gradle = source_text(github, "build.gradle", commit)
    scanner_versions = re.findall(r"^version = '([^']+)'$", gradle, re.MULTILINE)
    require(scanner_versions == [tag[1:]], "Tag must match the tagged scanner build.gradle version")
    versions = []
    for filename in ("package.json", "manifest.json", "package-lock.json"):
        try:
            value = json.loads(source_text(github, f"packaging/claude-desktop/{filename}", commit))
            versions.append(value["version"])
            if filename == "package-lock.json":
                versions.append(value["packages"][""]["version"])
        except (ValueError, KeyError, TypeError) as exc:
            raise PreflightError(f"Invalid tagged Desktop version metadata: {filename}") from exc
    require(all(isinstance(version, str) and re.fullmatch(VERSION, version) for version in versions)
            and len(set(versions)) == 1, "Tagged Desktop package, manifest and lockfile versions must match")
    return f"mcp-zap-connect-claude-desktop-{versions[0]}"


def verify_mutable_release(release, tag, release_id):
    require(release.get("id") == release_id and release.get("tag_name") == tag,
            "Release identity or tag changed")
    require(release.get("draft") is True and release.get("immutable") is False,
            "Release must be a mutable draft. Published immutable releases cannot receive missing assets; prepare a new version")


def selected_assets(github, release_id, filenames):
    all_assets = github.json(f"releases/{release_id}/assets?per_page=100", paginate=True)
    selected = {}
    for filename in filenames:
        matches = [asset for asset in all_assets if asset.get("name") == filename]
        require(len(matches) == 1, f"Draft must contain exactly one uploaded file: {filename}")
        asset = matches[0]
        maximum_size = 4096 if filename.endswith(".sha256") else MAX_ASSET_BYTES
        require(asset.get("state") == "uploaded" and type(asset.get("id")) is int
                and asset["id"] > 0 and type(asset.get("size")) is int
                and 0 < asset["size"] <= maximum_size,
                f"Missing, incomplete or oversized Desktop file: {filename}")
        # Downloads change download_count. Compare identity/content fields rather
        # than failing because this check itself incremented a usage counter.
        selected[filename] = {field: asset.get(field) for field in
                              ("id", "name", "state", "size", "digest", "created_at", "updated_at")}
    require(len({asset["id"] for asset in selected.values()}) == 4, "Desktop files must have distinct asset identities")
    return selected


def sha256(filename):
    digest = hashlib.sha256()
    with filename.open("rb") as source:
        for chunk in iter(lambda: source.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_draft(github, tag, commit):
    verify_source(github, tag, commit)
    package_base = desktop_base(github, tag, commit)
    files = [f"{package_base}.mcpb", f"{package_base}.mcpb.sha256",
             f"{package_base}-setup.md", f"{package_base}-setup.md.sha256"]
    releases = github.json("releases?per_page=100", paginate=True)
    matches = [release for release in releases if release.get("tag_name") == tag]
    require(len(matches) == 1, "Exactly one existing scanner release draft is required")
    release_id = matches[0].get("id")
    require(type(release_id) is int and release_id > 0, "Invalid release identity")
    release = github.json(f"releases/{release_id}")
    verify_mutable_release(release, tag, release_id)
    assets = selected_assets(github, release_id, files)
    with tempfile.TemporaryDirectory(prefix="mcp-zap-release-preflight-") as temporary:
        directory = Path(temporary)
        for name, asset in assets.items():
            destination = directory / name
            github.download(asset, destination)
            require(destination.stat().st_size == asset["size"], f"Downloaded size differs: {name}")
            digest = asset.get("digest")
            if digest is not None:
                require(digest == "sha256:" + sha256(destination), f"GitHub asset digest differs: {name}")
        for name in (files[0], files[2]):
            try:
                checksum = (directory / f"{name}.sha256").read_text("utf-8")
            except UnicodeError as exc:
                raise PreflightError(f"Checksum file is not UTF-8: {name}") from exc
            require(checksum in (f"{sha256(directory / name)}  {name}\n",
                                 f"{sha256(directory / name)}  {name}"),
                    f"Downloaded checksum or exact filename differs: {name}")
        verify_mutable_release(github.json(f"releases/{release_id}"), tag, release_id)
        require(selected_assets(github, release_id, files) == assets,
                "Desktop asset metadata changed during verification; inspect the draft and retry")
        verify_source(github, tag, commit)
    return release_id, package_base


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True, help="Existing scanner tag, for example v0.17.1")
    parser.add_argument("--commit", required=True, help="Independently approved full commit SHA")
    parser.add_argument("--repo", default="dtkmn/mcp-zap-server", help="GitHub owner/repository")
    args = parser.parse_args(argv)
    try:
        validate_inputs(args.tag, args.commit, args.repo)
        release_id, package = verify_draft(GitHub(args.repo), args.tag, args.commit)
    except (PreflightError, OSError, TypeError, AttributeError, KeyError) as exc:
        print(f"STOP: {exc}", file=sys.stderr)
        return 1
    print(f"PASS: mutable draft {release_id}, tag {args.tag}, commit {args.commit}; "
          f"all four {package} assets downloaded and checksums verified.")
    print("Point-in-time preflight only. Review the guide and publish separately; this command made only GET requests.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
