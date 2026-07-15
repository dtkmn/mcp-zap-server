package mcp.server.zap.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

class DockerPublishWorkflowArchitectureTest {

    private static final Path CI_WORKFLOW = Path.of(".github/workflows/ci.yml");
    private static final Path RELEASE_WORKFLOW = Path.of(".github/workflows/release.yml");

    @Test
    void continuousImagePublicationIsLimitedToMainPushes() throws IOException {
        String workflow = Files.readString(CI_WORKFLOW);

        assertThat(workflow)
                .contains("name: Publish main images")
                .contains("if: github.event_name == 'push' && github.ref == 'refs/heads/main'")
                .contains("BRANCH_TAG: main")
                .contains("sha-${{ github.sha }}")
                .doesNotContain("github.ref == 'refs/heads/dev'")
                .doesNotContain("git rev-parse --short");
    }

    @Test
    void releaseWorkflowHasOneLeastPrivilegeOrderedPublisher() throws IOException {
        JsonNode workflow = releaseWorkflow();
        JsonNode releaseTrigger = workflow.path("on").path("release");
        JsonNode jobs = workflow.path("jobs");
        JsonNode job = jobs.path("docker-release");
        List<JsonNode> steps = steps(job);

        assertThat(workflow.path("on").propertyNames()).containsExactly("release");
        assertThat(releaseTrigger.path("types")).hasSize(1);
        assertThat(releaseTrigger.path("types").path(0).asString()).isEqualTo("published");
        assertThat(jobs.propertyNames()).containsExactly("docker-release");
        assertThat(workflow.path("permissions").propertyNames()).containsExactly("contents");
        assertThat(workflow.path("permissions").path("contents").asString()).isEqualTo("read");
        assertThat(job.has("permissions")).isFalse();
        assertThat(steps).allSatisfy(step -> assertThat(step.has("continue-on-error")).isFalse());

        JsonNode resolve = stepNamed(steps, "Resolve release metadata");
        JsonNode checkout = stepUsing(steps, "actions/checkout@v7");
        JsonNode verify = stepNamed(steps, "Verify release tag, commit, and project version");
        JsonNode archive = stepNamed(steps, "Archive SBOM workflow artifact");
        JsonNode ghcrLogin = stepNamed(steps, "Log in to GitHub Container Registry");
        JsonNode dockerHubLogin = stepNamed(steps, "Login to Docker Hub");
        List<JsonNode> publishers = steps.stream()
                .filter(step -> "docker/build-push-action@v7".equals(step.path("uses").asString()))
                .toList();
        assertThat(publishers).hasSize(1);
        JsonNode publish = publishers.getFirst();
        assertThat(publish.path("name").asString()).isEqualTo("Build and push release images");

        assertThat(resolve.path("env").path("RELEASE_VERSION").asString())
                .isEqualTo("${{ github.event.release.tag_name }}");
        assertThat(resolve.path("env").path("RELEASE_IS_PRERELEASE").asString())
                .isEqualTo("${{ github.event.release.prerelease }}");
        assertThat(resolve.path("env").path("RELEASE_IS_IMMUTABLE").asString())
                .isEqualTo("${{ github.event.release.immutable }}");
        assertThat(checkout.path("with").path("ref").asString()).isEqualTo("${{ github.sha }}");
        assertThat(checkout.path("with").path("fetch-depth").asInt()).isZero();
        assertThat(checkout.path("with").path("persist-credentials").asBoolean()).isFalse();

        int resolveIndex = steps.indexOf(resolve);
        int checkoutIndex = steps.indexOf(checkout);
        int verifyIndex = steps.indexOf(verify);
        assertThat(resolveIndex).isLessThan(checkoutIndex);
        assertThat(checkoutIndex).isLessThan(verifyIndex);
        assertThat(steps.subList(0, verifyIndex + 1)).containsExactly(resolve, checkout, verify);
        assertThat(verifyIndex).isLessThan(steps.indexOf(stepNamed(steps, "Validate Docker Compose manifests")));
        assertThat(verifyIndex).isLessThan(steps.indexOf(stepNamed(steps, "Build with Gradle and generate SBOM")));
        assertThat(archive.path("with").path("if-no-files-found").asString()).isEqualTo("error");
        assertThat(steps.indexOf(archive)).isLessThan(steps.indexOf(ghcrLogin));
        assertThat(steps.indexOf(ghcrLogin)).isLessThan(steps.indexOf(publish));
        assertThat(steps.indexOf(dockerHubLogin)).isLessThan(steps.indexOf(publish));
        assertThat(List.of(archive, ghcrLogin, dockerHubLogin, publish))
                .allSatisfy(step -> assertThat(step.has("if")).isFalse());
        assertThat(publish.path("with").path("push").asBoolean()).isTrue();
        assertThat(publish.path("with").path("tags").asString())
                .isEqualTo("${{ steps.image-tags.outputs.tags }}");
    }

