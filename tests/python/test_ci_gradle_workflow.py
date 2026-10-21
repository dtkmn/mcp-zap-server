"""Exercise CI task selection offline without running Gradle, Docker, or Sonar."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = yaml.load((ROOT / ".github/workflows/ci.yml").read_text(), Loader=yaml.BaseLoader)
BUILD_JOB = WORKFLOW["jobs"]["build"]
GRADLE_STEPS = [step for step in BUILD_JOB["steps"] if "./gradlew" in step.get("run", "")]


def normalized_expression(value):
    return " ".join(value.split())


class CiGradleWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.calls_path = self.directory / "gradle-calls.jsonl"
        wrapper = self.directory / "gradlew"
        wrapper.write_text(f"""#!{sys.executable}
import json, os, pathlib, sys
with pathlib.Path(os.environ['FIXTURE_GRADLE_CALLS']).open('a') as calls:
    calls.write(json.dumps(sys.argv[1:]) + '\\n')
sys.exit(int(os.environ.get('FIXTURE_GRADLE_EXIT', '0')))
""")
        wrapper.chmod(0o700)

    def run_gradle_step(self, *, docker=False, sonar_eligible=False, token="", exit_code=0):
        self.assertEqual(len(GRADLE_STEPS), 1, "CI must use one Gradle invocation")
        self.calls_path.unlink(missing_ok=True)
        environment = os.environ | {
            "FIXTURE_GRADLE_CALLS": str(self.calls_path),
            "FIXTURE_GRADLE_EXIT": str(exit_code),
            "RUN_DOCKER_TESTS": str(docker).lower(),
            "SONAR_ELIGIBLE": str(sonar_eligible).lower(),
            "SONAR_TOKEN": token,
        }
        result = subprocess.run(
            ["bash", "-e", "-o", "pipefail", "-c", GRADLE_STEPS[0]["run"]],
            cwd=self.directory, env=environment, text=True, capture_output=True, timeout=10,
        )
        calls = [json.loads(line) for line in self.calls_path.read_text().splitlines()]
        self.assertEqual(len(calls), 1, "Tests, coverage, and analysis must share one task graph")
        return result, calls[0]

    def test_preserves_docker_and_trusted_sonar_event_guards(self):
        self.assertEqual(len(GRADLE_STEPS), 1)
        environment = GRADLE_STEPS[0]["env"]
        docker_condition = """github.event_name == 'workflow_dispatch' ||
            (github.event_name == 'push' && github.ref == 'refs/heads/main') ||
            (github.event_name == 'pull_request' && github.base_ref == 'main')"""
        sonar_condition = """github.actor != 'dependabot[bot]' &&
            (github.event_name != 'pull_request' ||
            github.event.pull_request.head.repo.full_name == github.repository)"""
        self.assertEqual(normalized_expression(environment["RUN_DOCKER_TESTS"]),
                         normalized_expression("${{ " + docker_condition + " }}"))
        self.assertEqual(normalized_expression(environment["SONAR_ELIGIBLE"]),
                         normalized_expression("${{ " + sonar_condition + " }}"))
        self.assertEqual(normalized_expression(environment["SONAR_TOKEN"]),
                         normalized_expression("${{ " + sonar_condition + " && secrets.SONAR_TOKEN || '' }}"))

    def test_optional_suites_and_analysis_use_one_fresh_ordered_graph(self):
        for docker in (False, True):
            for sonar_eligible in (False, True):
                for token in ("", "fixture-secret"):
                    with self.subTest(docker=docker, sonar_eligible=sonar_eligible, token_present=bool(token)):
                        result, arguments = self.run_gradle_step(
                            docker=docker, sonar_eligible=sonar_eligible, token=token,
                        )
                        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                        expected_tasks = ["clean", "build"]
                        if docker:
                            expected_tasks.append("dockerTest")
                        expected_tasks.append("jacocoTestReport")
                        if sonar_eligible and token:
                            expected_tasks.append("sonar")
                        self.assertEqual(arguments, expected_tasks + [
                            "--no-scan", "--no-daemon", "--stacktrace", "--info",
                        ])
                        self.assertEqual("::notice::Sonar analysis skipped:" in result.stdout,
                                         sonar_eligible and not token)
                        self.assertNotIn("fixture-secret", result.stdout + result.stderr + json.dumps(arguments))

    def test_gradle_failure_fails_the_step(self):
        result, _ = self.run_gradle_step(docker=True, sonar_eligible=True,
                                         token="fixture-secret", exit_code=37)
        self.assertEqual(result.returncode, 37)

    def test_required_check_and_always_uploaded_reports_are_preserved(self):
        self.assertEqual(BUILD_JOB["name"], "Build and test")
        upload = next(step for step in BUILD_JOB["steps"] if step.get("name") == "Upload test reports")
        self.assertEqual(upload["if"], "always()")
        self.assertEqual(upload["with"]["name"], "test-reports")
        self.assertEqual(upload["with"]["if-no-files-found"], "ignore")
        self.assertEqual(upload["with"]["path"].splitlines(), [
            "build/test-results/test/**",
            "build/test-results/dockerTest/**",
            "build/reports/tests/test/**",
            "build/reports/tests/dockerTest/**",
            "build/reports/jacoco/test/**",
            "build/reports/problems/problems-report.html",
        ])


if __name__ == "__main__":
    unittest.main()
