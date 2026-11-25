package com.ptcgo.revival.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ptcgo.revival.Config;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Patch manifest, messaging manifest and static assets on the HTTP port (default 8000).
 * The bundle manifest builder (UnityPy in the Python server) is not ported yet: manifest.version
 * and *.manifest answer 404 until that stage lands.
 */
public final class HttpApp implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(HttpApp.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> TYPES = Map.of(
            "png", "image/png", "jpg", "image/jpeg", "json", "application/json",
            "txt", "text/plain; charset=utf-8", "unity3d", "application/octet-stream");

    private final Config config;
    private HttpServer server;

    public HttpApp(Config config) {
        this.config = config;
    }

    public int start(int port) throws IOException {
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
            } else if (path.endsWith("messaging/manifest.json")) {
                json(ex, Map.of("cdn_url", base(ex) + "/", "messaging", List.of()));
            } else if (path.endsWith("manifest.json")) {
                json(ex, patchManifest(ex));
            } else if (path.endsWith(".unity3d") || path.endsWith(".png") || path.endsWith(".txt")) {
                serveAsset(ex, path);
            } else {
                respond(ex, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
            }
        } finally {
            ex.close();
        }
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

    private void serveAsset(HttpExchange ex, String urlPath) throws IOException {
        Path root = config.assetDir.toAbsolutePath().normalize();
        Path file = root.resolve(urlPath.substring(1)).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
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
