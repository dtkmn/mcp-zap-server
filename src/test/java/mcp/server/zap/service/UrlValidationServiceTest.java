package mcp.server.zap.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UrlValidationServiceTest {

    private UrlValidationService service;

    @BeforeEach
    void setup() {
        service = new UrlValidationService();
        // Set default configuration
        ReflectionTestUtils.setField(service, "allowLocalhost", false);
        ReflectionTestUtils.setField(service, "allowPrivateNetworks", false);
        ReflectionTestUtils.setField(service, "whitelist", List.of());
        ReflectionTestUtils.setField(service, "blacklist", 
            List.of("localhost", "127.0.0.1", "0.0.0.0"));
    }

    @Test
    void validatesValidPublicUrl() {
        assertDoesNotThrow(() -> service.validateUrl("https://example.com"));
        assertDoesNotThrow(() -> service.validateUrl("http://example.com/path"));
    }

    @Test
    void rejectsNullOrEmptyUrl() {
        assertThrows(IllegalArgumentException.class, () -> service.validateUrl(null));
        assertThrows(IllegalArgumentException.class, () -> service.validateUrl(""));
        assertThrows(IllegalArgumentException.class, () -> service.validateUrl("   "));
    }

    @Test
    void rejectsMalformedUrl() {
        assertThrows(IllegalArgumentException.class, () -> service.validateUrl("not-a-url"));
        assertThrows(IllegalArgumentException.class, () -> service.validateUrl("htp://bad-protocol.com"));
    }

    @Test
    void rejectsNonHttpProtocol() {
        assertThrows(IllegalArgumentException.class, () -> service.validateUrl("ftp://example.com"));
        assertThrows(IllegalArgumentException.class, () -> service.validateUrl("file:///etc/passwd"));
    }

    @Test
    void rejectsLocalhostByDefault() {
        assertThrows(IllegalArgumentException.class, () -> service.validateUrl("http://localhost"));
        assertThrows(IllegalArgumentException.class, () -> service.validateUrl("http://127.0.0.1"));
        assertThrows(IllegalArgumentException.class, () -> service.validateUrl("http://127.0.0.2"));
    }

    @Test
    void allowsLocalhostWhenEnabled() {
        ReflectionTestUtils.setField(service, "allowLocalhost", true);
        assertDoesNotThrow(() -> service.validateUrl("http://localhost"));
        assertDoesNotThrow(() -> service.validateUrl("http://127.0.0.1"));
    }

    @Test
    void rejectsPrivateNetworksByDefault() {
        assertThrows(IllegalArgumentException.class, () -> service.validateUrl("http://10.0.0.1"));
        assertThrows(IllegalArgumentException.class, () -> service.validateUrl("http://172.16.0.1"));
        assertThrows(IllegalArgumentException.class, () -> service.validateUrl("http://192.168.1.1"));
    }

    @Test
    void allowsPrivateNetworksWhenEnabled() {
        ReflectionTestUtils.setField(service, "allowPrivateNetworks", true);
        assertDoesNotThrow(() -> service.validateUrl("http://10.0.0.1"));
        assertDoesNotThrow(() -> service.validateUrl("http://192.168.1.1"));
    }

    @Test
    void whitelistTakesPrecedence() {
        ReflectionTestUtils.setField(service, "whitelist", List.of("example.com", "*.test.com"));
        
        // Whitelisted domains should pass
        assertDoesNotThrow(() -> service.validateUrl("http://example.com"));
        assertDoesNotThrow(() -> service.validateUrl("http://api.test.com"));
        
        // Non-whitelisted should fail
        assertThrows(IllegalArgumentException.class, () -> service.validateUrl("http://other.com"));
    }

    @Test
    void blacklistWorks() {
        ReflectionTestUtils.setField(service, "blacklist", List.of("blocked.com", "*.evil.com"));
        
        assertThrows(IllegalArgumentException.class, () -> service.validateUrl("http://blocked.com"));
        assertThrows(IllegalArgumentException.class, () -> service.validateUrl("http://api.evil.com"));
    }

    @Test
    void wildcardPatternsWork() {
        ReflectionTestUtils.setField(service, "whitelist", List.of("*.example.com"));
        
        assertDoesNotThrow(() -> service.validateUrl("http://api.example.com"));
        assertDoesNotThrow(() -> service.validateUrl("http://www.example.com"));
        assertThrows(IllegalArgumentException.class, () -> service.validateUrl("http://example.com"));
    }

    @Test
    void getConfigurationSummaryReturnsValidString() {
        String summary = service.getConfigurationSummary();
        assertNotNull(summary);
        assertTrue(summary.contains("allowLocalhost"));
        assertTrue(summary.contains("allowPrivateNetworks"));
    }
}
