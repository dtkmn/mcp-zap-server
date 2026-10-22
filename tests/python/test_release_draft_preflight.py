"""Exercise release preflight with offline fixtures, including a fake gh process."""

import base64
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[2] / "scripts/verify-release-draft.py"
SPEC = importlib.util.spec_from_file_location("release_draft_preflight", SCRIPT)
PREFLIGHT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREFLIGHT)
COMMIT = "a" * 40
TAG = "v0.17.1"
BASE = "mcp-zap-connect-claude-desktop-0.1.0"
FILES = [f"{BASE}.mcpb", f"{BASE}.mcpb.sha256", f"{BASE}-setup.md", f"{BASE}-setup.md.sha256"]


class FixtureGitHub:
    def __init__(self):
        self.source = COMMIT
        self.main_base = COMMIT
        self.scanner_version = "0.17.1"
        self.desktop_versions = {"package.json": "0.1.0", "manifest.json": "0.1.0", "package-lock.json": "0.1.0"}
        self.release = {"id": 42, "tag_name": TAG, "draft": True, "immutable": False}
        self.releases = [self.release]
        self.payloads = {}
        for name in (FILES[0], FILES[2]):
            self.payloads[name] = f"valid fixture {name}\n".encode()
            self.payloads[f"{name}.sha256"] = f"{hashlib.sha256(self.payloads[name]).hexdigest()}  {name}\n".encode()
        self.assets = [{"id": index + 1, "name": name, "state": "uploaded", "size": len(self.payloads[name]),
                        "digest": "sha256:" + hashlib.sha256(self.payloads[name]).hexdigest()}
                       for index, name in enumerate(FILES)]
        self.downloads = []
        self.calls = []
        self.after_download = None

    def json(self, suffix, *, paginate=False):
        self.calls.append((suffix, paginate))
        if suffix.startswith("commits/"):
            value = {"sha": self.source}
        elif suffix.startswith("compare/"):
            value = {"merge_base_commit": {"sha": self.main_base}}
        elif suffix.startswith("contents/"):
            assert suffix.endswith(f"?ref={COMMIT}"), suffix
            if "build.gradle" in suffix:
                text = f"version = '{self.scanner_version}'\n"
            else:
                filename = suffix.split("/")[-1].split("?")[0]
                version = self.desktop_versions[filename]
                document = {"version": version}
                if filename == "package-lock.json":
                    document["packages"] = {"": {"version": version}}
                text = json.dumps(document)
            value = {"encoding": "base64", "content": base64.b64encode(text.encode()).decode()}
        elif suffix == "releases?per_page=100":
            assert paginate
            value = self.releases
        elif suffix == "releases/42":
            value = self.release
        elif suffix == "releases/42/assets?per_page=100":
            assert paginate
            value = self.assets
        else:
            raise AssertionError(suffix)
        return copy.deepcopy(value)

    def download(self, asset, destination):
        self.downloads.append(destination)
        destination.write_bytes(self.payloads[asset["name"]])
        if len(self.downloads) == 4 and self.after_download:
            self.after_download()


