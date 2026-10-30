package mcp.server.zap.core.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import mcp.server.zap.core.service.jobstore.InMemoryScanJobStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import tools.jackson.databind.ObjectMapper;

class ScanJobStoreConfigurationTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void explicitPostgresCannotLoseLaunchAuthorityThroughBlankUrlFallback(boolean failFast) {
        ScanJobStoreProperties properties = new ScanJobStoreProperties();
        properties.setBackend("postgres");
        properties.getPostgres().setFailFast(failFast);
        assertThatThrownBy(() -> create(properties)).hasMessageContaining("requires its URL");
    }

    @Test
    void failedPostgresConstructionCannotFallBackToMemory() {
        ScanJobStoreProperties properties = new ScanJobStoreProperties();
        properties.setBackend("postgres");
        properties.getPostgres().setUrl("jdbc:postgresql://unused.invalid/fixture");
        properties.getPostgres().setTableName("invalid;table");
        assertThatThrownBy(() -> create(properties)).hasMessageContaining("must not fall back to memory");
    }

    @Test
    void memoryRemainsSupportedAndUnknownBackendDoesNotGuessAuthority() {
        ScanJobStoreProperties properties = new ScanJobStoreProperties();
        assertThat(create(properties)).isInstanceOf(InMemoryScanJobStore.class);
        properties.setBackend("postgrez");
        assertThatThrownBy(() -> create(properties)).hasMessageContaining("Unknown scan job store backend");
    }

    private Object create(ScanJobStoreProperties properties) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("mapper", new ObjectMapper());
        return new ScanJobStoreConfiguration().scanJobStore(properties, beans.getBeanProvider(ObjectMapper.class));
    }
}
