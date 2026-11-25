package com.ptcgo.revival.tls;

import com.ptcgo.revival.Config;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Game-socket TLS. The client checks the certificate name against the host it dials, so the
 * keystore must be regenerated after any hostname change ({@code --gen-cert}).
 */
public final class TlsContexts {
    private TlsContexts() {}

    public static SSLContext serverContext(Config cfg) throws Exception {
        if (!Files.exists(cfg.keystorePath)) generate(cfg, cfg.publicHost, false);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(cfg.keystorePath)) {
            ks.load(in, cfg.keystorePassword.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, cfg.keystorePassword.toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    /** Creates a self-signed PKCS12 keystore for {@code host} using the JDK's keytool. */
    public static void generate(Config cfg, String host, boolean force) throws IOException, InterruptedException {
        Path ks = cfg.keystorePath;
        if (Files.exists(ks)) {
            if (!force) return;
            Files.delete(ks);
        }
        if (ks.getParent() != null) Files.createDirectories(ks.getParent());
        boolean ip = host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+") || host.contains(":");
        List<String> cmd = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "ptcgo", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "3650", "-storetype", "PKCS12",
                "-keystore", ks.toString(), "-storepass", cfg.keystorePassword,
                "-dname", "CN=" + host,
                "-ext", "SAN=" + (ip ? "IP:" : "DNS:") + host + ",DNS:localhost,IP:127.0.0.1"));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IOException("keytool failed: " + out);
        }
    }

    static String describe(InetAddress a) {
        return a.getHostAddress();
    }
}
