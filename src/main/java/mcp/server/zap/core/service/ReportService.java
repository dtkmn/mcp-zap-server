package mcp.server.zap.core.service;

import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.exception.ZapApiException;
import mcp.server.zap.core.gateway.EngineReportAccess;
import mcp.server.zap.core.gateway.EngineReportAccess.ReportGenerationRequest;
import mcp.server.zap.core.history.ScanHistoryLedgerService;
import mcp.server.zap.core.service.protection.ClientWorkspaceResolver;
import mcp.server.zap.extension.api.protection.ReportArtifactBoundary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.net.URI;
import java.util.stream.Stream;

/**
 * Service for generating ZAP reports.
 * This service provides methods to view available report templates and generate reports in various formats.
 */
@Slf4j
@Service
public class ReportService {
    private static final int DEFAULT_REPORT_READ_MAX_CHARS = 20000;
    private static final int MAX_REPORT_READ_MAX_CHARS = 200000;
    static final long MAX_REPORT_ARTIFACT_BYTES = 50L * 1024 * 1024;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern SAFE_WORKSPACE_SEGMENT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,80}$");
    private static final String REPORT_STAGING_DIRECTORY = ".report-staging";
    private static final String TARGET_REPORT_SECTIONS = "alertcount|instancecount|alertdetails";
    private static final Set<String> REPORT_IDENTITY_FIELDS = Set.of("@programName", "@version", "@generated", "created");
    private static final Set<String> SITE_IDENTITY_FIELDS = Set.of("@name", "@host", "@port", "@ssl");
    private static final JsonMapper REPORT_JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(100).maxStringLength((int) MAX_REPORT_ARTIFACT_BYTES).build())
            .build()).build();

    private final EngineReportAccess engineReportAccess;
    private ClientWorkspaceResolver clientWorkspaceResolver;
    private ReportArtifactBoundary reportArtifactBoundary;
    private ScanHistoryLedgerService scanHistoryLedgerService;

    @Value("${zap.report.directory:/zap/wrk}")
    private String reportDirectory;

    /**
     * Build-time dependency injection constructor.
     */
    public ReportService(EngineReportAccess engineReportAccess) {
        this.engineReportAccess = engineReportAccess;
    }

    @Autowired(required = false)
    void setClientWorkspaceResolver(ClientWorkspaceResolver clientWorkspaceResolver) {
        this.clientWorkspaceResolver = clientWorkspaceResolver;
    }

    @Autowired(required = false)
    void setReportArtifactBoundary(ReportArtifactBoundary reportArtifactBoundary) {
        this.reportArtifactBoundary = reportArtifactBoundary;
    }

    @Autowired(required = false)
    void setScanHistoryLedgerService(ScanHistoryLedgerService scanHistoryLedgerService) {
        this.scanHistoryLedgerService = scanHistoryLedgerService;
    }

    /**
     * List the available report templates.
     *
     * @return A string representation of the available report templates
     */
    public String viewTemplates() {
        StringBuilder sb = new StringBuilder();
        for (String template : engineReportAccess.listReportTemplates()) {
            sb.append(template).append("\n");
        }
        return sb.toString().trim();
    }


    /**
     * Generate a report for selected URL prefixes, or the full engine session when sites is blank.
     * Scoped reports omit session metadata which ZAP does not filter by site.
     *
     * @param reportTemplate The report template to use (e.g. traditional-html-plus/traditional-json-plus)
     * @param theme         The report theme (dark/light)
     * @param sites         HTTP(S) URL prefixes separated by pipes or commas; blank means full session
     * @return The path to the generated report file
     */
    public String generateReport(
            String reportTemplate,
            String theme,
            String sites
    ) {
        List<URI> scopes = reportScopes(sites);
        String effectiveTemplate = scopedTemplate(reportTemplate, scopes);
        String normalizedSites = scopes.isEmpty() ? "" : String.join("|", scopes.stream().map(URI::toString).toList());
        String normalizedTheme = normalizeTheme(effectiveTemplate, theme);
        try {
            Path configuredReportRoot = Paths.get(reportDirectory).toAbsolutePath().normalize();
            Path effectiveReportRoot = resolveWriteReportDirectory(configuredReportRoot);
            validateArtifactPath(configuredReportRoot, effectiveReportRoot, false);
            Files.createDirectories(effectiveReportRoot);
            inheritConfiguredRootPermissions(configuredReportRoot, effectiveReportRoot);
            String basename = "zap-report-" + UUID.randomUUID();
            Path reportPath;
            if (scopes.isEmpty()) {
                reportPath = generateEngineReport(effectiveReportRoot, basename, effectiveTemplate,
                        normalizedTheme, normalizedSites, "");
                requireGeneratedPath(effectiveReportRoot, reportPath);
            } else {
                reportPath = generateScopedReport(configuredReportRoot, effectiveReportRoot, basename,
                        effectiveTemplate, normalizedTheme, normalizedSites, scopes);
            }
            recordReportArtifact(reportPath.toString(), effectiveTemplate, normalizedTheme, normalizedSites);
            return reportPath.toString();
        } catch (IOException e) {
            log.error("Error preparing report directory {}: {}", reportDirectory, e.getMessage(), e);
            throw new ZapApiException("Error preparing report directory", e);
        }
    }

    private Path generateEngineReport(Path directory, String basename, String template,
                                      String theme, String sites, String sections) {
        String fileName = engineReportAccess.generateReport(new ReportGenerationRequest(
                "My ZAP Scan Report", template, theme, "", "", sites, sections, "", "",
                basename, "", directory.toString(), "false"));
        return resolveReportPath(directory, requireText(fileName, "generated report path"));
    }

    private Path generateScopedReport(Path configuredRoot, Path destinationRoot, String basename,
                                      String template, String theme, String sites, List<URI> scopes) throws IOException {
        Path stagingRoot = configuredRoot.resolve(REPORT_STAGING_DIRECTORY);
        validateArtifactPath(configuredRoot, stagingRoot, true);
        Files.createDirectories(stagingRoot);
        Path staging = Files.createDirectory(stagingRoot.resolve(UUID.randomUUID().toString()));
        try {
            inheritConfiguredRootPermissions(configuredRoot, staging);
            boolean json = template.equals("traditional-json") || template.equals("traditional-json-plus");
            Path raw = generateEngineReport(staging, basename, template, theme, sites,
                    json ? "" : TARGET_REPORT_SECTIONS);
            requireGeneratedPath(staging, raw);
            validateArtifact(configuredRoot, raw, true);
            Path prepared = raw;
            if (json) {
                prepared = staging.resolve("scoped.json");
                projectScopedJson(configuredRoot, raw, prepared, scopes);
            }
            Path published = destinationRoot.resolve(raw.getFileName());
            requireGeneratedPath(destinationRoot, published);
            validateArtifactPath(configuredRoot, published, false);
            Files.move(prepared, published, StandardCopyOption.ATOMIC_MOVE);
            return published;
        } finally {
            removeStagingDirectory(staging);
        }
    }

    private void requireGeneratedPath(Path directory, Path path) {
        if (!directory.equals(path.getParent())) {
            throw new IllegalArgumentException("Generated report must remain directly inside its assigned directory");
        }
    }

    private void removeStagingDirectory(Path staging) throws IOException {
        try (Stream<Path> entries = Files.walk(staging)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(entry);
            }
        }
    }

    private String scopedTemplate(String template, List<URI> scopes) {
        String normalized = requireText(template, "reportTemplate");
        if (scopes.isEmpty()) {
            return normalized;
        }
        return switch (normalized) {
            case "traditional-json", "traditional-json-plus", "traditional-md", "traditional-html" -> normalized;
            // HTML-plus has an unconditional session-wide active-scan counter.
            case "traditional-html-plus" -> "traditional-html";
            default -> throw new IllegalArgumentException("Target-scoped reports support traditional-json, "
                    + "traditional-json-plus, traditional-html, traditional-html-plus, or traditional-md only");
        };
    }

    private List<URI> reportScopes(String sites) {
        if (!hasText(sites)) {
            return List.of();
        }
        String[] values = sites.trim().split("[|,]", -1);
        if (values.length > 100) {
            throw new IllegalArgumentException("A report may include at most 100 URL prefixes");
        }
        List<URI> scopes = new ArrayList<>();
        for (String value : values) {
            URI uri = reportUri(requireText(value, "report URL prefix"));
            if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("Report URL prefixes must not contain queries or fragments");
            }
            uri = canonicalReportScope(uri);
            if (!scopes.contains(uri)) {
                scopes.add(uri);
            }
        }
        return List.copyOf(scopes);
    }

    private URI canonicalReportScope(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        int port = uri.getPort();
        String explicitPort = port == -1 || port == (scheme.equals("https") ? 443 : 80) ? "" : ":" + port;
        String path = uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return URI.create(scheme + "://" + host + explicitPort + path);
    }

    private URI reportUri(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Reports require an absolute HTTP(S) URL", e);
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getPort() == 0 || uri.getPort() < -1 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("Reports require an absolute HTTP(S) URL without user information");
        }
        return uri;
    }

    private boolean sameOrigin(URI first, URI second) {
        return first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost().equalsIgnoreCase(second.getHost())
                && effectivePort(first) == effectivePort(second);
    }

    private int effectivePort(URI uri) {
        return uri.getPort() == -1 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
    }

    private boolean withinReportScope(URI uri, List<URI> scopes, boolean exactPrefix) {
        String path = uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return scopes.stream().anyMatch(scope -> sameOrigin(scope, uri)
                && (exactPrefix ? scope.getRawPath().equals(path)
                : path.startsWith(scope.getRawPath())));
    }

    private void projectScopedJson(Path configuredRoot, Path raw, Path prepared, List<URI> scopes) throws IOException {
        BasicFileAttributes before = validateArtifact(configuredRoot, raw, true);
        try (ReportInputStream source = new ReportInputStream(Files.newInputStream(raw, LinkOption.NOFOLLOW_LINKS));
             JsonParser parser = REPORT_JSON.createParser(source);
             ReportOutputStream destination = new ReportOutputStream(Files.newOutputStream(prepared,
                     StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS));
             JsonGenerator output = REPORT_JSON.createGenerator(destination)) {
            requireToken(parser.nextToken(), JsonToken.START_OBJECT);
            output.writeStartObject();
            boolean sitesSeen = false;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                requireToken(parser.currentToken(), JsonToken.PROPERTY_NAME);
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if (REPORT_IDENTITY_FIELDS.contains(field)) {
                    requireToken(value, JsonToken.VALUE_STRING);
                    output.writeStringProperty(field, parser.getString());
                } else if ("site".equals(field)) {
                    output.writeName(field);
                    projectReportSites(parser, output, scopes);
                    sitesSeen = true;
                } else {
                    parser.skipChildren();
                }
            }
            if (!sitesSeen || parser.nextToken() != null) {
                throw new IllegalArgumentException("Invalid target-scoped JSON report structure");
            }
            output.writeEndObject();
            BasicFileAttributes after = validateArtifact(configuredRoot, raw, true);
            if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !Objects.equals(before.fileKey(), after.fileKey()) || source.bytesRead != before.size()) {
                throw new IllegalArgumentException("Report changed during target-scope validation");
            }
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Invalid target-scoped JSON report structure", e);
        }
        validateArtifact(configuredRoot, prepared, true);
    }

    private void projectReportSites(JsonParser parser, JsonGenerator output, List<URI> scopes) {
        requireToken(parser.currentToken(), JsonToken.START_ARRAY);
        output.writeStartArray();
        Set<URI> sites = new HashSet<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            requireToken(parser.currentToken(), JsonToken.START_OBJECT);
            output.writeStartObject();
            URI site = null;
            boolean alertsSeen = false;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                requireToken(parser.currentToken(), JsonToken.PROPERTY_NAME);
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if (SITE_IDENTITY_FIELDS.contains(field)) {
                    requireToken(value, JsonToken.VALUE_STRING);
                    if (field.equals("@name")) {
                        site = reportUri(parser.getString());
                        if (site.getRawQuery() != null || site.getRawFragment() != null
                                || !withinReportScope(site, scopes, true)) {
                            throw new IllegalArgumentException("Generated report site is outside the requested URL prefixes");
                        }
                    }
                } else if (field.equals("alerts")) {
                    output.writeName(field);
                    projectReportAlerts(parser, output, scopes);
                    alertsSeen = true;
                } else {
                    parser.skipChildren();
                }
            }
            if (site == null || !alertsSeen) {
                throw new IllegalArgumentException("Generated report site must contain identity and alerts");
            }
            site = canonicalReportScope(site);
            if (!sites.add(site)) {
                throw new IllegalArgumentException("Generated report contains duplicate site records");
            }
            output.writeStringProperty("@name", site.toString());
            output.writeStringProperty("@host", site.getHost());
            output.writeStringProperty("@port", Integer.toString(effectivePort(site)));
            output.writeStringProperty("@ssl", Boolean.toString("https".equalsIgnoreCase(site.getScheme())));
            output.writeEndObject();
        }
        if (!sites.equals(Set.copyOf(scopes))) {
            throw new IllegalArgumentException("Generated report must contain the requested sites");
        }
        output.writeEndArray();
    }

    private void projectReportAlerts(JsonParser parser, JsonGenerator output, List<URI> scopes) {
        requireToken(parser.currentToken(), JsonToken.START_ARRAY);
        output.writeStartArray();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            requireToken(parser.currentToken(), JsonToken.START_OBJECT);
            output.writeStartObject();
            boolean instancesSeen = false;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                requireToken(parser.currentToken(), JsonToken.PROPERTY_NAME);
                String field = parser.currentName();
                parser.nextToken();
                output.writeName(field);
                if (field.equals("instances")) {
                    projectAlertInstances(parser, output, scopes);
                    instancesSeen = true;
                } else {
                    output.copyCurrentStructure(parser);
                }
            }
            if (!instancesSeen) {
                throw new IllegalArgumentException("Generated report alert must contain instances");
            }
            output.writeEndObject();
        }
        output.writeEndArray();
    }

    private void projectAlertInstances(JsonParser parser, JsonGenerator output, List<URI> scopes) {
        requireToken(parser.currentToken(), JsonToken.START_ARRAY);
        output.writeStartArray();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            requireToken(parser.currentToken(), JsonToken.START_OBJECT);
            output.writeStartObject();
            boolean uriSeen = false;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                requireToken(parser.currentToken(), JsonToken.PROPERTY_NAME);
                String field = parser.currentName();
                parser.nextToken();
                if (field.equals("uri")) {
                    requireToken(parser.currentToken(), JsonToken.VALUE_STRING);
                    URI uri = reportUri(parser.getString());
                    if (!withinReportScope(uri, scopes, false)) {
                        throw new IllegalArgumentException("Generated report instance is outside the requested URL prefixes");
                    }
                    uriSeen = true;
                }
                output.writeName(field);
                output.copyCurrentStructure(parser);
            }
            if (!uriSeen) {
                throw new IllegalArgumentException("Generated report instance must contain a URL");
            }
            output.writeEndObject();
        }
        output.writeEndArray();
    }

    private void requireToken(JsonToken actual, JsonToken expected) {
        if (actual != expected) {
            throw new IllegalArgumentException("Invalid target-scoped JSON report structure");
        }
    }

    private void recordReportArtifact(String reportPath, String reportTemplate, String theme, String sites) {
        if (scanHistoryLedgerService == null) {
            return;
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        if (hasText(reportTemplate)) {
            metadata.put("template", reportTemplate.trim());
        }
        if (hasText(theme)) {
            metadata.put("theme", theme.trim());
        }
        scanHistoryLedgerService.recordReportArtifact(reportPath, reportTemplate, sites, metadata);
    }

    private String normalizeTheme(String reportTemplate, String theme) {
        if (!templateSupportsTheme(reportTemplate)) {
            return "";
        }
        return theme == null ? "" : theme;
    }

    private boolean templateSupportsTheme(String reportTemplate) {
        if (reportTemplate == null) {
            return false;
        }
        String normalizedTemplate = reportTemplate.toLowerCase(Locale.ROOT);
        return !normalizedTemplate.equals("traditional-html")
                && !normalizedTemplate.equals("traditional-pdf")
                && !normalizedTemplate.contains("json")
                && !normalizedTemplate.contains("xml")
                && !normalizedTemplate.contains("sarif")
                && !normalizedTemplate.endsWith("-md");
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public String readReport(
            String reportPath,
            Integer maxChars
    ) {
        int boundedMaxChars = validateMaxChars(maxChars);
        ReportChunk chunk = readReportChunk(reportPath, 0L, boundedMaxChars, null);
        String content = chunk.content();
        boolean truncated = !chunk.endOfFile() || content.length() > boundedMaxChars;
        int end = Math.min(content.length(), boundedMaxChars);
        if (end < content.length() && end > 0 && Character.isHighSurrogate(content.charAt(end - 1))) {
            end--;
        }
        String body = content.substring(0, end);
        return new StringBuilder()
                .append("Report artifact").append('\n')
                .append("Path: ").append(chunk.reportPath()).append('\n')
                .append("Characters Returned: ").append(body.length()).append('\n')
                .append("Truncated: ").append(truncated ? "yes" : "no").append('\n')
                .append('\n')
                .append(body)
                .toString();
    }

    /**
     * Read one bounded page while streaming the artifact to verify its identity.
     * Offsets and page lengths count Unicode code points, so pages never split a surrogate pair.
     */
    public ReportChunk readReportChunk(String reportPath, Long offset, Integer maxChars, String expectedSha256) {
        String normalizedReportPath = requireText(reportPath, "reportPath");
        int boundedMaxChars = validateMaxChars(maxChars);
        long start = offset == null ? 0 : offset;
        if (start < 0) {
            throw new IllegalArgumentException("offset must be zero or greater");
        }
        String expected = expectedSha256 == null ? null : expectedSha256.trim();
        if (expected != null && !SHA256.matcher(expected).matches()) {
            throw new IllegalArgumentException("expectedSha256 must contain 64 hexadecimal characters");
        }
        Path configuredRoot = Paths.get(reportDirectory).toAbsolutePath().normalize();
        Path reportRoot = resolveReadReportDirectory(configuredRoot);
        Path resolvedPath = resolveReportPath(reportRoot, normalizedReportPath);
        if (!resolvedPath.startsWith(reportRoot)) {
            throw new IllegalArgumentException("Report path must stay within the configured report directory");
        }
        try {
            BasicFileAttributes before = validateReadableArtifact(configuredRoot, resolvedPath);
            MessageDigest digest = sha256Digest();
            StringBuilder page = new StringBuilder(Math.min(boundedMaxChars, 8192));
            long characters = 0;
            long consumedBytes;
            try (ReportInputStream source = new ReportInputStream(
                         Files.newInputStream(resolvedPath, LinkOption.NOFOLLOW_LINKS));
                 DigestInputStream hashed = new DigestInputStream(source, digest);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(hashed,
                         StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                 .onUnmappableCharacter(CodingErrorAction.REPORT)))) {
                int first;
                while ((first = reader.read()) != -1) {
                    int codePoint = first;
                    if (Character.isHighSurrogate((char) first)) {
                        int second = reader.read();
                        if (second == -1 || !Character.isLowSurrogate((char) second)) {
                            throw new IOException("Invalid Unicode in report artifact");
                        }
                        codePoint = Character.toCodePoint((char) first, (char) second);
                    }
                    if (characters >= start && characters - start < boundedMaxChars) {
                        page.appendCodePoint(codePoint);
                    }
                    characters++;
                }
                consumedBytes = source.bytesRead;
            }
            BasicFileAttributes after = validateReadableArtifact(configuredRoot, resolvedPath);
            if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !Objects.equals(before.fileKey(), after.fileKey()) || consumedBytes != before.size()) {
                throw new IllegalArgumentException("Report changed while reading; restart retrieval");
            }
            if (start > characters) {
                throw new IllegalArgumentException("offset is beyond the end of the report");
            }
            String actualSha = HexFormat.of().formatHex(digest.digest());
            if (expected != null && !expected.equalsIgnoreCase(actualSha)) {
                throw new IllegalArgumentException("Report SHA-256 changed; restart retrieval from offset zero");
            }
            int returned = page.codePointCount(0, page.length());
            boolean endOfFile = start + returned == characters;
            return new ReportChunk(resolvedPath.toString(), start, endOfFile ? null : start + returned,
                    returned, endOfFile, characters, "unicodeCodePoints", actualSha, page.toString());
        } catch (IOException e) {
            log.error("Error reading report {}: {}", resolvedPath, e.getMessage(), e);
            throw new ZapApiException("Error reading generated report", e);
        }
    }

    private BasicFileAttributes validateReadableArtifact(Path configuredRoot, Path path) throws IOException {
        return validateArtifact(configuredRoot, path, false);
    }

    private BasicFileAttributes validateArtifact(Path configuredRoot, Path path, boolean allowStaging) throws IOException {
        validateArtifactPath(configuredRoot, path, allowStaging);
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new IllegalArgumentException("Report path must identify a regular file");
        }
        if (attributes.size() > MAX_REPORT_ARTIFACT_BYTES) {
            throw new IllegalArgumentException("Report exceeds the 50 MiB artifact limit");
        }
        try {
            if (((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1) {
                throw new IllegalArgumentException("Report files must not have multiple hard links");
            }
        } catch (UnsupportedOperationException ignored) {
            // File systems without Unix attributes still enforce workspace and symbolic-link checks.
        }
        return attributes;
    }

    private void validateArtifactPath(Path configuredRoot, Path path, boolean allowStaging) {
        if (!path.startsWith(configuredRoot) || Files.isSymbolicLink(configuredRoot)) {
            throw new IllegalArgumentException("Report path must remain under its configured directory without symbolic links");
        }
        Path current = configuredRoot;
        for (Path segment : configuredRoot.relativize(path)) {
            if (!allowStaging && segment.toString().equals(REPORT_STAGING_DIRECTORY)) {
                throw new IllegalArgumentException("Report generation staging files cannot be read");
            }
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Report paths must not contain symbolic links");
            }
        }
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for report artifacts", e);
        }
    }

    public record ReportChunk(String reportPath, long offset, Long nextOffset, int charactersReturned,
                              boolean endOfFile, long totalCharacters, String offsetUnit,
                              String artifactSha256, String content) {
    }

    private static final class ReportInputStream extends FilterInputStream {
        private long bytesRead;

        private ReportInputStream(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            count(value == -1 ? 0 : 1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int boundedLength = (int) Math.min(length, MAX_REPORT_ARTIFACT_BYTES + 1 - bytesRead);
            int count = super.read(buffer, offset, boundedLength);
            count(Math.max(count, 0));
            return count;
        }

        private void count(int count) {
            bytesRead += count;
            if (bytesRead > MAX_REPORT_ARTIFACT_BYTES) {
                throw new IllegalArgumentException("Report exceeds the 50 MiB artifact limit");
            }
        }
    }

    private static final class ReportOutputStream extends FilterOutputStream {
        private long bytesWritten;

        private ReportOutputStream(OutputStream output) {
            super(output);
        }

        @Override
        public void write(int value) throws IOException {
            count(1);
            out.write(value);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            count(length);
            out.write(buffer, offset, length);
        }

        private void count(int count) {
            bytesWritten += count;
            if (bytesWritten > MAX_REPORT_ARTIFACT_BYTES) {
                throw new IllegalArgumentException("Report exceeds the 50 MiB artifact limit");
            }
        }
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be null or blank");
        }
        return value.trim();
    }

    private int validateMaxChars(Integer maxChars) {
        int effectiveMaxChars = maxChars == null ? DEFAULT_REPORT_READ_MAX_CHARS : maxChars;
        if (effectiveMaxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be greater than 0");
        }
        return Math.min(effectiveMaxChars, MAX_REPORT_READ_MAX_CHARS);
    }

    private Path resolveReportPath(Path reportRoot, String reportPath) {
        Path candidate = Paths.get(reportPath);
        if (candidate.isAbsolute()) {
            return candidate.toAbsolutePath().normalize();
        }
        return reportRoot.resolve(candidate).normalize();
    }

    private Path resolveWriteReportDirectory(Path configuredReportRoot) {
        if (reportArtifactBoundary == null) {
            return workspaceScopedDirectory(configuredReportRoot);
        }
        return requireDirectoryUnderConfiguredRoot(
                configuredReportRoot,
                reportArtifactBoundary.resolveWriteDirectory(configuredReportRoot),
                "write"
        );
    }

    private Path resolveReadReportDirectory(Path configuredReportRoot) {
        if (reportArtifactBoundary == null) {
            return workspaceScopedDirectory(configuredReportRoot);
        }
        return requireDirectoryUnderConfiguredRoot(
                configuredReportRoot,
                reportArtifactBoundary.resolveReadDirectory(configuredReportRoot),
                "read"
        );
    }

    private Path requireDirectoryUnderConfiguredRoot(Path configuredReportRoot,
                                                     Path extensionDirectory,
                                                     String accessMode) {
        if (extensionDirectory == null) {
            throw new IllegalArgumentException("Report artifact boundary returned null " + accessMode + " directory");
        }
        Path normalizedRoot = configuredReportRoot.toAbsolutePath().normalize();
        Path normalizedDirectory = extensionDirectory.toAbsolutePath().normalize();
        if (!normalizedDirectory.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException(
                    "Report artifact boundary " + accessMode + " directory must stay within the configured report directory"
            );
        }
        return normalizedDirectory;
    }

    private Path workspaceScopedDirectory(Path configuredReportRoot) {
        return configuredReportRoot
                .resolve("workspaces")
                .resolve(workspacePathSegment(currentWorkspaceId()))
                .normalize();
    }

    private void inheritConfiguredRootPermissions(Path configuredReportRoot, Path effectiveReportRoot) throws IOException {
        Path normalizedRoot = configuredReportRoot.toAbsolutePath().normalize();
        Path normalizedEffectiveRoot = effectiveReportRoot.toAbsolutePath().normalize();
        if (normalizedRoot.equals(normalizedEffectiveRoot) || !Files.exists(normalizedRoot)) {
            return;
        }
        try {
            Set<PosixFilePermission> rootPermissions = Files.getPosixFilePermissions(normalizedRoot);
            if (!normalizedEffectiveRoot.startsWith(normalizedRoot)) {
                Files.setPosixFilePermissions(normalizedEffectiveRoot, rootPermissions);
                return;
            }
            Path current = normalizedRoot;
            for (Path segment : normalizedRoot.relativize(normalizedEffectiveRoot)) {
                current = current.resolve(segment);
                Files.setPosixFilePermissions(current, rootPermissions);
            }
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX file systems do not expose permissions through java.nio.
        }
    }

    private String currentWorkspaceId() {
        if (clientWorkspaceResolver == null) {
            return "default-workspace";
        }
        String workspaceId = clientWorkspaceResolver.resolveCurrentWorkspaceId();
        return hasText(workspaceId) ? workspaceId.trim() : "default-workspace";
    }

    private String workspacePathSegment(String workspaceId) {
        String normalized = hasText(workspaceId) ? workspaceId.trim() : "default-workspace";
        if (SAFE_WORKSPACE_SEGMENT.matcher(normalized).matches()) {
            return normalized;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return "ws-" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for workspace report paths", e);
        }
    }

}
