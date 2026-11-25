package com.ptcgo.revival.net;

import com.ptcgo.revival.Services;
import com.ptcgo.revival.packet.PacketRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import java.io.IOException;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** TLS game socket (default 39389). One virtual thread per client. */
public final class GameServer implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(GameServer.class);

    private final Services services;
    private final PacketRouter router;
    private final SSLContext tls; // null = plain TCP (tests only)
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<ClientConnection> clients = ConcurrentHashMap.newKeySet();
    private java.net.ServerSocket server;
    private volatile boolean running;

    public GameServer(Services services, PacketRouter router, SSLContext tls) {
        this.services = services;
        this.router = router;
        this.tls = tls;
    }

    /** Binds and starts accepting; returns the bound port (useful when port 0 is requested). */
    public int start(int port) throws IOException {
        server = tls != null
                ? (SSLServerSocket) tls.getServerSocketFactory().createServerSocket(port)
                : new java.net.ServerSocket(port);
        running = true;
        Thread.ofPlatform().name("game-accept").daemon(true).start(this::acceptLoop);
        LOG.info("[TCP] Game socket listening on {} ({})", server.getLocalPort(), tls != null ? "TLS" : "plain");
        return server.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                ClientConnection[] holder = new ClientConnection[1];
                holder[0] = new ClientConnection(s, services, router, () -> clients.remove(holder[0]));
                clients.add(holder[0]);
                pool.execute(holder[0]);
            } catch (IOException e) {
                if (running) LOG.warn("[TCP] accept failed: {}", e.getMessage());
            }
        }
    }

    public int clientCount() {
        return clients.size();
    }

    @Override
    public void close() throws IOException {
        running = false;
        if (server != null) server.close();
        clients.forEach(ClientConnection::close);
        pool.shutdownNow();
    }
}