    @Test
    void releaseMetadataAndImageTagsFailClosed(@TempDir Path tempDir) throws Exception {
        List<JsonNode> steps = releaseSteps();
        JsonNode resolve = stepNamed(steps, "Resolve release metadata");
        JsonNode imageTags = stepNamed(steps, "Compute release image tags");

        Map<String, String> stable = resolveRelease(tempDir, resolve, "stable", "v0.10.0", "false", "true");
        assertThat(stable).containsEntry("version", "v0.10.0").containsEntry("publish_latest", "true");
        String stableTags = computeImageTags(tempDir, imageTags, "stable", stable);
        assertThat(stableTags)
                .contains("ghcr.io/dtkmn/mcp-zap-server:v0.10.0")
                .contains("dtkmn/mcp-zap-server:v0.10.0")
                .contains("ghcr.io/dtkmn/mcp-zap-server:latest")
                .contains("dtkmn/mcp-zap-server:latest");

        Map<String, String> suffixPrerelease =
                resolveRelease(tempDir, resolve, "suffix-prerelease", "v0.10.0-rc.1", "false", "true");
        assertThat(suffixPrerelease).containsEntry("publish_latest", "false");
        assertThat(computeImageTags(tempDir, imageTags, "suffix-prerelease", suffixPrerelease))
                .doesNotContain(":latest");

        Map<String, String> eventPrerelease =
                resolveRelease(tempDir, resolve, "event-prerelease", "v0.10.0", "true", "true");
        assertThat(eventPrerelease).containsEntry("publish_latest", "false");
        assertThat(computeImageTags(tempDir, imageTags, "event-prerelease", eventPrerelease))
                .doesNotContain(":latest");

        Path injectionMarker = tempDir.resolve("injected");
        ShellResult malicious = runShell(
                resolve,
                Path.of("."),
                Map.of(
                        "RELEASE_VERSION", "v0.10.0$(touch " + injectionMarker + ")",
                        "RELEASE_IS_PRERELEASE", "false",
                        "RELEASE_IS_IMMUTABLE", "true",
                        "GITHUB_OUTPUT", tempDir.resolve("malicious-output").toString()));
        assertThat(malicious.exitCode()).isNotZero();
        assertThat(injectionMarker).doesNotExist();

        int invalidIndex = 0;
        for (String invalidVersion : List.of("v01.0.0", "v0.10", "v0.10.0+build", "v0.10.0-rc..1")) {
            ShellResult invalid = runShell(
                    resolve,
                    Path.of("."),
                    Map.of(
                            "RELEASE_VERSION", invalidVersion,
                            "RELEASE_IS_PRERELEASE", "false",
                            "RELEASE_IS_IMMUTABLE", "true",
                            "GITHUB_OUTPUT",
                                    tempDir.resolve("invalid-output-" + invalidIndex++).toString()));
            assertThat(invalid.exitCode()).as(invalidVersion).isNotZero();
        }

        ShellResult mutable = runShell(
                resolve,
                Path.of("."),
                Map.of(
                        "RELEASE_VERSION", "v0.10.0",
                        "RELEASE_IS_PRERELEASE", "false",
                        "RELEASE_IS_IMMUTABLE", "false",
                        "GITHUB_OUTPUT", tempDir.resolve("mutable-output").toString()));
        assertThat(mutable.exitCode()).isNotZero();
        assertThat(mutable.output()).contains("immutable releases");
    }

