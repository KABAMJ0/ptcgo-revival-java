package com.ptcgo.revival.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ptcgo.revival.Config;
import com.ptcgo.revival.Services;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundleRoutesTest {
    @TempDir
    Path bundleDir;

    private final HttpClient client = HttpClient.newHttpClient();
    private HttpApp app;
    private int port;
    private byte[] unity;
    private byte[] gzipManifest;

    @BeforeEach
    void setUp() throws Exception {
        unity = "UnityFS fake bundle bytes".getBytes(StandardCharsets.UTF_8);
        gzipManifest = gzip("asset manifest body");
        Files.write(bundleDir.resolve("en_US_Card_1.unity3d"), unity);
        Files.write(bundleDir.resolve("asset.manifest"), gzipManifest);
        Files.write(bundleDir.resolve("manifest.version"), "42".getBytes(StandardCharsets.UTF_8));
        Files.write(bundleDir.resolve("unlisted.unity3d"), "not in the manifest".getBytes(StandardCharsets.UTF_8));

        List<Map<String, Object>> files = List.of(
                entry("en_US_Card_1.unity3d", unity.length, "application/vnd.unity"),
                entry("asset.manifest", gzipManifest.length, "application/x-gzip"),
                entry("manifest.version", 2, "application/json"));
        Files.write(bundleDir.resolve("manifest.json"),
                new ObjectMapper().writeValueAsBytes(Map.of("files", files)));

        app = new HttpApp(new Services(new Config(), null, null, null, null, null, null, null, null), bundleDir);
        port = app.start(0);
    }

    @AfterEach
    void tearDown() {
        app.close();
    }

    @Test
    void servesListedUnityBundleWithLongCache() throws Exception {
        HttpResponse<byte[]> r = get("/cdn/en_US_Card_1.unity3d");
        assertEquals(200, r.statusCode());
        assertEquals("application/vnd.unity", r.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("public, max-age=31536000", r.headers().firstValue("Cache-Control").orElseThrow());
        assertArrayEquals(unity, r.body());
    }

    @Test
    void servesGzipAssetManifestWithoutCacheHeader() throws Exception {
        HttpResponse<byte[]> r = get("/asset.manifest");
        assertEquals(200, r.statusCode());
        assertEquals("application/x-gzip", r.headers().firstValue("Content-Type").orElseThrow());
        assertFalse(r.headers().firstValue("Cache-Control").isPresent());
        assertArrayEquals(gzipManifest, r.body());
    }

    @Test
    void servesManifestVersionAsText() throws Exception {
        HttpResponse<byte[]> r = get("/manifest.version");
        assertEquals(200, r.statusCode());
        assertEquals("application/json", r.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("42", new String(r.body(), StandardCharsets.UTF_8));
    }

    @Test
    void unlistedBundleIsEmpty404() throws Exception {
        HttpResponse<byte[]> r = get("/unlisted.unity3d");
        assertEquals(404, r.statusCode());
        assertEquals("0", r.headers().firstValue("Content-Length").orElseThrow());
        assertEquals(0, r.body().length);
    }

    @Test
    void unknownManifestIsEmpty404() throws Exception {
        HttpResponse<byte[]> r = get("/missing.manifest");
        assertEquals(404, r.statusCode());
        assertEquals("0", r.headers().firstValue("Content-Length").orElseThrow());
        assertEquals(0, r.body().length);
    }

    @Test
    void traversalIsEmpty404EvenWhenTheNameIsListed() throws Exception {
        // The JDK client normalises "..", so send the literal request line over a raw socket.
        String status = rawStatusLine("/cdn/../en_US_Card_1.unity3d");
        assertEquals("HTTP/1.1 404 Not Found", status);
        assertEquals("HTTP/1.1 404 Not Found", rawStatusLine("/../asset.manifest"));
    }

    private String rawStatusLine(String path) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
            return in.readLine();
        }
    }

    @Test
    void existingRoutesStillAnswer() throws Exception {
        HttpResponse<byte[]> r = get("/health");
        assertEquals(200, r.statusCode());
        assertTrue(new String(r.body(), StandardCharsets.UTF_8).contains("ok"));
    }

    private HttpResponse<byte[]> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static Map<String, Object> entry(String name, long size, String type) {
        return Map.of("name", name, "size", size, "sha256", "0".repeat(64), "content_type", type);
    }

    private static byte[] gzip(String text) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bos.toByteArray();
    }
}
