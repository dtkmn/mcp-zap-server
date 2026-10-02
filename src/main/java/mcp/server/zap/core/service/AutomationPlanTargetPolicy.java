package mcp.server.zap.core.service;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Checks caller-supplied destinations before a plan is materialized for ZAP. */
final class AutomationPlanTargetPolicy {
    private static final Set<String> SUPPORTED_JOBS = Set.of(
            "requestor", "spider", "activeScan", "passiveScan-config", "passiveScan-wait",
            "activeScan-config", "activeScan-policy", "report", "exitStatus", "delay");
    private static final Set<String> CONTEXT_FIELDS = Set.of(
            "name", "urls", "includePaths", "excludePaths", "authentication", "sessionManagement",
            "technology", "structure", "users");
    private final UrlValidationService urlValidationService;

    AutomationPlanTargetPolicy(UrlValidationService urlValidationService) {
        this.urlValidationService = Objects.requireNonNull(urlValidationService);
    }

    void validate(Map<String, Object> plan) {
        Map<?, ?> env = mapping(plan.get("env"), "env");
        allowedKeys(env, Set.of("contexts", "vars", "parameters"), "env");
        List<?> contexts = list(env.get("contexts"), "env.contexts");
        if (contexts.isEmpty()) {
            throw invalid("env.contexts", "must contain at least one context");
        }
        Set<String> contextNames = new HashSet<>();
        for (int i = 0; i < contexts.size(); i++) {
            String field = "env.contexts[" + i + "]";
            Map<?, ?> context = mapping(contexts.get(i), field);
            allowedKeys(context, CONTEXT_FIELDS, field);
            String name = literalText(context.get("name"), field + ".name");
            if (!contextNames.add(name)) {
                throw invalid(field + ".name", "must be unique within the plan");
            }
            List<?> urls = list(context.get("urls"), field + ".urls");
            if (urls.isEmpty()) {
                throw invalid(field + ".urls", "must contain at least one URL");
            }
            for (int j = 0; j < urls.size(); j++) {
                destination(urls.get(j), field + ".urls[" + j + "]");
            }
            if (context.get("includePaths") != null && !list(context.get("includePaths"), field + ".includePaths").isEmpty()) {
                throw invalid(field + ".includePaths", "is unsupported; declare literal URLs in urls instead");
            }
            validateAuthentication(context.get("authentication"), field + ".authentication");
            validateSessionManagement(context.get("sessionManagement"), field + ".sessionManagement");
        }
        if (env.get("parameters") != null) {
            allowedKeys(mapping(env.get("parameters"), "env.parameters"), Set.of(
                    "failOnError", "failOnWarning", "continueOnFailure", "progressToStdout", "maxDuration"), "env.parameters");
        }
        if (env.get("vars") != null) {
            mapping(env.get("vars"), "env.vars");
        }
        if (plan.get("jobs") == null) {
            return;
        }
        List<?> jobs = list(plan.get("jobs"), "jobs");
        for (int i = 0; i < jobs.size(); i++) {
            String field = "jobs[" + i + "]";
            Map<?, ?> job = mapping(jobs.get(i), field);
            String type = literalText(job.get("type"), field + ".type");
            if (!SUPPORTED_JOBS.contains(type)) {
                throw invalid(field + ".type", "is unsupported by the MCP destination policy: " + type);
            }
            Map<?, ?> parameters = optionalMapping(job.get("parameters"), field + ".parameters");
            rejectSetterAliases(parameters, field + ".parameters");
            if (parameters.get("context") != null) {
                String context = literalText(parameters.get("context"), field + ".parameters.context");
                if (!contextNames.contains(context)) {
                    throw invalid(field + ".parameters.context", "must name a context declared in this plan");
                }
            }
            if ("spider".equals(type) || "activeScan".equals(type)) {
                optionalDestination(parameters.get("url"), field + ".parameters.url");
            }
            if ("requestor".equals(type)) {
                List<?> requests = list(job.get("requests"), field + ".requests");
                for (int j = 0; j < requests.size(); j++) {
                    String requestField = field + ".requests[" + j + "]";
                    Map<?, ?> request = mapping(requests.get(j), requestField);
                    allowedKeys(request, Set.of("url", "name", "method", "httpVersion", "headers", "data", "responseCode"), requestField);
                    destination(request.get("url"), requestField + ".url");
                }
            }
        }
    }