    @Test
    void releaseCommitMustBeOnMainAndTagMustRemainFixed(@TempDir Path tempDir) throws Exception {
        JsonNode verify = stepNamed(releaseSteps(), "Verify release tag, commit, and project version");
        Path repository = tempDir.resolve("repository");
        Files.createDirectories(repository);
        git(repository, "init", "--initial-branch=main");
        git(repository, "config", "user.name", "Release Policy Test");
        git(repository, "config", "user.email", "release-policy@example.invalid");
        git(repository, "config", "commit.gpgsign", "false");
        git(repository, "config", "tag.gpgsign", "false");
        Files.writeString(repository.resolve("build.gradle"), "version = '0.10.0'\n");
        git(repository, "add", "build.gradle");
        git(repository, "commit", "-m", "main release commit");
        String mainCommit = git(repository, "rev-parse", "HEAD");
        git(repository, "update-ref", "refs/remotes/origin/main", mainCommit);
        git(repository, "tag", "v0.10.0", mainCommit);

        ShellResult valid = runVerification(verify, repository, mainCommit);
        assertThat(valid.exitCode()).as(valid.output()).isZero();

        git(repository, "checkout", "-b", "off-main");
        Files.writeString(repository.resolve("off-main.txt"), "unreviewed\n");
        git(repository, "add", "off-main.txt");
        git(repository, "commit", "-m", "off-main release commit");
        String offMainCommit = git(repository, "rev-parse", "HEAD");
        git(repository, "tag", "-f", "v0.10.0", offMainCommit);

        ShellResult offMain = runVerification(verify, repository, offMainCommit);
        assertThat(offMain.exitCode()).isNotZero();
        assertThat(offMain.output()).contains("reachable from origin/main");

        git(repository, "checkout", "main");
        ShellResult movedTag = runVerification(verify, repository, mainCommit);
        assertThat(movedTag.exitCode()).isNotZero();
        assertThat(movedTag.output()).contains("must still reference the release event commit");
    }

    private static JsonNode releaseWorkflow() throws IOException {
        return YAMLMapper.shared().readTree(Files.readString(RELEASE_WORKFLOW));
    }

    private static List<JsonNode> releaseSteps() throws IOException {
        return steps(releaseWorkflow().path("jobs").path("docker-release"));
    }

    private static List<JsonNode> steps(JsonNode job) {
        List<JsonNode> steps = new ArrayList<>();
        job.path("steps").forEach(steps::add);
        return steps;
    }

    private static JsonNode stepNamed(List<JsonNode> steps, String name) {
        return steps.stream()
                .filter(step -> name.equals(step.path("name").asString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing workflow step: " + name));
    }

    private static JsonNode stepUsing(List<JsonNode> steps, String action) {
        return steps.stream()
                .filter(step -> action.equals(step.path("uses").asString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing workflow action: " + action));
    }

    private static Map<String, String> resolveRelease(
            Path tempDir, JsonNode resolve, String scenario, String version, String prerelease, String immutable)
            throws Exception {
        Path output = tempDir.resolve(scenario + "-resolve-output");
        ShellResult result = runShell(
                resolve,
                Path.of("."),
                Map.of(
                        "RELEASE_VERSION", version,
                        "RELEASE_IS_PRERELEASE", prerelease,
                        "RELEASE_IS_IMMUTABLE", immutable,
                        "GITHUB_OUTPUT", output.toString()));
        assertThat(result.exitCode()).as(result.output()).isZero();
        return readKeyValueOutput(output);
    }

    private static String computeImageTags(
            Path tempDir, JsonNode imageTags, String scenario, Map<String, String> release) throws Exception {
        Path output = tempDir.resolve(scenario + "-image-output");
        ShellResult result = runShell(
                imageTags,
                Path.of("."),
                Map.of(
                        "RELEASE_VERSION", release.get("version"),
                        "PUBLISH_LATEST", release.get("publish_latest"),
                        "GHCR_IMAGE", "ghcr.io/dtkmn/mcp-zap-server",
                        "DOCKER_HUB_IMAGE", "dtkmn/mcp-zap-server",
                        "GITHUB_OUTPUT", output.toString()));
        assertThat(result.exitCode()).as(result.output()).isZero();
        return Files.readString(output);
    }

    private static Map<String, String> readKeyValueOutput(Path output) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : Files.readAllLines(output)) {
            int separator = line.indexOf('=');
            if (separator > 0) {
                values.put(line.substring(0, separator), line.substring(separator + 1));
            }
        }
        return values;
    }

    private static ShellResult runVerification(JsonNode verify, Path repository, String expectedCommit)
            throws Exception {
        return runShell(
                verify,
                repository,
                Map.of("RELEASE_VERSION", "v0.10.0", "EXPECTED_COMMIT", expectedCommit));
    }

    private static ShellResult runShell(JsonNode step, Path directory, Map<String, String> environment)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder("/bin/bash", "-c", step.path("run").asString());
        builder.directory(directory.toFile());
        builder.redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ShellResult(process.waitFor(), output);
    }

    private static String git(Path repository, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("/usr/bin/git");
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(repository.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new AssertionError("Git command failed: " + String.join(" ", command) + "\n" + output);
        }
        return output.strip();
    }

    private record ShellResult(int exitCode, String output) {}
}
