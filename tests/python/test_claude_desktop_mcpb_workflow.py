"""Exercise release guards with local fixtures; never call GitHub or a scanner."""

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/claude-desktop-mcpb.yml"
SOURCE = "a" * 40
OTHER_SOURCE = "b" * 40
TAG = "v0.17.0"
BASE = "mcp-zap-connect-claude-desktop-0.1.0"
FILES = [f"{BASE}.mcpb", f"{BASE}.mcpb.sha256",
         f"{BASE}-setup.md", f"{BASE}-setup.md.sha256"]


def load_workflow(path):
    # BaseLoader preserves GitHub's `on` key rather than interpreting YAML 1.1 booleans.
    return yaml.load(path.read_text(), Loader=yaml.BaseLoader)


WORKFLOW_DATA = load_workflow(WORKFLOW)
RELEASE_WORKFLOW_DATA = load_workflow(ROOT / ".github/workflows/release.yml")


def step(job, step_id):
    workflow = RELEASE_WORKFLOW_DATA if job == "docker-release" else WORKFLOW_DATA
    return next(item for item in workflow["jobs"][job]["steps"]
                if item.get("id") == step_id)


FAKE_GIT = """#!{python}
import os, sys
args = sys.argv[1:]
if args == ['rev-parse', 'HEAD']:
    print(os.environ['FIXTURE_SOURCE'])
elif args[:2] == ['rev-parse', '--verify']:
    print(os.environ.get('FIXTURE_TAG_SOURCE', os.environ['FIXTURE_SOURCE']))
elif args[:2] == ['merge-base', '--is-ancestor']:
    sys.exit(0 if os.environ.get('FIXTURE_ON_MAIN', 'true') == 'true' else 1)
else:
    raise SystemExit('Unexpected git fixture request: ' + repr(args))
"""


FAKE_GH = """#!{python}
import json, os, pathlib, sys
from urllib.parse import parse_qs, urlparse
args = sys.argv[1:]
state_path = pathlib.Path(os.environ['FIXTURE_GH_STATE'])
state = json.loads(state_path.read_text())
if not args or args[0] != 'api':
    raise SystemExit('Unexpected gh fixture request: ' + repr(args))
endpoint_arg = next((arg for arg in args[1:] if arg.startswith(('repos/', 'https://'))), '')
if '--method' in args:
    assert args[args.index('--method') + 1] == 'POST'
    endpoint = urlparse(args[-1])
    assert endpoint.netloc == 'uploads.github.com'
    assert endpoint.path == '/repos/example/scanner/releases/42/assets'
    name = parse_qs(endpoint.query)['name'][0]
    assert name not in [item['name'] for item in state['assets']]
    payload = pathlib.Path(args[args.index('--input') + 1])
    state['uploads'].append(name)
    state['assets'].append({{'name': name, 'state': 'uploaded', 'size': payload.stat().st_size}})
    if os.environ.get('FIXTURE_PUBLISH_AFTER_UPLOAD') == 'true':
        state['draft'] = False
    state_path.write_text(json.dumps(state))
elif '/commits/' in endpoint_arg:
    assert '/commits/refs%2Ftags%2F' in endpoint_arg
    after_upload = os.environ.get('FIXTURE_REMOTE_TAG_SOURCE_AFTER_UPLOAD') if state['uploads'] else None
    print(after_upload or os.environ.get('FIXTURE_REMOTE_TAG_SOURCE', os.environ['FIXTURE_SOURCE']))
elif '/compare/' in endpoint_arg:
    print(os.environ.get('FIXTURE_REMOTE_MAIN_BASE', os.environ['FIXTURE_SOURCE']))
elif endpoint_arg == 'repos/example/scanner/releases?per_page=100':
    assert '--paginate' in args and '--slurp' in args
    mode = os.environ.get('FIXTURE_RELEASE_LOOKUP', 'found')
    releases = [] if mode == 'missing' else [state] * (2 if mode == 'duplicate' else 1)
    # Put the matching draft on a later page, including unrelated releases.
    print(json.dumps([[{{'tag_name': 'v0.1.0', 'draft': False}}], releases]))
elif endpoint_arg == 'repos/example/scanner/releases/42':
    print(json.dumps(state))
else:
    raise SystemExit('Unexpected gh fixture request: ' + repr(args))
"""


class ClaudeDesktopWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.bin = self.directory / "bin"
        self.bin.mkdir()
        for name, script in (("git", FAKE_GIT), ("gh", FAKE_GH)):
            executable = self.bin / name
            executable.write_text(script.format(python=sys.executable))
            executable.chmod(0o700)
        self.state_path = self.directory / "github-state.json"
        self.write_state()
        self.environment = os.environ.copy()
        self.environment.update({
            "PATH": str(self.bin) + os.pathsep + os.environ["PATH"],
            "DRAFT_RELEASE_TAG": TAG,
            "GITHUB_EVENT_NAME": "workflow_dispatch",
            "GITHUB_REF": "refs/heads/main",
            "GITHUB_SHA": SOURCE,
            "GITHUB_REPOSITORY": "example/scanner",
            "GITHUB_OUTPUT": str(self.directory / "output"),
            "GITHUB_STEP_SUMMARY": str(self.directory / "summary"),
            "SOURCE_COMMIT": SOURCE,
            "PACKAGE_BASE": BASE,
            "RELEASE_ID": "42",
            "RELEASE_VERSION": TAG,
            "GH_TOKEN": "unused-fixture-token",
            "FIXTURE_SOURCE": SOURCE,
            "FIXTURE_GH_STATE": str(self.state_path),
        })
        self.write_versions()
        for filename in (FILES[0], FILES[2]):
            content = f"fixture {filename}\n".encode()
            (self.directory / filename).write_bytes(content)
            (self.directory / f"{filename}.sha256").write_text(
                f"{hashlib.sha256(content).hexdigest()}  {filename}\n")

    def write_state(self, **overrides):
        state = {"id": 42, "tag_name": TAG, "draft": True, "prerelease": False, "immutable": False,
                 "assets": [], "uploads": []}
        state.update(overrides)
        self.state_path.write_text(json.dumps(state))

    def write_versions(self, manifest_version="0.1.0", lock_version="0.1.0", scanner_version="0.17.0"):
        package = self.directory / "packaging/claude-desktop"
        package.mkdir(parents=True, exist_ok=True)
        (package / "package.json").write_text(json.dumps({"version": "0.1.0"}))
        (package / "manifest.json").write_text(json.dumps({"version": manifest_version}))
        (package / "package-lock.json").write_text(json.dumps({
            "version": lock_version, "packages": {"": {"version": lock_version}},
        }))
        (self.directory / "build.gradle").write_text(f"version = '{scanner_version}'\n")

    def run_step(self, job, step_id, *, success=True, environment=None):
        env = self.environment | (environment or {})
        result = subprocess.run(
            ["bash", "-c", step(job, step_id)["run"]], cwd=self.directory,
            env=env, text=True, capture_output=True, timeout=20,
        )
        if success:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        return result

    def uploads(self):
        return json.loads(self.state_path.read_text())["uploads"]

    def test_build_permissions_and_privileged_job_boundary(self):
        self.assertEqual(WORKFLOW_DATA["permissions"], {"contents": "read"})
        self.assertEqual(WORKFLOW_DATA["jobs"]["build"]["permissions"], {"contents": "read"})
        self.assertNotIn("GH_TOKEN", step("build", "package").get("env", {}))
        self.assertNotIn("gh api", step("build", "package")["run"])
        attach = WORKFLOW_DATA["jobs"]["attach-draft"]
        self.assertEqual(attach["permissions"], {"contents": "write"})
        self.assertEqual(attach["needs"], "build")
        self.assertIn("github.event_name == 'workflow_dispatch'", attach["if"])
        self.assertIn("github.ref == 'refs/heads/main'", attach["if"])
        self.assertIn("inputs.draft_release_tag != ''", attach["if"])
        self.assertEqual(len(attach["steps"]), 2)
        download = attach["steps"][0]
        self.assertTrue(download["uses"].startswith("actions/download-artifact@"))
        self.assertEqual(download["with"]["artifact-ids"], "${{ needs.build.outputs.artifact-id }}")
        self.assertEqual(download["with"]["merge-multiple"], "true")
        self.assertNotRegex(step("attach-draft", "attach")["run"], r"(?m)^\s*(npm|node|docker|git)\s")
        for job in list(WORKFLOW_DATA["jobs"].values()) + list(RELEASE_WORKFLOW_DATA["jobs"].values()):
            for item in job["steps"]:
                if "uses" in item:
                    self.assertRegex(item["uses"], r"@[0-9a-f]{40}$")

    def test_packaging_runs_only_for_relevant_main_pushes_or_manual_requests(self):
        events = WORKFLOW_DATA["on"]
        self.assertEqual(set(events), {"push", "workflow_dispatch"})
        self.assertEqual(events["push"]["branches"], ["main"])
        self.assertIn("packaging/claude-desktop/**", events["push"]["paths"])
        self.assertIn(".github/workflows/claude-desktop-mcpb.yml", events["push"]["paths"])

    def test_packaging_builds_exact_four_asset_set(self):
        steps = WORKFLOW_DATA["jobs"]["build"]["steps"]
        commands = [item.get("run") for item in steps]
        self.assertIn("npm ci --ignore-scripts --no-audit --no-fund", commands)
        self.assertIn("npm test", commands)
        self.assertIn("npm run build", commands)
        node = next(item for item in steps if item.get("uses", "").startswith("actions/setup-node@"))
        self.assertEqual(node["with"]["node-version"], "22")
        checkout = next(item for item in steps if item.get("uses", "").startswith("actions/checkout@"))
        self.assertEqual(checkout["with"]["persist-credentials"], "false")
        paths = step("build", "upload")["with"]["path"].splitlines()
        self.assertEqual(paths, ["build/claude-desktop/" + filename.replace(
            BASE, "${{ steps.package.outputs.base }}") for filename in FILES])

    def test_ordinary_ci_keeps_package_tests_without_distribution_builds(self):
        ci = load_workflow(ROOT / ".github/workflows/ci.yml")
        self.assertIn("dev", ci["on"]["pull_request"]["branches"])
        self.assertIn("main", ci["on"]["pull_request"]["branches"])
        self.assertEqual(ci["on"]["push"]["branches"], ["main"])
        steps = ci["jobs"]["build"]["steps"]
        node = next(item for item in steps if item.get("uses", "").startswith("actions/setup-node@"))
        self.assertEqual(node["with"]["node-version"], "22")
        package_steps = [item for item in steps
                         if item.get("working-directory") == "packaging/claude-desktop"]
        self.assertEqual([item["run"] for item in package_steps],
                         ["npm ci --ignore-scripts --no-audit --no-fund", "npm test"])
        for item in steps:
            self.assertNotIn("npm run build", item.get("run", ""))
            self.assertNotIn("build/claude-desktop", item.get("with", {}).get("path", ""))

    def test_every_shell_block_has_valid_bash_syntax(self):
        for job in list(WORKFLOW_DATA["jobs"].values()) + list(RELEASE_WORKFLOW_DATA["jobs"].values()):
            for item in job["steps"]:
                if "run" in item:
                    with self.subTest(step=item.get("name")):
                        result = subprocess.run(["bash", "-n"], input=item["run"],
                                                text=True, capture_output=True, timeout=5)
                        self.assertEqual(result.returncode, 0, result.stderr)

    def test_release_mode_requires_manual_main_and_scanner_namespace(self):
        self.run_step("build", "release-mode")
        self.run_step("build", "release-mode", environment={"DRAFT_RELEASE_TAG": ""})
        cases = [{"GITHUB_EVENT_NAME": "push"}, {"GITHUB_REF": "refs/heads/dev"},
                 {"DRAFT_RELEASE_TAG": "claude-desktop-v0.1.0"},
                 {"DRAFT_RELEASE_TAG": "v00.17.0"}, {"DRAFT_RELEASE_TAG": "v0.17.0/other"}]
        for env in cases:
            with self.subTest(environment=env):
                self.run_step("build", "release-mode", success=False, environment=env)

    @unittest.skipUnless(shutil.which("node"), "Node is required for package version guards")
    def test_source_guard_accepts_scanner_tag_independent_of_bundle_version(self):
        self.run_step("build", "package")
        self.assertIn(f"base={BASE}\ncommit={SOURCE}\n",
                      (self.directory / "output").read_text())
        self.run_step("build", "package", environment={"DRAFT_RELEASE_TAG": ""})

    @unittest.skipUnless(shutil.which("node"), "Node is required for package version guards")
    def test_source_guard_rejects_mismatched_version_tag_source_or_main(self):
        for env in ({"DRAFT_RELEASE_TAG": "v0.18.0"},
                    {"FIXTURE_TAG_SOURCE": OTHER_SOURCE}, {"FIXTURE_ON_MAIN": "false"},
                    {"DRAFT_RELEASE_TAG": "", "GITHUB_SHA": OTHER_SOURCE}):
            with self.subTest(environment=env):
                self.run_step("build", "package", success=False, environment=env)
        for manifest_version, lock_version in (("0.2.0", "0.1.0"), ("0.1.0", "0.2.0")):
            with self.subTest(manifest=manifest_version, lock=lock_version):
                self.write_versions(manifest_version, lock_version)
                self.run_step("build", "package", success=False)

    @unittest.skipUnless(shutil.which("node") and shutil.which("jq") and shutil.which("sha256sum"),
                         "Node, jq and sha256sum required")
    def test_scanner_prerelease_uses_the_same_draft_preparation_flow(self):
        tag = "v0.17.0-rc.1"
        self.write_versions(scanner_version=tag[1:])
        self.write_state(tag_name=tag, prerelease=True)
        env = {"DRAFT_RELEASE_TAG": tag}
        self.run_step("build", "release-mode", environment=env)
        self.run_step("build", "package", environment=env)
        self.run_step("attach-draft", "attach", environment=env)
        self.assertEqual(self.uploads(), FILES)

    @unittest.skipUnless(shutil.which("sha256sum"), "sha256sum is required for asset checks")
    def test_asset_check_rejects_tampering_and_misdirected_checksum(self):
        self.run_step("build", "checksums")
        original = (self.directory / FILES[0]).read_bytes()
        (self.directory / FILES[0]).write_bytes(b"tampered")
        self.run_step("build", "checksums", success=False)
        (self.directory / FILES[0]).write_bytes(original)
        checksum_path = self.directory / FILES[1]
        checksum_path.write_text(checksum_path.read_text().replace(FILES[0], FILES[2]))
        self.run_step("build", "checksums", success=False)

    @unittest.skipUnless(shutil.which("jq") and shutil.which("sha256sum"), "jq and sha256sum required")
    def test_attachment_uploads_exactly_four_verified_files(self):
        self.run_step("attach-draft", "attach")
        self.assertEqual(self.uploads(), FILES)
        self.assertIn(f"from `{SOURCE}`", (self.directory / "summary").read_text())

    @unittest.skipUnless(shutil.which("jq") and shutil.which("sha256sum"), "jq and sha256sum required")
    def test_attachment_refuses_existing_assets_and_non_draft_releases_before_upload(self):
        for overrides in ({"assets": [{"name": FILES[2]}]}, {"draft": False},
                          {"immutable": True}, {"tag_name": "v9.9.9"}):
            with self.subTest(state=overrides):
                self.write_state(**overrides)
                self.run_step("attach-draft", "attach", success=False)
                self.assertEqual(self.uploads(), [])

    @unittest.skipUnless(shutil.which("jq"), "jq required")
    def test_attachment_requires_exactly_one_matching_draft_across_all_pages(self):
        for lookup in ("missing", "duplicate"):
            with self.subTest(lookup=lookup):
                self.run_step("attach-draft", "attach", success=False,
                              environment={"FIXTURE_RELEASE_LOOKUP": lookup})
                self.assertEqual(self.uploads(), [])

    @unittest.skipUnless(shutil.which("jq") and shutil.which("sha256sum"), "jq and sha256sum required")
    def test_attachment_rechecks_remote_tag_and_main_before_upload(self):
        for env in ({"FIXTURE_REMOTE_TAG_SOURCE": OTHER_SOURCE},
                    {"FIXTURE_REMOTE_MAIN_BASE": OTHER_SOURCE}):
            with self.subTest(environment=env):
                self.run_step("attach-draft", "attach", success=False, environment=env)
                self.assertEqual(self.uploads(), [])

    @unittest.skipUnless(shutil.which("jq") and shutil.which("sha256sum"), "jq and sha256sum required")
    def test_attachment_does_not_report_success_if_tag_changes_during_upload(self):
        self.run_step("attach-draft", "attach", success=False,
                      environment={"FIXTURE_REMOTE_TAG_SOURCE_AFTER_UPLOAD": OTHER_SOURCE})
        self.assertEqual(self.uploads(), FILES)
        self.assertFalse((self.directory / "summary").exists())

    @unittest.skipUnless(shutil.which("jq") and shutil.which("sha256sum"), "jq and sha256sum required")
    def test_attachment_stops_if_release_is_published_between_uploads(self):
        self.run_step("attach-draft", "attach", success=False,
                      environment={"FIXTURE_PUBLISH_AFTER_UPLOAD": "true"})
        self.assertEqual(self.uploads(), [FILES[0]])

    @unittest.skipUnless(shutil.which("jq") and shutil.which("sha256sum"), "jq and sha256sum required")
    def test_attachment_rechecks_checksum_before_any_upload(self):
        (self.directory / FILES[2]).write_text("modified guide")
        self.run_step("attach-draft", "attach", success=False)
        self.assertEqual(self.uploads(), [])

    def test_scanner_release_checks_assets_before_building_or_publishing_images(self):
        release = RELEASE_WORKFLOW_DATA
        self.assertEqual(release["on"]["release"]["types"], ["published"])
        job = release["jobs"]["docker-release"]
        self.assertNotIn("if", job)
        steps = job["steps"]
        guard = next(i for i, item in enumerate(steps) if item.get("id") == "desktop-assets")
        build = next(i for i, item in enumerate(steps) if item.get("run", "").startswith("./gradlew build"))
        self.assertLess(guard, build)
        self.assertNotIn("--method", step("docker-release", "desktop-assets")["run"])

    @unittest.skipUnless(shutil.which("jq") and shutil.which("python3"), "jq and Python required")
    def test_scanner_publication_requires_complete_uploaded_four_asset_set(self):
        assets = [{"name": name, "state": "uploaded", "size": 128} for name in FILES]
        self.write_state(draft=False, immutable=True, assets=assets)
        self.run_step("docker-release", "desktop-assets")
        for overrides in ([{"assets": [item for item in assets if item["name"] != name]} for name in FILES] +
                         [{"assets": assets + [assets[0]]},
                          {"assets": [dict(item, size=0) for item in assets]},
                          {"assets": [dict(item, state="starter") for item in assets]},
                          {"draft": True}, {"immutable": False}, {"tag_name": "v9.9.9"}]):
            with self.subTest(state=overrides):
                state = {"draft": False, "immutable": True, "assets": assets} | overrides
                self.write_state(**state)
                self.run_step("docker-release", "desktop-assets", success=False)
        self.assertEqual(self.uploads(), [])


if __name__ == "__main__":
    unittest.main()