    private void validateAuthentication(Object value, String field) {
        if (value == null) {
            return;
        }
        Map<?, ?> authentication = mapping(value, field);
        allowedKeys(authentication, Set.of("method", "parameters", "verification"), field);
        String method = literalText(authentication.get("method"), field + ".method");
        Set<String> parameterFields = switch (method) {
            case "manual" -> Set.of();
            case "http" -> Set.of("hostname", "port", "realm");
            case "form", "json" -> Set.of("loginPageUrl", "loginRequestUrl", "loginRequestBody");
            default -> throw invalid(field + ".method", "supports only manual, http, form, or json authentication");
        };
        Map<?, ?> parameters = optionalMapping(authentication.get("parameters"), field + ".parameters");
        allowedKeys(parameters, parameterFields, field + ".parameters");
        if ("form".equals(method) || "json".equals(method)) {
            destination(parameters.get("loginRequestUrl"), field + ".parameters.loginRequestUrl");
            optionalDestination(parameters.get("loginPageUrl"), field + ".parameters.loginPageUrl");
        }
        Map<?, ?> verification = optionalMapping(authentication.get("verification"), field + ".verification");
        allowedKeys(verification, Set.of("method", "loggedInRegex", "loggedOutRegex", "pollFrequency", "pollUnits",
                "pollUrl", "pollPostData", "pollAdditionalHeaders"), field + ".verification");
        boolean polling = false;
        if (verification.get("method") != null) {
            String verificationMethod = literalText(verification.get("method"), field + ".verification.method");
            if (!Set.of("response", "request", "both", "poll").contains(verificationMethod)) {
                throw invalid(field + ".verification.method", "supports only response, request, both, or poll verification");
            }
            polling = "poll".equals(verificationMethod);
        }
        if (polling) {
            destination(verification.get("pollUrl"), field + ".verification.pollUrl");
        } else {
            optionalDestination(verification.get("pollUrl"), field + ".verification.pollUrl");
        }
    }

    private void validateSessionManagement(Object value, String field) {
        if (value == null) {
            return;
        }
        Map<?, ?> session = mapping(value, field);
        allowedKeys(session, Set.of("method", "parameters"), field);
        String method = literalText(session.get("method"), field + ".method");
        if (!Set.of("cookie", "http").contains(method)) {
            throw invalid(field + ".method", "supports only cookie or http session management");
        }
        allowedKeys(optionalMapping(session.get("parameters"), field + ".parameters"), Set.of(), field + ".parameters");
    }

    private void optionalDestination(Object value, String field) {
        if (value == null || value instanceof String text && text.isBlank()) {
            return;
        }
        destination(value, field);
    }

    private void destination(Object value, String field) {
        String url = literalText(value, field);
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw invalid(field, "must be a literal HTTP(S) URL");
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || uri.getPort() < -1 || uri.getPort() > 65535) {
            throw invalid(field, "must be a full HTTP(S) URL without user info or a fragment");
        }
        urlValidationService.validateUrl(url);
    }

    private String literalText(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank() || !text.equals(text.trim()) || text.contains("${")) {
            throw invalid(field, "must be nonblank literal text without variable substitutions or surrounding whitespace");
        }
        return text;
    }

    private Map<?, ?> optionalMapping(Object value, String field) {
        return value == null ? Map.of() : mapping(value, field);
    }

    private Map<?, ?> mapping(Object value, String field) {
        if (!(value instanceof Map<?, ?> map)) {
            throw invalid(field, "must be a mapping");
        }
        return map;
    }

    private List<?> list(Object value, String field) {
        if (!(value instanceof List<?> list)) {
            throw invalid(field, "must be a list");
        }
        return list;
    }

    private void allowedKeys(Map<?, ?> map, Set<String> allowed, String field) {
        for (Object key : map.keySet()) {
            if (!allowed.contains(key)) {
                throw invalid(field + "." + key, "is unsupported by the MCP destination policy");
            }
        }
    }

    private void rejectSetterAliases(Map<?, ?> parameters, String field) {
        for (Object key : parameters.keySet()) {
            if (key instanceof String text && !text.isEmpty() && Character.isUpperCase(text.charAt(0))) {
                throw invalid(field + "." + text, "must use the documented lowercase-first parameter name");
            }
        }
    }

    private IllegalArgumentException invalid(String field, String message) {
        return new IllegalArgumentException("Automation plan " + field + " " + message);
    }
}
