package mcp.server.zap.core.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlScopeTest {

    @Test
    void containsSameOriginAndPathBoundaryOnly() {
        UrlScope scope = UrlScope.parse("https://target/app");

        assertThat(scope.contains("https://target/app")).isTrue();
        assertThat(scope.contains("https://target/app/page")).isTrue();
        assertThat(scope.contains("https://target/app2")).isFalse();
        assertThat(scope.contains("https://target.evil/app")).isFalse();
        assertThat(scope.contains("https://target/app/%2e%2e/secret")).isFalse();
    }

    @Test
    void normalizesDefaultPortsAndTrailingSlash() {
        UrlScope scope = UrlScope.parse("https://target:443/app/");

        assertThat(scope.contains("https://target/app")).isTrue();
        assertThat(scope.contains("https://target/app/deeper")).isTrue();
        assertThat(scope.contains("http://target:80/app")).isFalse();
    }

    @Test
    void rejectsMalformedScopeAndCandidateUrls() {
        assertThatThrownBy(() -> UrlScope.parse("not-a-url"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute URL");

        assertThat(UrlScope.parse("https://target").contains("not-a-url")).isFalse();
    }
}
