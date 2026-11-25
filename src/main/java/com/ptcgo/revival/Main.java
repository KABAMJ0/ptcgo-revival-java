package com.ptcgo.revival;

import com.ptcgo.revival.auth.CasTickets;
import com.ptcgo.revival.db.AccountRepository;
import com.ptcgo.revival.db.Database;
import com.ptcgo.revival.http.HttpApp;
import com.ptcgo.revival.net.ClientRegistry;
import com.ptcgo.revival.net.GameServer;
import com.ptcgo.revival.packet.AuthHandler;
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
        Database db = new Database(cfg);
        db.migrate();
        AccountRepository accounts = new AccountRepository(db);
        if (args.length > 1 && args[0].equals("--create-admin")) { // --create-admin <name> <password>
            System.out.println(accounts.create(args[1], args.length > 2 ? args[2] : "password", true).isPresent()
                    ? "Created admin " + args[1] : "Username already exists");
            return;
        }
        Services services = new Services(cfg, accounts, new CasTickets(), new ClientRegistry());
        PacketRouter router = new PacketRouter(List.of(new HandshakeHandler(), new AuthHandler()));
        try (HttpApp http = new HttpApp(services);
             GameServer game = new GameServer(services, router, TlsContexts.serverContext(cfg))) {
            http.start(cfg.httpPort);
            game.start(cfg.tcpPort);
            new CountDownLatch(1).await();
        }
    }
}
