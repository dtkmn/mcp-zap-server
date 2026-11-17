"""Run the packaged release check against private Git and Docker fixtures only."""

import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
CHECKER = ROOT / "plugins/mcp-zap-connect/scripts/check-scanner-release.sh"
SECRET = "fixture-private-secret"
FAKE_CURL = '''#!PYTHON
import os, sys
args = sys.argv[1:]
assert args[0] == "--disable"
assert args[-1] == "https://api.github.com/repos/dtkmn/mcp-zap-server/commits/refs%2Ftags%2Fv0.18.0"
assert args[args.index("--header") + 1] == "Accept: application/vnd.github.sha"
assert args[args.index("--proto") + 1] == "=https"
assert "--max-time" in args and "--fail" in args and "--location" not in args
if os.environ.get("CHECK_FIXTURE_OFFLINE"):
    print("fixture-private-secret", file=sys.stderr)
    sys.exit(22)
print(os.environ["CHECK_FIXTURE_COMMIT"])
'''
FAKE_DOCKER = '''#!PYTHON
import json, os, sys
from pathlib import Path
args = sys.argv[1:]
root = Path(os.environ["CHECK_FIXTURE_ROOT"])
with Path(os.environ["CHECK_FIXTURE_CALLS"]).open("a") as output:
    output.write(json.dumps(args) + "\\n")
if args == ["context", "inspect", "--format", "{{.Endpoints.docker.Host}}"]:
    print(os.environ.get("CHECK_FIXTURE_ENDPOINT", "unix:///fixture/docker.sock"))
    sys.exit(0)
if args[:2] == ["ps", "-a"]:
    assert args == ["ps", "-a", "--no-trunc", "--filter", "label=com.docker.compose.project.working_dir=" + str(root),
                    "--filter", "label=com.docker.compose.service=mcp-server", "--format", "{{.ID}}"]
    if os.environ.get("CHECK_FIXTURE_INVENTORY_FAIL"):
        sys.exit(1)
    print(os.environ.get("CHECK_FIXTURE_IDS", ""))
    sys.exit(0)
if args[:2] == ["inspect", "--type"]:
    assert args == ["inspect", "--type", "container", "--format", "{{.Config.Image}}", "a" * 64]
    print(os.environ["CHECK_FIXTURE_RUNNING_IMAGE"])
    sys.exit(0)
assert args[:3] == ["compose", "--project-directory", str(root)]
assert args[3] == "--env-file" and args[5:7] == ["-f", str(root / "docker-compose.yml")]
if args[7:] == ["ps", "--all", "--quiet", "mcp-server"]:
    print(os.environ.get("CHECK_FIXTURE_PROJECT_IDS", os.environ.get("CHECK_FIXTURE_IDS", "")))
    sys.exit(0)
assert args[7:] == ["config", "--images", "mcp-server"]
if os.environ.get("CHECK_FIXTURE_FAIL"):
    print("fixture-private-secret", file=sys.stderr)
    raise SystemExit(1)
settings = dict(line.split("=", 1) for line in Path(args[4]).read_text().splitlines() if "=" in line)
tag = os.environ.get("IMAGE_TAG", settings.get("IMAGE_TAG", "v0.18.0")) or "v0.18.0"
# Compose includes dependencies even when a service name was supplied.
print(os.environ.get("CHECK_FIXTURE_IMAGE", "zaproxy/zap-stable:2.17.0\\ndtkmn/mcp-zap-server:" + tag))
'''


class PluginSetupReleaseTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="plugin-release-fixture-")
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name).resolve()
        self.checkout = self.directory / "scanner"
        self.checkout.mkdir()
        self.caller = self.directory / "unrelated-cwd"
        self.caller.mkdir()
        self.clients = self.directory / "private-clients"
        self.clients.mkdir(mode=0o700)
        (self.clients / "settings.json").write_text('{"key":"' + SECRET + '"}\n')
        self.environment = {
            "PATH": "/usr/bin:/bin", "LC_ALL": "C", "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_CONFIG_GLOBAL": "/dev/null", "GIT_AUTHOR_NAME": "Fixture",
            "GIT_AUTHOR_EMAIL": "fixture@example.invalid", "GIT_COMMITTER_NAME": "Fixture",
            "GIT_COMMITTER_EMAIL": "fixture@example.invalid",
        }
        self.git("init", "-q", "--object-format=sha1")
        contents = {
            ".env.example": "IMAGE_TAG=v0.18.0\nMCP_API_KEY=placeholder\n",
            "docker-compose.yml": "services:\n  mcp-server:\n    image: dtkmn/mcp-zap-server:${IMAGE_TAG:-v0.18.0}\n",
            "build.gradle": "version = '0.18.0'\n",
            ".mcp/server.json": '{"version":"0.18.0"}\n',
            "docs/getting-started/SELF_SERVE_FIRST_RUN.md": "# Released setup\n",
            "README.md": "Unrelated documentation\n",
        }
        for name in ("bootstrap-local.sh", "self-serve-doctor.sh", "scanner-readiness.sh"):
            contents["bin/" + name] = "#!/bin/bash\nexit 99\n"
        for name, content in contents.items():
            path = self.checkout / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
            if name.startswith("bin/"):
                path.chmod(0o700)
        self.git("add", ".")
        self.git("commit", "-qm", "Fixture release")
        self.commit = self.git("rev-parse", "HEAD").stdout.strip()
        self.git("tag", "v0.18.0")
        self.checker = CHECKER
        fake_bin = self.directory / "fake-bin"
        fake_bin.mkdir()
        for name, program in (("docker", FAKE_DOCKER), ("curl", FAKE_CURL)):
            executable = fake_bin / name
            executable.write_text(program.replace("#!PYTHON", "#!" + sys.executable, 1))
            executable.chmod(0o700)
        self.calls = self.directory / "docker-calls.jsonl"
        self.environment.update({
            "PATH": str(fake_bin) + ":/usr/bin:/bin",
            "CHECK_FIXTURE_COMMIT": self.commit,
            "CHECK_FIXTURE_ROOT": str(self.checkout), "CHECK_FIXTURE_CALLS": str(self.calls),
        })

    def git(self, *arguments):
        return subprocess.run(["git", "-C", str(self.checkout), *arguments], env=self.environment,
                              capture_output=True, text=True, check=True, timeout=10)

    def snapshot(self):
        result = {}
        for root in (self.checkout, self.clients):
            for path in root.rglob("*"):
                mode = path.lstat().st_mode
                if path.is_symlink():
                    value = str(path.readlink())
                elif path.is_file():
                    value = hashlib.sha256(path.read_bytes()).hexdigest()
                else:
                    value = "directory"
                result[str(path)] = (mode, value)
        return result

    def check(self, expected="COMPATIBLE", directory=None, docker=True):
        self.calls.unlink(missing_ok=True)
        before = self.snapshot()
        result = subprocess.run(
            ["/bin/bash", str(self.checker), "--directory", str(directory or self.checkout)],
            cwd=self.caller, env=self.environment, capture_output=True, text=True, timeout=10,
        )
        self.assertEqual(result.returncode, 0 if expected == "COMPATIBLE" else 2, result.stdout + result.stderr)
        self.assertTrue(result.stdout.startswith(expected + ":"), result.stdout + result.stderr)
        self.assertEqual(result.stderr, "")
        self.assertNotIn(SECRET, result.stdout + result.stderr)
        self.assertEqual(self.snapshot(), before, "Compatibility checks must not modify installation/client state")
        self.assertEqual(self.calls.exists(), docker)
        if docker:
            calls = [json.loads(line) for line in self.calls.read_text().splitlines()]
            self.assertEqual(sum(call[-3:] == ["config", "--images", "mcp-server"] for call in calls), 1)
            return calls[0]
        return None

    def test_fresh_checkout_uses_verified_template_without_creating_settings(self):
        call = self.check()
        self.assertEqual(call[4], str(self.checkout / ".env.example"))
        self.assertFalse((self.checkout / ".env").exists())

    def test_existing_settings_reports_clients_and_unrelated_edits_are_preserved(self):
        (self.checkout / ".env").write_text("IMAGE_TAG=v0.18.0\nMCP_API_KEY=" + SECRET + "\n")
        (self.checkout / "reports").mkdir()
        (self.checkout / "reports/existing.html").write_text("existing evidence")
        (self.checkout / "README.md").write_text("unrelated local edit")
        call = self.check()
        self.assertEqual(call[4], str(self.checkout / ".env"))

    def test_effective_image_uses_compose_environment_precedence(self):
        env_file = self.checkout / ".env"
        env_file.write_text("IMAGE_TAG=v0.16.0\nMCP_API_KEY=" + SECRET + "\n")
        self.check("IMAGE_MISMATCH")
        self.environment["IMAGE_TAG"] = "v0.18.0"
        self.check()
        env_file.write_text("IMAGE_TAG=v0.18.0\n")
        self.environment["IMAGE_TAG"] = "latest"
        self.check("IMAGE_MISMATCH")
        env_file.unlink()
        self.check("IMAGE_MISMATCH")

    def test_wrong_head_and_same_named_fake_tag_are_rejected(self):
        self.git("commit", "--allow-empty", "-qm", "Different source")
        self.check("RELEASE_MISMATCH", docker=False)
        self.git("tag", "-f", "v0.18.0")
        self.check("RELEASE_MISMATCH", docker=False)
        self.git("checkout", "--detach", self.commit)
        self.check("RELEASE_MISMATCH", docker=False)

    def test_older_installation_is_preserved(self):
        self.git("tag", "-d", "v0.18.0")
        self.git("tag", "v0.17.1")
        (self.checkout / ".env").write_text("IMAGE_TAG=v0.17.1\nMCP_API_KEY=" + SECRET + "\n")
        self.check("RELEASE_MISMATCH", docker=False)

    def test_unavailable_or_invalid_upstream_reference_blocks_setup(self):
        self.environment["CHECK_FIXTURE_OFFLINE"] = "1"
        self.check("RELEASE_UNAVAILABLE", docker=False)
        del self.environment["CHECK_FIXTURE_OFFLINE"]
        for value in ("", SECRET, self.commit + "\n" + self.commit):
            with self.subTest(value=value):
                self.environment["CHECK_FIXTURE_COMMIT"] = value
                self.check("RELEASE_UNAVAILABLE", docker=False)

    def test_existing_container_must_run_the_selected_image(self):
        self.environment["CHECK_FIXTURE_IDS"] = "a" * 64
        self.environment["CHECK_FIXTURE_RUNNING_IMAGE"] = "dtkmn/mcp-zap-server:v0.18.0"
        self.check()
        self.environment["CHECK_FIXTURE_RUNNING_IMAGE"] = "dtkmn/mcp-zap-server:v0.17.1"
        self.check("IMAGE_MISMATCH")

    def test_existing_project_and_selected_directory_must_identify_the_same_container(self):
        for directory_id, project_id in (("", "a" * 64), ("a" * 64, ""), ("a" * 64, "b" * 64)):
            with self.subTest(directory_id=directory_id, project_id=project_id):
                self.environment["CHECK_FIXTURE_IDS"] = directory_id
                self.environment["CHECK_FIXTURE_PROJECT_IDS"] = project_id
                self.check("OWNERSHIP_CONFLICT")

    def test_unavailable_ambiguous_or_remote_inventory_blocks_setup(self):
        self.environment["CHECK_FIXTURE_INVENTORY_FAIL"] = "1"
        self.check("CHECK_FAILED")
        del self.environment["CHECK_FIXTURE_INVENTORY_FAIL"]
        self.environment["CHECK_FIXTURE_IDS"] = "a" * 64 + "\n" + "b" * 64
        self.check("OWNERSHIP_CONFLICT")
        self.environment["DOCKER_HOST"] = "tcp://remote.invalid"
        self.check("REMOTE_DOCKER_UNSUPPORTED")
        self.assertNotIn('"ps"', self.calls.read_text())

    def test_modified_helper_cannot_hide_behind_assume_unchanged(self):
        self.git("update-index", "--assume-unchanged", "bin/bootstrap-local.sh")
        (self.checkout / "bin/bootstrap-local.sh").write_text("#!/bin/bash\necho unexpected\n")
        self.check("MODIFIED_SETUP", docker=False)

    def test_staged_metadata_change_is_rejected_even_with_original_worktree_bytes(self):
        metadata = self.checkout / "build.gradle"
        original = metadata.read_bytes()
        metadata.write_text("version = 'development'\n")
        self.git("add", "build.gradle")
        metadata.write_bytes(original)
        self.check("MODIFIED_SETUP", docker=False)

    def test_linked_and_non_executable_helpers_are_rejected(self):
        helper = self.checkout / "bin/self-serve-doctor.sh"
        helper.chmod(0o600)
        self.check("MODIFIED_SETUP", docker=False)
        helper.chmod(0o700)
        target = self.directory / "external-helper"
        target.write_bytes(helper.read_bytes())
        target.chmod(0o700)
        helper.unlink()
        helper.symlink_to(target)
        self.check("MODIFIED_SETUP", docker=False)

    def test_custom_compose_selection_is_rejected_before_docker(self):
        for key in ("COMPOSE_FILE", "COMPOSE_ENV_FILES", "COMPOSE_PROFILES"):
            with self.subTest(key=key):
                self.environment[key] = "custom"
                self.check("CUSTOM_DEPLOYMENT", docker=False)
                del self.environment[key]
                env_file = self.checkout / ".env"
                env_file.write_text(key + "=custom\nMCP_API_KEY=" + SECRET + "\n")
                self.check("CUSTOM_DEPLOYMENT", docker=False)
                env_file.unlink()
        override = self.checkout / "compose.override.yaml"
        override.write_text("services: {}\n")
        self.check("CUSTOM_DEPLOYMENT", docker=False)

    def test_linked_private_settings_are_preserved_and_rejected(self):
        target = self.clients / "private.env"
        target.write_text("MCP_API_KEY=" + SECRET + "\n")
        (self.checkout / ".env").symlink_to(target)
        self.check("CUSTOM_DEPLOYMENT", docker=False)

    def test_wrong_directory_and_git_environment_do_not_select_another_checkout(self):
        for directory, code in (("relative", "INVALID_DIRECTORY"), ("/", "INVALID_DIRECTORY"),
                                (self.caller, "RELEASE_MISMATCH"),
                                (self.checkout / "bin", "RELEASE_MISMATCH")):
            with self.subTest(directory=directory):
                self.check(code, directory=directory, docker=False)
        self.environment["GIT_DIR"] = str(self.checkout / ".git")
        self.check("ENVIRONMENT_CONFLICT", docker=False)

    def test_compose_failure_and_ambiguous_output_do_not_disclose_private_values(self):
        self.environment["CHECK_FIXTURE_FAIL"] = "1"
        self.check("CONFIGURATION_INVALID")
        del self.environment["CHECK_FIXTURE_FAIL"]
        for image in ("", SECRET,
                      "dtkmn/mcp-zap-server:v0.18.0\ndtkmn/mcp-zap-server:v0.18.0",
                      "dtkmn/mcp-zap-server:v0.18.0\ndtkmn/mcp-zap-server:v0.16.0"):
            with self.subTest(image=image):
                self.environment["CHECK_FIXTURE_IMAGE"] = image
                self.check("IMAGE_MISMATCH")


if __name__ == "__main__":
    unittest.main()