class ReleaseDraftPreflightTest(unittest.TestCase):
    def setUp(self):
        self.github = FixtureGitHub()

    def verify(self):
        try:
            return PREFLIGHT.verify_draft(self.github, TAG, COMMIT)
        finally:
            self.assertTrue(all(not destination.exists() for destination in self.github.downloads),
                            "Temporary downloads must be removed on success and failure")

    def rejects(self, expected):
        with self.assertRaisesRegex(PREFLIGHT.PreflightError, expected):
            self.verify()

    def test_complete_draft_passes_and_rechecks_source_release_and_assets(self):
        self.assertEqual(self.verify(), (42, BASE))
        self.assertEqual(len(self.github.downloads), 4)
        self.assertEqual(sum(suffix.startswith("commits/") for suffix, _ in self.github.calls), 2)
        self.assertEqual(self.github.calls.count(("releases/42", False)), 2)
        self.assertEqual(self.github.calls.count(("releases/42/assets?per_page=100", True)), 2)

    def test_missing_asset_rejected_before_download(self):
        self.github.assets.pop()
        self.rejects("exactly one uploaded file")
        self.assertFalse(self.github.downloads)

    def test_duplicate_asset_rejected(self):
        self.github.assets.append(copy.deepcopy(self.github.assets[0]))
        self.rejects("exactly one uploaded file")

    def test_partial_asset_rejected(self):
        self.github.assets[0]["state"] = "starter"
        self.rejects("incomplete")

    def test_zero_and_oversized_assets_rejected(self):
        for size in (0, PREFLIGHT.MAX_ASSET_BYTES + 1):
            with self.subTest(size=size):
                self.github.assets[0]["size"] = size
                self.rejects("incomplete or oversized")

    def test_published_or_immutable_release_rejected(self):
        for change in ({"draft": False}, {"immutable": True}):
            with self.subTest(change=change):
                self.github.release.update(draft=True, immutable=False)
                self.github.release.update(change)
                self.rejects("mutable draft")
        self.assertFalse(self.github.downloads)

    def test_missing_and_duplicate_release_rejected(self):
        for releases in ([], [self.github.release, self.github.release]):
            with self.subTest(releases=len(releases)):
                self.github.releases = releases
                self.rejects("Exactly one existing")

    def test_wrong_tag_rejected(self):
        self.github.release["tag_name"] = "v0.17.0"
        self.rejects("Exactly one existing")

    def test_wrong_commit_rejected(self):
        self.github.source = "b" * 40
        self.rejects("approved commit")

    def test_commit_not_on_main_rejected(self):
        self.github.main_base = "b" * 40
        self.rejects("not reachable from main")

    def test_wrong_scanner_version_rejected(self):
        self.github.scanner_version = "0.17.0"
        self.rejects("build.gradle version")

    def test_inconsistent_desktop_version_rejected(self):
        self.github.desktop_versions["manifest.json"] = "0.2.0"
        self.rejects("versions must match")

    def test_downloaded_size_mismatch_rejected_and_cleaned(self):
        self.github.assets[0]["size"] += 1
        self.rejects("Downloaded size differs")

    def test_github_digest_mismatch_rejected(self):
        self.github.assets[0]["digest"] = "sha256:" + "0" * 64
        self.rejects("GitHub asset digest differs")

    def test_wrong_checksum_rejected_even_without_github_digest(self):
        payload = ("0" * 64 + "  " + FILES[0] + "\n").encode()
        self.github.payloads[FILES[1]] = payload
        self.github.assets[1].pop("digest")
        self.rejects("checksum or exact filename differs")

    def test_checksum_for_wrong_filename_rejected(self):
        checksum = self.github.payloads[FILES[1]].replace(FILES[0].encode(), b"x" * len(FILES[0]))
        self.github.payloads[FILES[1]] = checksum
        self.github.assets[1].pop("digest")
        self.rejects("checksum or exact filename differs")

    def test_source_move_during_download_rejected(self):
        self.github.after_download = lambda: setattr(self.github, "source", "b" * 40)
        self.rejects("approved commit")

    def test_publication_during_download_rejected(self):
        self.github.after_download = lambda: self.github.release.update(draft=False, immutable=True)
        self.rejects("mutable draft")

    def test_asset_replacement_during_download_rejected(self):
        self.github.after_download = lambda: self.github.assets[0].update(id=99)
        self.rejects("asset metadata changed")

    def test_own_download_counter_changes_do_not_fail_content_drift_check(self):
        self.github.after_download = lambda: self.github.assets[0].update(download_count=1)
        self.assertEqual(self.verify(), (42, BASE))

    def test_strict_inputs(self):
        PREFLIGHT.validate_inputs(TAG, COMMIT, "dtkmn/mcp-zap-server")
        for tag, commit, repository in (("v017.1.0", COMMIT, "dtkmn/mcp-zap-server"),
                                         (TAG, "short", "dtkmn/mcp-zap-server"),
                                         (TAG, COMMIT, "https://elsewhere/repo")):
            with self.subTest(tag=tag, commit=commit, repository=repository):
                with self.assertRaises(PREFLIGHT.PreflightError):
                    PREFLIGHT.validate_inputs(tag, commit, repository)


class GitHubReadOnlyAdapterTest(unittest.TestCase):
    def test_get_pagination_download_and_byte_limit_with_real_fake_gh_process(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            executable = directory / "gh"
            executable.write_text(f"#!{sys.executable}\n" + '''
import json, sys
args = sys.argv[1:]
assert args[0] == 'api'
assert args[args.index('--hostname') + 1] == 'github.com'
assert args[args.index('--method') + 1] == 'GET'
assert not any(x in args for x in ('--input', '-f', '-F', '--field', '--raw-field'))
if '--paginate' in args:
    assert '--slurp' in args
    print(json.dumps([[{'id': 1}], [{'id': 2}]]))
elif 'Accept: application/octet-stream' in args:
    sys.stdout.buffer.write(b'abc')
else:
    print(json.dumps({'draft': True}))
''')
            executable.chmod(0o700)
            old_path = os.environ["PATH"]
            os.environ["PATH"] = str(directory) + os.pathsep + old_path
            try:
                github = PREFLIGHT.GitHub("dtkmn/mcp-zap-server")
                self.assertEqual(github.json("releases?per_page=100", paginate=True), [{"id": 1}, {"id": 2}])
                self.assertEqual(github.json("releases/42"), {"draft": True})
                destination = directory / "asset"
                github.download({"id": 1, "size": 3}, destination)
                self.assertEqual(destination.read_bytes(), b"abc")
                with self.assertRaisesRegex(PREFLIGHT.PreflightError, "permitted size"):
                    github.download({"id": 2, "size": 2}, directory / "oversized")
            finally:
                os.environ["PATH"] = old_path

    def test_invalid_cli_input_fails_before_gh(self):
        result = subprocess.run([sys.executable, str(SCRIPT), "--tag", "invalid", "--commit", COMMIT],
                                text=True, capture_output=True, timeout=10)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("STOP:", result.stderr)


if __name__ == "__main__":
    unittest.main()
