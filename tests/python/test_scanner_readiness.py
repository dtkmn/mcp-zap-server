"""Exercise pre-scan diagnosis with isolated Docker and doctor fixtures only."""

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
KEY = "private-fixture-mcp-key"
ZAP_KEY = "private-fixture-zap-key"
MCP_ID = "a" * 64
ZAP_ID = "b" * 64

FAKE_DOCKER = r'''#!PYTHON
import json, os, sys, time, subprocess
from pathlib import Path
s = json.loads(Path(os.environ['READINESS_STATE']).read_text())
a = sys.argv[1:]
with Path(os.environ['READINESS_CALLS']).open('a') as f:
    f.write(json.dumps(a) + '\n')
def output(value):
    print(value)
    raise SystemExit(0)
if a == ['context', 'inspect', '--format', '{{.Endpoints.docker.Host}}']:
    if s.get('context_error'): raise SystemExit(1)
    output(s.get('endpoint', 'unix:///fixture/docker.sock'))
if a == ['info']:
    if s.get('docker_orphan'):
        child = subprocess.Popen([sys.executable, '-c', "import os,signal,time; from pathlib import Path; signal.signal(signal.SIGTERM,signal.SIG_IGN); p=Path(os.environ['READINESS_HEARTBEAT']); p.write_text('started'); exec('while True:\\n p.write_text(str(time.time_ns()))\\n time.sleep(0.03)')"])
        Path(os.environ['READINESS_ORPHAN_PID']).write_text(str(child.pid))
        while not Path(os.environ['READINESS_HEARTBEAT']).exists(): time.sleep(0.01)
        time.sleep(30)
    if s.get('docker_hung'): time.sleep(30)
    raise SystemExit(1 if s.get('docker_off') else 0)
if a == ['compose', 'version']:
    raise SystemExit(1 if s.get('compose_missing') else 0)
if a and a[0] == 'ps':
    containers = s['containers']
    if '--filter' in a:
        query = a[a.index('--filter') + 1]
        if query == 'publish=7456':
            containers = [c for c in containers if c['running'] and ':7456;' in c['binding']]
        elif query.startswith('label=com.docker.compose.project='):
            project = query.split('=', 2)[2]
            containers = [c for c in containers if c['project'] == project]
        else: raise SystemExit('Unexpected fixture filter')
    pattern = a[a.index('--format') + 1]
    if pattern == '{{.ID}} {{.Image}}': output('\n'.join(c['id'] + ' ' + c['image'] for c in containers))
    if pattern == '{{.ID}}': output('\n'.join(c['id'] for c in containers))
    raise SystemExit('Unexpected fixture format')
if a[:2] == ['inspect', '--format']:
    c = next(c for c in s['containers'] if c['id'] == a[-1])
    if s.get('disappear'): raise SystemExit(1)
    if a[2].startswith('{{.Config.Image}}'):
        output('\n'.join([c['image'], c['owner'], c['project'], c['service'], c['compose'],
                         str(c['running']).lower(), c['health'], c['binding'],
                         'MCP_API_KEY=' + c['key'], 'MCP_SECURITY_ENABLED=' + c['enabled'],
                         'MCP_SECURITY_MODE=' + c['mode'], 'ZAP_API_KEY=' + c['zap_key']]))
    output('\n'.join([c['owner'], c['service'], str(c['running']).lower(), c['health']]))
raise SystemExit('Forbidden fixture Docker operation: ' + repr(a))
'''

FAKE_DOCTOR = '''#!/bin/bash
set -eu
printf '%s\\n' "$@" > "$READINESS_DOCTOR_ARGS"
test "$MCP_API_KEY" = "$READINESS_EXPECTED_KEY"
test "${BASH_ENV:-}" = ''
test "${ENV:-}" = ''
exit "${READINESS_DOCTOR_EXIT:-0}"
'''


class ScannerReadinessTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="readiness-fixture-")
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name).resolve()
        self.bin = self.directory / "bin"
        self.bin.mkdir()
        self.fake_bin = self.directory / "fake-bin"
        self.fake_bin.mkdir()
        self.script = self.bin / "scanner-readiness.sh"
        shutil.copyfile(ROOT / "bin/scanner-readiness.sh", self.script)
        doctor = self.bin / "self-serve-doctor.sh"
        doctor.write_text(FAKE_DOCTOR)
        doctor.chmod(0o700)
        fake = self.fake_bin / "docker"
        fake.write_text(FAKE_DOCKER.replace("#!PYTHON", "#!" + sys.executable, 1))
        fake.chmod(0o700)
        self.env_file = self.directory / ".env"
        self.env_file.write_text(f"MCP_API_KEY={KEY}\nZAP_API_KEY={ZAP_KEY}\nCOMPOSE_PROJECT_NAME=fixture\n")
        self.env_file.chmod(0o600)
        (self.directory / "docker-compose.yml").write_text("services: fixture\n")
        self.state_file = self.directory / "state.json"
        self.calls_file = self.directory / "calls.jsonl"
        self.doctor_args = self.directory / "doctor-args"
        self.state = {"containers": [self.container(MCP_ID, "mcp-server"), self.container(ZAP_ID, "zap")]}
        self.environment = os.environ.copy()
        for key in ["DOCKER_HOST", "DOCKER_CONTEXT", "COMPOSE_FILE", "MCP_API_KEY", "MCP_ZAP_API_KEY", "BASH_ENV", "ENV", "CDPATH"]:
            self.environment.pop(key, None)
        self.environment.update({"PATH": str(self.fake_bin) + ":/usr/bin:/bin:/usr/sbin:/sbin",
                                 "READINESS_STATE": str(self.state_file), "READINESS_CALLS": str(self.calls_file),
                                 "READINESS_DOCTOR_ARGS": str(self.doctor_args), "READINESS_EXPECTED_KEY": KEY})
        self.environment['READINESS_HEARTBEAT'] = str(self.directory / 'heartbeat')
        self.environment['READINESS_ORPHAN_PID'] = str(self.directory / 'orphan.pid')

    def container(self, identifier, service):
        return {"id": identifier, "image": "dtkmn/mcp-zap-server:v0.16.0" if service == "mcp-server" else "zaproxy/zap-stable:2.17.0",
                "owner": str(self.directory), "project": "fixture", "service": service,
                "compose": str(self.directory / "docker-compose.yml"), "running": True, "health": "healthy",
                "binding": "7456/tcp=127.0.0.1:7456;" if service == "mcp-server" else "8090/tcp=127.0.0.1:8090;",
                "key": KEY, "zap_key": ZAP_KEY, "enabled": "true", "mode": "api-key"}

    def run_preflight(self, expected, extra=()):
        self.state_file.write_text(json.dumps(self.state))
        before = self.snapshot()
        result = subprocess.run(["/bin/bash", str(self.script), *extra], env=self.environment,
                                capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 0 if expected == "READY" else 2, result.stdout + result.stderr)
        self.assertTrue(result.stdout.startswith(expected + ":"), result.stdout + result.stderr)
        self.assertEqual(result.stderr, "")
        self.assertNotIn(KEY, result.stdout)
        self.assertNotIn(ZAP_KEY, result.stdout)
        self.assertEqual(self.snapshot(), before)
        for args in self.calls():
            self.assertIn(args[0], ["context", "info", "compose", "ps", "inspect"])
            if args[0] == "compose": self.assertEqual(args, ["compose", "version"])
        if expected != "READY": self.assertFalse(self.doctor_args.exists())
        return result

    def snapshot(self):
        return {p.name: (hashlib.sha256(p.read_bytes()).hexdigest(), p.stat().st_mode & 0o777)
                for p in [self.env_file, self.directory / "docker-compose.yml"] if p.exists()}

    def calls(self):
        return [json.loads(line) for line in self.calls_file.read_text().splitlines()] if self.calls_file.exists() else []

    def test_ready_authenticates_without_scanner_tool_or_mutation(self):
        self.run_preflight("READY")
        self.assertEqual(self.doctor_args.read_text().splitlines(),
                         ["--env-file", str(self.env_file), "--skip-docker", "--skip-tool-call"])

    def test_docker_offline_stops_before_installation_or_http_checks(self):
        self.state["docker_off"] = True
        self.run_preflight("DOCKER_UNAVAILABLE")
        self.assertEqual([a[0] for a in self.calls()], ["context", "info"])

    def test_unresponsive_docker_is_bounded_and_never_recovers_automatically(self):
        import time
        self.state["docker_hung"] = True
        start = time.monotonic()
        self.run_preflight("DOCKER_UNAVAILABLE", ["--timeout-seconds", "1"])
        self.assertLess(time.monotonic() - start, 5)
        self.assertEqual([a[0] for a in self.calls()], ["context", "info"])

    def test_timeout_cleans_plugin_child_even_when_parent_exits_on_term(self):
        import signal
        import time
        def cleanup_fixture_child():
            pid_file = self.directory / 'orphan.pid'
            if pid_file.exists():
                try:
                    os.kill(int(pid_file.read_text()), signal.SIGKILL)
                except ProcessLookupError:
                    # An already-exited fixture child needs no further cleanup.
                    pass
        self.addCleanup(cleanup_fixture_child)
        self.state['docker_orphan'] = True
        self.run_preflight('DOCKER_UNAVAILABLE', ['--timeout-seconds', '1'])
        heartbeat = self.directory / 'heartbeat'
        before = heartbeat.read_text()
        time.sleep(0.2)
        self.assertEqual(heartbeat.read_text(), before)

    def test_user_starts_docker_then_same_readonly_check_recovers(self):
        self.state["docker_off"] = True
        self.run_preflight("DOCKER_UNAVAILABLE")
        self.state["docker_off"] = False
        self.run_preflight("READY")

    def test_remote_context_is_never_contacted_or_switched(self):
        self.state["endpoint"] = "ssh://fixture.example"
        self.run_preflight("REMOTE_DOCKER_UNSUPPORTED")
        self.assertEqual(len(self.calls()), 1)

    def test_remote_host_override_is_rejected_before_engine_contact(self):
        self.environment["DOCKER_HOST"] = "tcp://fixture.example:2376"
        self.run_preflight("REMOTE_DOCKER_UNSUPPORTED")
        self.assertEqual(len(self.calls()), 1)

    def test_compose_override_requires_explicit_review(self):
        self.environment["COMPOSE_FILE"] = "other.yml"
        self.run_preflight("ENVIRONMENT_CONFLICT")
        self.assertEqual(self.calls(), [])

    def test_missing_or_stopped_stack_never_recreates_containers(self):
        for state, code in [("missing", "SCANNER_MISSING"), ("stopped", "SCANNER_STOPPED"), ("unhealthy", "SCANNER_UNHEALTHY")]:
            with self.subTest(state=state):
                original = json.loads(json.dumps(self.state))
                if state == "missing": self.state["containers"] = []
                elif state == "stopped": self.state["containers"][0]["running"] = False
                else: self.state["containers"][0]["health"] = "unhealthy"
                self.run_preflight(code)
                self.state = original

    def test_stopped_engine_and_missing_engine_block_ready_mcp(self):
        self.state["containers"][1]["running"] = False
        self.run_preflight("SCANNER_STOPPED")
        self.state["containers"].pop()
        self.run_preflight("SCANNER_MISSING")

    def test_effective_key_mismatch_is_reported_without_secret_or_repair(self):
        self.state["containers"][0]["key"] = "different-private-fixture"
        result = self.run_preflight("CREDENTIAL_CONFLICT")
        self.assertNotIn("different-private-fixture", result.stdout)

    def test_inherited_key_mismatch_blocks_connection(self):
        self.environment["MCP_API_KEY"] = "old-client-key"
        self.run_preflight("CREDENTIAL_CONFLICT")

    def test_other_installation_duplicate_and_unknown_ownership_stop(self):
        self.state["containers"][0]["owner"] = "/another/installation"
        self.run_preflight("OWNERSHIP_CONFLICT")
        self.state["containers"][0]["owner"] = str(self.directory)
        other = self.container("c" * 64, "mcp-server")
        self.state["containers"].append(other)
        self.run_preflight("DUPLICATE_SCANNERS")

    def test_stopped_scanner_checks_all_member_ownership_before_resume_guidance(self):
        self.state["containers"][0]["running"] = False
        self.state["containers"][1]["owner"] = "/another/installation"
        self.run_preflight("OWNERSHIP_CONFLICT")

    def test_stopped_scanner_rejects_inherited_deployment_overrides(self):
        self.state["containers"][0]["running"] = False
        for key, value in [("COMPOSE_PROJECT_NAME", "other"), ("IMAGE_TAG", "v99.0.0"),
                           ("MCP_ZAP_BIND_ADDRESS", "0.0.0.0"), ("LOCAL_ZAP_WORKPLACE_FOLDER", "/other/workspace")]:
            with self.subTest(setting=key):
                self.environment[key] = value
                self.run_preflight("ENVIRONMENT_CONFLICT")
                self.environment.pop(key)

    def test_pending_file_auth_change_requires_review_before_scan(self):
        with self.env_file.open("a") as handle:
            handle.write("MCP_SECURITY_MODE=jwt\n")
        self.run_preflight("ENVIRONMENT_CONFLICT")

    def test_foreign_port_owner_is_not_stopped(self):
        foreign = self.container("c" * 64, "mcp-server")
        foreign["image"] = "unrelated/service:1"
        self.state["containers"] = [foreign]
        self.run_preflight("PORT_CONFLICT")

    def test_unsafe_binding_and_auth_mode_are_preserved(self):
        self.state["containers"][0]["binding"] = "7456/tcp=0.0.0.0:7456;"
        self.run_preflight("UNSAFE_BINDING")
        self.state["containers"][0]["binding"] = "7456/tcp=127.0.0.1:7456;"
        self.state["containers"][0]["mode"] = "jwt"
        self.run_preflight("AUTH_UNSUPPORTED")

    def test_stopped_scanner_with_pending_unsafe_binding_is_not_offered_resume(self):
        self.state['containers'][0]['running'] = False
        with self.env_file.open('a') as handle:
            handle.write('MCP_ZAP_BIND_ADDRESS=0.0.0.0\n')
        self.run_preflight('UNSAFE_BINDING')

    def test_custom_compose_layout_is_not_executed(self):
        self.state["containers"][0]["compose"] += ",override.yml"
        self.run_preflight("CONFIGURATION_INVALID")

    def test_disappearing_container_is_a_failed_check_not_a_retry(self):
        self.state["disappear"] = True
        self.run_preflight("CHECK_FAILED")
        self.assertEqual(sum(a[0] == "inspect" for a in self.calls()), 1)

    def test_credentials_are_not_generated_when_missing(self):
        self.env_file.unlink()
        self.run_preflight("CREDENTIALS_MISSING")
        self.assertFalse(self.env_file.exists())

    def test_shared_credentials_are_not_silently_chmodded(self):
        self.env_file.chmod(0o644)
        self.run_preflight("CONFIGURATION_INVALID")
        self.assertEqual(self.env_file.stat().st_mode & 0o777, 0o644)

    def test_failed_authentication_never_claims_native_client_readiness(self):
        self.environment["READINESS_DOCTOR_EXIT"] = "1"
        # The doctor ran, but no scanner tool or lifecycle action can run.
        self.state_file.write_text(json.dumps(self.state))
        result = subprocess.run(["/bin/bash", str(self.script)], env=self.environment,
                                capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 2)
        self.assertTrue(result.stdout.startswith("DOCTOR_FAILED:"))
        self.assertIn("--skip-tool-call", self.doctor_args.read_text())


if __name__ == "__main__":
    unittest.main()
