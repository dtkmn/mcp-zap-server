package mcp.server.zap.core.service.auth.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import mcp.server.zap.core.configuration.AuthBootstrapProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class AuthProfileResolverTest {

    @Test
    void resolvesOperatorFieldsAndCallerTargetAsOneRequest() {
        AuthProfileResolver resolver = new AuthProfileResolver(propertiesWith(formProfile("shop-staging")));

        AuthBootstrapRequest request = resolver.resolve(
                "shop-staging",
                "https://shop.example.com/catalog"
        );

        assertThat(request.profileId()).isEqualTo("shop-staging");
        assertThat(request.authKind()).isEqualTo(AuthBootstrapKind.FORM);
        assertThat(request.allowedOrigin()).isEqualTo(HttpOrigin.fromConfiguredOrigin("https://shop.example.com"));
        assertThat(request.targetUrl()).isEqualTo("https://shop.example.com/catalog");
        assertThat(request.loginUrl()).isEqualTo("https://shop.example.com/login");
        assertThat(request.credentialReference()).isEqualTo("env:SHOP_PASSWORD");
    }

    @Test
    void rejectsUnknownAndDuplicateProfiles() {
        AuthProfileResolver resolver = new AuthProfileResolver(propertiesWith(formProfile("shop-staging")));

        assertThatThrownBy(() -> resolver.resolve("missing", "https://shop.example.com"))
                .hasMessage("Unknown auth profile ID: missing");

        AuthBootstrapProperties duplicateProperties = propertiesWith(
                formProfile("shop-staging"),
                formProfile("shop-staging")
        );
        assertThatThrownBy(() -> new AuthProfileResolver(duplicateProperties))
                .hasMessage("Duplicate auth profile ID: shop-staging");
    }

    @Test
    void rejectsProfileWhoseLoginIsOutsideItsAllowedOrigin() {
        AuthBootstrapProperties.Profile profile = formProfile("shop-staging");
        profile.setLoginUrl("https://attacker.example/login");

        assertThatThrownBy(() -> new AuthProfileResolver(propertiesWith(profile)))
                .hasMessageContaining("Auth profile 'shop-staging' is invalid")
                .hasMessageContaining("loginUrl origin is not authorized for auth profile");
    }

    @Test
    void rejectsInvalidCredentialReferenceBeforeProfileCanBeSelected() {
        AuthBootstrapProperties.Profile profile = formProfile("shop-staging");
        profile.setCredentialReference("file:relative/secret");

        assertThatThrownBy(() -> new AuthProfileResolver(propertiesWith(profile)))
                .hasMessageContaining("Auth profile 'shop-staging' is invalid")
                .hasMessageContaining("credentialReference file path must be absolute");
    }

    @Test
    void rejectsMalformedIndicatorRegexesWhenProfilesAreCompiled() {
        AuthBootstrapProperties.Profile loggedIn = formProfile("bad-logged-in");
        loggedIn.setLoggedInIndicatorRegex("[");
        assertThatThrownBy(() -> new AuthProfileResolver(propertiesWith(loggedIn)))
                .hasMessageContaining("Auth profile 'bad-logged-in' is invalid")
                .hasMessageContaining("loggedInIndicatorRegex must be a valid regular expression");

        AuthBootstrapProperties.Profile loggedOut = formProfile("bad-logged-out");
        loggedOut.setLoggedOutIndicatorRegex("[");
        assertThatThrownBy(() -> new AuthProfileResolver(propertiesWith(loggedOut)))
                .hasMessageContaining("Auth profile 'bad-logged-out' is invalid")
                .hasMessageContaining("loggedOutIndicatorRegex must be a valid regular expression");
    }

    @Test
    void rejectsIndicatorRegexesLongerThanTheRuntimeLimit() {
        AuthBootstrapProperties.Profile loggedIn = formProfile("long-logged-in");
        loggedIn.setLoggedInIndicatorRegex("a".repeat(513));
        assertThatThrownBy(() -> new AuthProfileResolver(propertiesWith(loggedIn)))
                .hasMessageContaining("loggedInIndicatorRegex cannot exceed 512 characters");

        AuthBootstrapProperties.Profile loggedOut = formProfile("long-logged-out");
        loggedOut.setLoggedOutIndicatorRegex("a".repeat(513));
        assertThatThrownBy(() -> new AuthProfileResolver(propertiesWith(loggedOut)))
                .hasMessageContaining("loggedOutIndicatorRegex cannot exceed 512 characters");
    }

    @Test
    void acceptsIndicatorRegexesAtTheRuntimeLengthLimit() {
        AuthBootstrapProperties.Profile profile = formProfile("limit");
        profile.setLoggedInIndicatorRegex("a".repeat(512));
        profile.setLoggedOutIndicatorRegex("b".repeat(512));

        AuthBootstrapRequest request = new AuthProfileResolver(propertiesWith(profile))
                .resolve("limit", "https://shop.example.com");

        assertThat(request.loggedInIndicatorRegex()).hasSize(512);
        assertThat(request.loggedOutIndicatorRegex()).hasSize(512);
    }

    @Test
    void springConfigurationBindingBuildsIndexedProfile() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "mcp.server.auth.bootstrap.profiles[0].id", "shop-staging",
                "mcp.server.auth.bootstrap.profiles[0].kind", "form",
                "mcp.server.auth.bootstrap.profiles[0].allowed-origin", "https://shop.example.com",
                "mcp.server.auth.bootstrap.profiles[0].credential-reference", "env:SHOP_PASSWORD",
                "mcp.server.auth.bootstrap.profiles[0].login-url", "https://shop.example.com/login",
                "mcp.server.auth.bootstrap.profiles[0].username", "zap-scan-user",
                "mcp.server.auth.bootstrap.profiles[0].zap-user-name", "shop-zap-user",
                "mcp.server.auth.bootstrap.profiles[0].logged-in-indicator-regex", ".*Logout.*"
        ));
        AuthBootstrapProperties properties = new Binder(source)
                .bind("mcp.server.auth.bootstrap", Bindable.of(AuthBootstrapProperties.class))
                .orElseThrow(() -> new AssertionError("Expected auth bootstrap properties to bind"));

        AuthBootstrapRequest request = new AuthProfileResolver(properties)
                .resolve("shop-staging", "https://shop.example.com/account");

        assertThat(request.profileId()).isEqualTo("shop-staging");
        assertThat(request.allowedOrigin().toString()).isEqualTo("https://shop.example.com");
        assertThat(request.loginUrl()).isEqualTo("https://shop.example.com/login");
        assertThat(request.zapUserName()).isEqualTo("shop-zap-user");
    }

    private AuthBootstrapProperties propertiesWith(AuthBootstrapProperties.Profile... profiles) {
        AuthBootstrapProperties properties = new AuthBootstrapProperties();
        properties.setProfiles(List.of(profiles));
        return properties;
    }

    private AuthBootstrapProperties.Profile formProfile(String id) {
        AuthBootstrapProperties.Profile profile = new AuthBootstrapProperties.Profile();
        profile.setId(id);
        profile.setKind("form");
        profile.setAllowedOrigin("https://shop.example.com");
        profile.setCredentialReference("env:SHOP_PASSWORD");
        profile.setLoginUrl("https://shop.example.com/login");
        profile.setUsername("zap-scan-user");
        profile.setLoggedInIndicatorRegex(".*Logout.*");
        return profile;
    }
}
