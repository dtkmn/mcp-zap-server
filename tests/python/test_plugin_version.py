"""Exercise plugin version checks against isolated Git history and working files."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest


SCRIPT = Path(__file__).resolve().parents[2] / "scripts/check-plugin-version.py"
PLUGIN = Path("plugins/mcp-zap-connect")
DESKTOP = Path("packaging/claude-desktop")
MANIFESTS = ("plugin.json", ".claude-plugin/plugin.json", ".cursor-plugin/plugin.json")


class PluginVersionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="mcp-plugin-version-")
        self.addCleanup(self.temporary.cleanup)
        self.repo = Path(self.temporary.name)
        self.git("init", "-q")
        self.write_versions("0.17.1")
        self.write(PLUGIN / "skills/setup/SKILL.md", "Original setup instructions.\n")
        self.write(PLUGIN / "README.md", "Install the plugin.\n")
        self.commit("Released plugin")
        self.git("tag", "v0.17.1")
        self.write("server.txt", "Unrelated server change.\n")
        self.commit("After release")

    def git(self, *args):
        result = subprocess.run(
            ["git", "-C", str(self.repo), *args],
            capture_output=True, check=True,
            env={**os.environ, "GIT_AUTHOR_NAME": "Fixture", "GIT_AUTHOR_EMAIL": "fixture@example.invalid",
                 "GIT_COMMITTER_NAME": "Fixture", "GIT_COMMITTER_EMAIL": "fixture@example.invalid",
                 "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull},
        )
        return result.stdout

    def write(self, relative, text):
        path = self.repo / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    def write_versions(self, version, project=None, desktop=None):
        self.write("build.gradle", f"version = '{version if project is None else project}'\n")
        self.write(PLUGIN / "scripts/check-scanner-release.sh", f"SCANNER_RELEASE_TAG='v{version}'\n")
        self.write(PLUGIN / "skills/setup-local-scanner/SKILL.md", f"  git clone --branch v{version} --single-branch example\n")
        for manifest in MANIFESTS:
            self.write(PLUGIN / manifest, json.dumps({"name": "mcp-zap-connect", "version": version}) + "\n")
        desktop_version = version if desktop is None else desktop
        for name in ("manifest.json", "package.json"):
            self.write(DESKTOP / name, json.dumps({"version": desktop_version}))
        self.write(DESKTOP / "package-lock.json", json.dumps({
            "version": desktop_version, "packages": {"": {"version": desktop_version}},
        }))

    def commit(self, message):
        self.git("add", ".")
        self.git("-c", "commit.gpgsign=false", "commit", "-qm", message)

    def run_check(self, *args):
        before = (self.git("rev-parse", "HEAD"), self.git("ls-files", "--stage", "-z"),
                  self.git("status", "--porcelain=v1", "-z"))
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "--repo", str(self.repo), *args],
            capture_output=True, text=True, check=False,
        )
        after = (self.git("rev-parse", "HEAD"), self.git("ls-files", "--stage", "-z"),
                 self.git("status", "--porcelain=v1", "-z"))
        self.assertEqual(before, after, "The check must not modify HEAD, staging or working files")
        return result

    def assert_passes(self, *args):
        result = self.run_check(*args)
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout

    def assert_rejected(self, message, *args):
        result = self.run_check(*args)
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn(message, result.stderr)

    def test_unchanged_plugin_payload_passes_with_matching_shared_version(self):
        self.write("server.txt", "Another uncommitted server change.\n")
        self.assertIn("payload unchanged against v0.17.1", self.assert_passes())

    def test_changed_skill_requires_version_advance(self):
        self.write(PLUGIN / "skills/setup/SKILL.md", "Changed setup instructions.\n")
        self.assert_rejected("does not advance 0.17.1")
        self.write_versions("0.18.0")
        self.assertIn("0.18.0", self.assert_passes())

    def test_untracked_shipped_file_requires_version_advance(self):
        self.write(PLUGIN / "scripts/check-release.sh", "#!/bin/sh\nexit 0\n")
        self.assert_rejected("Plugin payload changed")
        self.write_versions("0.18.0")
        self.assert_passes()

    def test_deleted_shipped_file_requires_version_advance(self):
        (self.repo / PLUGIN / "README.md").unlink()
        self.assert_rejected("Plugin payload changed")

    def test_executable_mode_change_requires_version_advance(self):
        path = self.repo / PLUGIN / "skills/setup/SKILL.md"
        path.chmod(0o755)
        self.assert_rejected("Plugin payload changed")

    def test_missing_manifest_rejected(self):
        (self.repo / PLUGIN / MANIFESTS[1]).unlink()
        self.assert_rejected("Missing current plugin manifest")

    def test_manifest_versions_must_match(self):
        self.write(PLUGIN / MANIFESTS[1], '{"name":"mcp-zap-connect","version":"0.18.0"}')
        self.assert_rejected("manifest versions must match")

    def test_plugin_version_must_match_project_version(self):
        self.write_versions("0.18.0", project="0.19.0")
        self.assert_rejected("must match project version 0.19.0")

    def test_desktop_metadata_must_agree_with_shared_version(self):
        for name, data in (
            ("manifest.json", {"version": "0.1.1"}),
            ("package.json", {"version": "0.1.1"}),
            ("package-lock.json", {"version": "0.1.1", "packages": {"": {"version": "0.18.0"}}}),
            ("package-lock.json", {"version": "0.18.0", "packages": {"": {"version": "0.1.1"}}}),
        ):
            with self.subTest(name=name, data=data):
                self.write_versions("0.18.0")
                self.write(DESKTOP / name, json.dumps(data))
                self.assert_rejected(f"Desktop version in {DESKTOP / name} must match project version 0.18.0")

    def test_missing_and_unreadable_desktop_metadata_rejected(self):
        path = self.repo / DESKTOP / "package-lock.json"
        path.unlink()
        self.assert_rejected(f"Missing or invalid Desktop version metadata in {DESKTOP / path.name}")
        for invalid in ("{broken", "[]", '{"version":"0.17.1","packages":{}}'):
            with self.subTest(content=invalid):
                path.write_text(invalid)
                self.assert_rejected(f"Missing or invalid Desktop version metadata in {DESKTOP / path.name}")

    def test_desktop_only_changes_require_a_new_release(self):
        self.write_versions("0.18.0")
        self.commit("Shared desktop release")
        self.git("tag", "v0.18.0")
        self.write("server.txt", "After the release.\n")
        self.commit("Unrelated change")
        self.write(DESKTOP / "test/unit.js", "// Only used by the Desktop test suite.\n")
        self.assertIn("payload unchanged", self.assert_passes())
        for path in (DESKTOP / "server/mcp.js", "docker-compose.yml"):
            with self.subTest(path=path):
                added = self.write(path, "Changed packaged content.\n")
                self.assert_rejected("does not advance 0.18.0")
                self.assert_rejected("differs from existing local tag v0.18.0", "--base-ref", "v0.17.1")
                added.unlink()
        self.write(DESKTOP / "SETUP.md", "Updated Desktop setup guide.\n")
        self.write_versions("0.18.1")
        self.assert_passes()

    def test_old_scanner_pin_or_setup_checkout_rejected_with_new_plugin(self):
        self.write_versions("0.18.0")
        self.write(PLUGIN / "scripts/check-scanner-release.sh", "SCANNER_RELEASE_TAG='v0.17.1'\n")
        self.assert_rejected("compatibility check must select v0.18.0")
        self.write_versions("0.18.0")
        self.write(PLUGIN / "skills/setup-local-scanner/SKILL.md", "  git clone --branch v0.17.1 --single-branch example\n")
        self.assert_rejected("setup must clone the matching scanner tag v0.18.0")

    def test_missing_ambiguous_and_invalid_project_versions_rejected(self):
        for source in ("", "version = project.findProperty('releaseVersion')\n",
                       "version = '0.18.0'\nversion = '0.19.0'\n",
                       "version = '0.18.0'\nversion = calculateVersion()\n",
                       "version = 'not-semver'\n"):
            with self.subTest(source=source):
                self.write("build.gradle", source)
                self.assert_rejected("project version" if "not-semver" not in source else "valid SemVer")
        (self.repo / "build.gradle").unlink()
        self.assert_rejected("build.gradle")

    def test_legacy_plugin_transition_must_advance_repository_version(self):
        self.write_versions("0.5.0", project="0.17.1", desktop="0.1.0")
        self.commit("Legacy independent plugin version")
        self.git("tag", "legacy-baseline")
        self.write_versions("0.17.1")
        self.assert_rejected("does not advance repository version 0.17.1", "--base-ref", "legacy-baseline")
        self.write_versions("0.18.0")
        self.assert_passes("--base-ref", "legacy-baseline")

    def test_manifest_name_must_match_plugin_identity(self):
        self.write(PLUGIN / MANIFESTS[1], '{"name":"another-plugin","version":"0.17.1"}')
        self.assert_rejected("Unexpected plugin name")

    def test_invalid_json_and_nonobject_manifest_rejected(self):
        for content in ("{broken", "[]"):
            with self.subTest(content=content):
                self.write(PLUGIN / MANIFESTS[1], content)
                self.assert_rejected("plugin manifest" if content == "{broken" else "plugin name")

    def test_invalid_semver_rejected(self):
        for version in (None, 5, "0.5", "v0.18.0", "00.18.0", "0.18.0-01", "0.18.0+", "0.18.0\n"):
            with self.subTest(version=version):
                self.write_versions(version)
                self.assert_rejected("valid SemVer")

    def test_version_regression_rejected(self):
        self.write_versions("0.16.9")
        self.assert_rejected("does not advance 0.17.1")

    def test_build_metadata_is_not_a_version_advance(self):
        self.write_versions("0.17.1+new-build")
        self.assert_rejected("does not advance 0.17.1")

    def test_semver_prerelease_precedence(self):
        self.write_versions("0.19.0-rc.2")
        self.commit("Prerelease baseline")
        self.git("tag", "preview-baseline")
        for version, accepted in (("0.19.0-rc.10", True), ("0.19.0", True),
                                  ("0.19.0-rc.1", False), ("0.19.0-2", False)):
            with self.subTest(version=version):
                self.write_versions(version)
                if accepted:
                    self.assert_passes("--base-ref", "preview-baseline")
                else:
                    self.assert_rejected("does not advance", "--base-ref", "preview-baseline")

    def test_dependencies_caches_and_gitignored_files_are_not_shipped_payload(self):
        for path in ("node_modules/dependency/index.js", "__pycache__/cache.pyc", ".git/temporary"):
            self.write(PLUGIN / path, "Generated data\n")
        self.write(".gitignore", "local-private-file\n")
        self.write(PLUGIN / "local-private-file", "Not part of the package\n")
        self.assert_passes()

    def test_symlinked_payload_is_rejected_without_reading_target(self):
        (self.repo / PLUGIN / "linked-file").symlink_to(self.repo / "server.txt")
        self.assert_rejected("Linked plugin paths")

    def test_symlinked_parent_directory_is_rejected(self):
        setup = self.repo / PLUGIN / "skills/setup"
        (setup / "SKILL.md").unlink()
        setup.rmdir()
        external = self.repo / "external"
        external.mkdir()
        (external / "SKILL.md").write_text("Not plugin-owned.\n")
        setup.symlink_to(external, target_is_directory=True)
        self.assert_rejected("Linked plugin paths")

    def test_default_skips_release_tag_at_head(self):
        self.write(PLUGIN / "skills/setup/SKILL.md", "Changed without version bump.\n")
        self.commit("Candidate release")
        self.git("tag", "v0.17.2")
        self.assert_rejected("changed since v0.17.1")

    def test_clean_shared_release_tag_at_head_passes(self):
        self.write_versions("0.18.0")
        self.commit("Shared version release")
        self.git("tag", "v0.18.0")
        self.assert_passes()

    def test_dirty_payload_at_release_tag_requires_new_version(self):
        self.write_versions("0.18.0")
        self.commit("Shared version release")
        self.git("tag", "v0.18.0")
        self.write(PLUGIN / "skills/setup/SKILL.md", "Changed after tagging.\n")
        self.assert_rejected("differs from existing local tag v0.18.0")

    def test_existing_tag_cannot_be_bypassed_with_older_baseline(self):
        self.write_versions("0.18.0")
        self.commit("Shared version release")
        self.git("tag", "v0.18.0")
        self.write(PLUGIN / "skills/setup/SKILL.md", "Changed after tagging.\n")
        self.commit("Another commit using the same version")
        self.assert_rejected("differs from existing local tag v0.18.0", "--base-ref", "v0.17.1")

    def test_release_step_enforces_tag_project_and_plugin_versions(self):
        workflow = (SCRIPT.parents[1] / ".github/workflows/release.yml").read_text()
        step = workflow.split("      - name: Verify release tag, commit, and project version\n", 1)[1]
        body = textwrap.dedent(step.split("        run: |\n", 1)[1].split("\n      - name:", 1)[0])
        self.write("scripts/check-plugin-version.py", SCRIPT.read_text())
        self.write_versions("0.18.0")
        self.commit("Shared version release")
        self.git("tag", "v0.18.0")
        self.git("tag", "v0.18.1")
        self.git("update-ref", "refs/remotes/origin/main", "HEAD")

        def run_step(tag):
            before = (self.git("rev-parse", "HEAD"), self.git("ls-files", "--stage", "-z"),
                      self.git("status", "--porcelain=v1", "-z"))
            result = subprocess.run(
                ["bash", "-c", body], cwd=self.repo, capture_output=True, text=True,
                env={**os.environ, "RELEASE_VERSION": tag,
                     "EXPECTED_COMMIT": self.git("rev-parse", "HEAD").decode().strip()},
                check=False,
            )
            after = (self.git("rev-parse", "HEAD"), self.git("ls-files", "--stage", "-z"),
                     self.git("status", "--porcelain=v1", "-z"))
            self.assertEqual(before, after, "Release verification must not modify the checkout")
            return result

        valid = run_step("v0.18.0")
        self.assertEqual(valid.returncode, 0, valid.stderr)
        self.assertIn("project and manifests agree", valid.stdout)
        self.write_versions("0.19.0", project="0.18.0")
        mismatch = run_step("v0.18.0")
        self.assertNotEqual(mismatch.returncode, 0)
        self.assertIn("must match project version", mismatch.stderr)
        self.write_versions("0.18.0")
        wrong_tag = run_step("v0.18.1")
        self.assertNotEqual(wrong_tag.returncode, 0)
        self.assertIn("Release tag must be v0.18.0", wrong_tag.stderr)

    def test_default_uses_highest_preceding_stable_tag(self):
        self.write_versions("0.18.0")
        self.commit("Second release")
        self.git("tag", "v0.18.0")
        self.git("tag", "v0.99.0-rc.1")
        self.write("server.txt", "Later server change.\n")
        self.commit("After second release")
        self.assertIn("against v0.18.0", self.assert_passes())
        self.assertIn("against v0.17.1", self.assert_passes("--base-ref", "v0.17.1"))

    def test_default_uses_release_merge_not_in_dev_ancestry(self):
        self.git("tag", "-d", "v0.17.1")
        self.git("tag", "v0.17.0", "HEAD~1")
        self.git("branch", "dev")
        self.git("checkout", "-qb", "release-main", "HEAD~1")
        self.write_versions("0.18.0")
        self.commit("Plugin version on main")
        self.git("-c", "commit.gpgsign=false", "merge", "--no-ff", "dev", "-m", "Release merge")
        self.git("tag", "v0.18.0")
        self.git("checkout", "-q", "dev")
        release_commit = self.git("rev-parse", "v0.18.0").strip()
        self.assertNotIn(release_commit, self.git("rev-list", "HEAD").splitlines())
        self.assert_rejected("does not advance 0.18.0")
        self.write_versions("0.18.1")
        self.assertIn("against v0.18.0", self.assert_passes())

    def test_no_baseline_fails_instead_of_skipping_version_check(self):
        self.git("tag", "-d", "v0.17.1")
        self.assert_rejected("No other local vX.Y.Z tag")
        self.assert_passes("--base-ref", "HEAD")

    def test_missing_explicit_ref_fails(self):
        self.assert_rejected("Git inspection failed", "--base-ref", "missing-release")

    def test_incomplete_baseline_manifests_fail(self):
        (self.repo / PLUGIN / MANIFESTS[2]).unlink()
        self.commit("Incomplete baseline")
        self.git("tag", "incomplete-baseline")
        self.write_versions("0.18.0")
        self.assert_rejected("Missing baseline plugin manifest", "--base-ref", "incomplete-baseline")


if __name__ == "__main__":
    unittest.main()
