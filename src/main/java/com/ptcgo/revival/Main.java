package com.ptcgo.revival;

import com.ptcgo.revival.http.HttpApp;
import com.ptcgo.revival.net.GameServer;
import com.ptcgo.revival.packet.HandshakeHandler;
import com.ptcgo.revival.packet.PacketRouter;
import com.ptcgo.revival.tls.TlsContexts;

import java.util.List;
import java.util.concurrent.CountDownLatch;

public final class Main {
    public static void main(String[] args) throws Exception {
        Config cfg = new Config();
        if (args.length > 0 && args[0].equals("--gen-cert")) {
            String host = args.length > 1 ? args[1] : cfg.publicHost;
            TlsContexts.generate(cfg, host, true);
            System.out.println("Wrote " + cfg.keystorePath + " for " + host);
            return;
        }
        PacketRouter router = new PacketRouter(List.of(new HandshakeHandler()));
        try (HttpApp http = new HttpApp(cfg);
             GameServer game = new GameServer(cfg, router, TlsContexts.serverContext(cfg))) {
            http.start(cfg.httpPort);
            game.start(cfg.tcpPort);
            new CountDownLatch(1).await();
        }
    }
}
