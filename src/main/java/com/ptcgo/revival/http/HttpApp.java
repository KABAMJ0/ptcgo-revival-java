package com.ptcgo.revival.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ptcgo.revival.Config;
import com.ptcgo.revival.Services;
import com.ptcgo.revival.db.Account;
import com.ptcgo.revival.db.AccountRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Patch manifest, messaging manifest and static assets on the HTTP port (default 8000). Asset bundles
 * ({@code manifest.version}, {@code *.manifest}, {@code *.unity3d}) are served from {@code PTCGO_BUNDLE_DIR},
 * which the Python {@code tools/export_bundles.py} fills; only files listed in its {@code manifest.json} are served.
 */
public final class HttpApp implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(HttpApp.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> TYPES = Map.of(
            "png", "image/png", "jpg", "image/jpeg", "json", "application/json",
            "txt", "text/plain; charset=utf-8", "unity3d", "application/octet-stream");

    private static final String LOGIN_FORM = """
            <!DOCTYPE html>
            <html>
            <body>
                <form action="" method="post">
                    <input type="hidden" name="lt" value="LT-MOCK-TOKEN-12345" />
                    <input type="hidden" value="LT-MOCK-TOKEN-12345" name="lt" />
                    <input name="lt" type="hidden" value="LT-MOCK-TOKEN-12345" />

                    <input type="hidden" name="execution" value="e1s1" />
                    <input type="hidden" value="e1s1" name="execution" />
                    <input name="execution" type="hidden" value="e1s1" />

                    <input type="text" name="username" />
                    <input type="password" name="password" />
                    <input type="submit" name="submit" value="Login" />
                </form>
            </body>
            </html>
            """;

    private final Config config;
    private final Services services;
    private final Path bundleDir;
    private BundleStore bundles;
    private HttpServer server;

    public HttpApp(Services services) {
        this(services, services.config().bundleDir);
    }

    HttpApp(Services services, Path bundleDir) {
        this.services = services;
        this.config = services.config();
        this.bundleDir = bundleDir;
    }

    public int start(int port) throws IOException {
        bundles = BundleStore.load(bundleDir);
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::dispatch);
        server.start();
        LOG.info("[HTTP] Listening on {}", server.getAddress().getPort());
        return server.getAddress().getPort();
    }

    private void dispatch(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/health")) {
                json(ex, Map.of("status", "ok"));
            } else if (path.endsWith("sso/login")) {
                casLogin(ex);
            } else if (path.endsWith("messaging/manifest.json")) {
                json(ex, Map.of("cdn_url", base(ex) + "/", "messaging", List.of()));
            } else if (path.endsWith("manifest.json")) {
                json(ex, patchManifest(ex));
            } else if (path.endsWith("manifest.version") || path.endsWith(".manifest") || path.endsWith(".unity3d")) {
                serveBundle(ex, path);
            } else if (path.endsWith(".png") || path.endsWith(".txt")) {
                serveAsset(ex, path);
            } else {
                respond(ex, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
            }
        } finally {
            ex.close();
        }
    }

    /** CAS login page: GET serves the form the client scrapes, POST verifies and redirects with a ticket. */
    private void casLogin(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equals("GET")) {
            respond(ex, 200, "text/html", LOGIN_FORM.getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (!ex.getRequestMethod().equals("POST")) {
            respond(ex, 405, "text/plain", "Method not allowed".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String form = new String(ex.getRequestBody().readNBytes(64 * 1024), StandardCharsets.UTF_8);
        String username = formValue(form, "username");
        String password = formValue(form, "password");
        try {
            AccountRepository repo = services.accounts();
            Optional<Account> acc = repo.findByUsername(username);
            if (acc.isEmpty() && !username.isBlank()) acc = services.createAccount(username, password, false); // private server: auto-register
            if (acc.isPresent() && !acc.get().banned() && AccountRepository.verifyPassword(acc.get().passwordHash(), password)) {
                String ticket = "ST-" + UUID.randomUUID();
                services.tickets().issue(ticket, username);
                byte[] body = ("ticket=" + ticket + "\n?ticket=" + ticket + "\n&ticket=" + ticket)
                        .getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Location", base(ex) + "/cas/sso/login?ticket=" + ticket);
                respond(ex, 302, "text/html", body);
                return;
            }
        } catch (SQLException e) {
            LOG.error("[HTTP] CAS login failed", e);
        }
        respond(ex, 200, "text/html", "Authentication Failed. Check your credentials.".getBytes(StandardCharsets.UTF_8));
    }

    static String formValue(String form, String key) {
        for (String pair : form.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8).equals(key)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private String base(HttpExchange ex) {
        return config.clientBaseUrl(ex.getRequestHeaders().getFirst("Host"));
    }

    private Map<String, Object> patchManifest(HttpExchange ex) {
        String base = base(ex);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("LatestWindowsPatcherVersion", 240);
        m.put("LatestMacPatcherVersion", 240);
        m.put("LatestWindowsClientVersion", config.clientVersion);
        m.put("LatestMacClientVersion", config.clientVersion);
        m.put("WindowsPatcherDownloadLink", base + "/refresher.zip");
        m.put("MacPatcherDownloadLink", base + "/refresher.zip");
        return m;
    }

    /**
     * Bundle files from PTCGO_BUNDLE_DIR, matched by file name and only when listed in its manifest.
     * Unlisted names, and any path with a {@code ..} segment, answer 404 with an empty body as Python does.
     * An unlisted {@code *.unity3d} falls back to the plain asset directory (the pre-bundle behaviour).
     */
    private void serveBundle(HttpExchange ex, String urlPath) throws IOException {
        String name = urlPath.substring(urlPath.lastIndexOf('/') + 1);
        boolean traversal = Arrays.asList(urlPath.split("/")).contains("..");
        Optional<BundleStore.Entry> entry = traversal ? Optional.empty() : bundles.find(name);
        if (entry.isEmpty()) {
            if (!traversal && name.endsWith(".unity3d") && assetFile(urlPath) != null) {
                serveAsset(ex, urlPath);
            } else {
                notFoundEmpty(ex);
            }
            return;
        }
        Path file = bundles.file(entry.get());
        if (!Files.isRegularFile(file) || Files.size(file) != entry.get().size()) {
            LOG.warn("[HTTP] Bundle {} is missing or does not match its manifest size", name);
            notFoundEmpty(ex);
            return;
        }
        String type;
        if (name.endsWith(".unity3d")) {
            type = "application/vnd.unity";
            ex.getResponseHeaders().set("Cache-Control", "public, max-age=31536000");
        } else if (name.endsWith(".manifest")) {
            type = "application/x-gzip";
        } else {
            type = "application/json"; // manifest.version: Python answers the version as JSON
        }
        respond(ex, 200, type, Files.readAllBytes(file));
    }

    private static void notFoundEmpty(HttpExchange ex) throws IOException {
        ex.sendResponseHeaders(404, -1);
    }

    /** The file under the asset directory for this URL path, or null when there is none (or it escapes the root). */
    private Path assetFile(String urlPath) {
        Path root = config.assetDir.toAbsolutePath().normalize();
        Path file = root.resolve(urlPath.substring(1)).normalize();
        return file.startsWith(root) && Files.isRegularFile(file) ? file : null;
    }

    private void serveAsset(HttpExchange ex, String urlPath) throws IOException {
        Path file = assetFile(urlPath);
        if (file == null) {
            respond(ex, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String name = file.getFileName().toString();
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
        respond(ex, 200, TYPES.getOrDefault(ext, "application/octet-stream"), Files.readAllBytes(file));
    }

    private void json(HttpExchange ex, Object body) throws IOException {
        respond(ex, 200, "application/json", JSON.writeValueAsBytes(body));
    }

    private void respond(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        boolean head = "HEAD".equals(ex.getRequestMethod());
        ex.sendResponseHeaders(status, head ? -1 : body.length);
        if (!head) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }

    @Override
    public void close() {
        if (server != null) server.stop(0);
    }
}
