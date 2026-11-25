package com.ptcgo.revival;

import java.nio.file.Path;

/** Runtime configuration, read from the same PTCGO_* environment variables as the Python server. */
public final class Config {
    public final String publicHost = env("PTCGO_PUBLIC_HOST", "127.0.0.1");
    public final int httpPort = Integer.parseInt(env("PTCGO_HTTP_PORT", "8000"));
    public final int tcpPort = Integer.parseInt(env("PTCGO_TCP_PORT", "39389"));
    public final String httpBaseUrlOverride = stripTrailingSlash(env("PTCGO_HTTP_BASE_URL", ""));
    public final Path assetDir = Path.of(env("PTCGO_ASSET_DIR", "assets"));
    /** Exported asset bundles (manifest.json + the files it lists), served by HttpApp. */
    public final Path bundleDir = Path.of(env("PTCGO_BUNDLE_DIR", "bundles"));
    public final Path keystorePath = Path.of(env("PTCGO_KEYSTORE", "certs/server.p12"));
    public final String keystorePassword = env("PTCGO_KEYSTORE_PASSWORD", "changeit");
    public final int clientVersion = Integer.parseInt(env("PTCGO_CLIENT_VERSION", "5788"));
    /** Idle limit once logged in / before login (seconds). */
    public final int idleTimeoutSeconds = (int) Double.parseDouble(env("PTCGO_IDLE_TIMEOUT", "240"));
    public final int preauthTimeoutSeconds = (int) Double.parseDouble(env("PTCGO_PREAUTH_TIMEOUT", "60"));
    public final int maxDecompressedBytes = Integer.parseInt(env("PTCGO_MAX_DECOMPRESSED", String.valueOf(16 * 1024 * 1024)));
    public final double inboundRatePerSec = Double.parseDouble(env("PTCGO_INBOUND_RATE", "80"));
    public final double inboundRateBurst = Double.parseDouble(env("PTCGO_INBOUND_BURST", "240"));
    public final Path dbPath = Path.of(env("PTCGO_DB_PATH", "ptcgo_server.db"));
    /** Guest / DeviceID / mobile logins create credential-less accounts; off unless PTCGO_ALLOW_GUESTS=1. */
    public final boolean allowGuests = env("PTCGO_ALLOW_GUESTS", "0").equals("1");
    public final Path catalogDir = Path.of(env("PTCGO_CATALOG_DIR", "catalog"));
    public final String gameVersion = "2.95.0.5815";

    /** Base URL the client should use for HTTP, preferring an explicit override, then the Host header. */
    public String clientBaseUrl(String requestHost) {
        if (!httpBaseUrlOverride.isEmpty()) return httpBaseUrlOverride;
        String host = requestHost == null || requestHost.isBlank() ? publicHost + ":" + httpPort : requestHost.strip();
        return "http://" + host;
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v.strip();
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
