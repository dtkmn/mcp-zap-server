package mcp.server.zap.helm;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HelmMigrationBundleTest {

    private static final Path REPO_ROOT = Path.of("").toAbsolutePath();
    private static final Path CLASSPATH_MIGRATIONS = REPO_ROOT.resolve("src/main/resources/db/migration");
    private static final Path HELM_MIGRATIONS = REPO_ROOT.resolve("helm/mcp-zap-server/files/db/migration");
    private static final Path MIGRATION_CONFIGMAP_TEMPLATE =
            REPO_ROOT.resolve("helm/mcp-zap-server/templates/db-migration-configmap.yaml");

    @Test
    void helmMigrationBundleContainsEveryClasspathMigration() throws IOException {
        assertEquals(
                migrationNames(CLASSPATH_MIGRATIONS),
                migrationNames(HELM_MIGRATIONS),
                "Helm Flyway bundle must mirror every classpath migration"
        );
    }

    @Test
    void migrationConfigMapRendersBundledMigrations() throws IOException {
        String template = Files.readString(MIGRATION_CONFIGMAP_TEMPLATE);

        assertTrue(
                template.contains("files/db/migration/*.sql"),
                "db migration ConfigMap must render the bundled Flyway migration files"
        );
    }

    private Set<String> migrationNames(Path directory) throws IOException {
        try (Stream<Path> migrations = Files.list(directory)) {
            return migrations
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .map(path -> path.getFileName().toString())
                    .collect(TreeSet::new, TreeSet::add, TreeSet::addAll);
        }
    }
}
