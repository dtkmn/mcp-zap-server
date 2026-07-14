package mcp.server.zap.core.service.auth.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class HttpOriginTest {

    @Test
    void canonicalizesCaseAndDefaultPort() {
        HttpOrigin origin = HttpOrigin.fromConfiguredOrigin("https://SHOP.Example.com:443");

        assertThat(origin).isEqualTo(HttpOrigin.fromUrl("https://shop.example.com/account"));
        assertThat(origin.toString()).isEqualTo("https://shop.example.com");
    }

    @Test
    void rejectsTerminalDotInsteadOfAuthorizingADifferentHostname() {
        assertThatThrownBy(() -> HttpOrigin.fromConfiguredOrigin("https://shop.example.com."))
                .hasMessage("allowedOrigin host must not end with a dot");
        assertThatThrownBy(() -> HttpOrigin.fromUrl("https://shop.example.com./account"))
                .hasMessage("URL host must not end with a dot");
    }

    @Test
    void distinguishesSchemePortAndSiblingHost() {
        HttpOrigin origin = HttpOrigin.fromConfiguredOrigin("https://shop.example.com");

        assertThat(origin.matches("http://shop.example.com")).isFalse();
        assertThat(origin.matches("https://shop.example.com:8443")).isFalse();
        assertThat(origin.matches("https://shop.example.com.attacker.test")).isFalse();
    }

    @Test
    void configuredOriginRejectsPathQueryFragmentAndUserInfo() {
        assertThatThrownBy(() -> HttpOrigin.fromConfiguredOrigin("https://shop.example.com/login"))
                .hasMessage("allowedOrigin must contain only scheme, host, and optional port");
        assertThatThrownBy(() -> HttpOrigin.fromConfiguredOrigin("https://shop.example.com?x=1"))
                .hasMessage("allowedOrigin must contain only scheme, host, and optional port");
        assertThatThrownBy(() -> HttpOrigin.fromConfiguredOrigin("https://shop.example.com#fragment"))
                .hasMessage("allowedOrigin must contain only scheme, host, and optional port");
        assertThatThrownBy(() -> HttpOrigin.fromConfiguredOrigin("https://user@shop.example.com"))
                .hasMessage("allowedOrigin must not contain user information");
    }

    @Test
    void rejectsExplicitlyEmptyPort() {
        assertThatThrownBy(() -> HttpOrigin.fromUrl("https://shop.example.com:/account"))
                .hasMessage("URL port cannot be empty");
    }
}
