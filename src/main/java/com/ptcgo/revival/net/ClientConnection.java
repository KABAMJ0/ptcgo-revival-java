package com.ptcgo.revival.net;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.Config;
import com.ptcgo.revival.Services;
import com.ptcgo.revival.db.Account;
import com.ptcgo.revival.packet.ClientContext;
import com.ptcgo.revival.packet.ClientState;
import com.ptcgo.revival.packet.PacketRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** One connected game client. Reads on its own virtual thread; writes are serialized. */
public final class ClientConnection implements ClientContext, Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(ClientConnection.class);

    private final Socket socket;
    private final Services services;
    private final Config config;
    private final PacketRouter router;
    private final String sessionId = UUID.randomUUID().toString(); // must parse as a C# Guid
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object writeLock = new Object();
    private final ClientState state = new ClientState();
    private final Runnable onClose;

    private volatile Account account; // set by the auth handler once login lands

    // Inbound token bucket (abuse guard); generous enough that legitimate play never trips it.
    private double tokens;
    private long bucketTs = System.nanoTime();
    private long rateWarnedAt;

    public ClientConnection(Socket socket, Services services, PacketRouter router, Runnable onClose) {
        this.socket = socket;
        this.services = services;
        this.config = services.config();
        this.router = router;
        this.onClose = onClose;
        this.tokens = config.inboundRateBurst;
    }

    @Override
    public void authenticated(Account account) {
        this.account = account;
        services.registry().register(account.accountId(), this);
    }

    @Override
    public Account account() {
        return account;
    }

    @Override
    public Services services() {
        return services;
    }

    @Override
    public void run() {
        try (DataInputStream in = new DataInputStream(socket.getInputStream())) {
            while (!closed.get()) {
                socket.setSoTimeout(1000 * (account != null ? config.idleTimeoutSeconds : config.preauthTimeoutSeconds));
                byte[] header = new byte[WargCodec.HEADER_SIZE];
                try {
                    in.readFully(header);
                } catch (SocketTimeoutException e) {
                    LOG.info("[TCP] [{}] Idle timeout; closing.", remoteAddress());
                    break;
                }
                int[] h = WargCodec.decodeHeader(header);
                byte[] body = new byte[h[0]];
                in.readFully(body);
                if (!rateOk()) continue;

                int requestId = h[1];
                int flags = h[2];
                byte[] data = WargCodec.inflateIfNeeded(body, flags, config.maxDecompressedBytes);
                if (WargFlags.has(flags, WargFlags.PROTOBUF)) {
                    LOG.debug("[TCP] [{}] protobuf frame ({} bytes) not handled yet", remoteAddress(), data.length);
                    continue;
                }
                JsonNode message = WargCodec.parseJson(data);
                router.route(this, message, requestId, flags);
            }
        } catch (EOFException e) {
            LOG.info("[TCP] [{}] Connection closed by peer.", remoteAddress());
        } catch (IOException e) {
            LOG.info("[TCP] [{}] Connection ended: {}", remoteAddress(), e.getMessage());
        } catch (RuntimeException e) {
            LOG.error("[TCP] [{}] Error handling client", remoteAddress(), e);
        } finally {
            close();
        }
    }

    private boolean rateOk() {
        double rate = config.inboundRatePerSec;
        if (rate <= 0) return true;
        long now = System.nanoTime();
        tokens = Math.min(config.inboundRateBurst, tokens + (now - bucketTs) / 1e9 * rate);
        bucketTs = now;
        if (tokens < 1.0) {
            if (now - rateWarnedAt > 5_000_000_000L) {
                LOG.warn("[TCP] [{}] Inbound rate limit hit; dropping packets.", remoteAddress());
                rateWarnedAt = now;
            }
            return false;
        }
        tokens -= 1.0;
        return true;
    }

    @Override
    public ClientState state() {
        return state;
    }

    @Override
    public void send(String messageName, Map<String, ?> payload, int requestId, int flags) {
        writeFrame(WargCodec.frameMessage(messageName, payload, requestId, flags));
    }

    @Override
    public void sendRaw(byte[] body, int requestId, int flags) {
        writeFrame(WargCodec.frame(body, requestId, flags));
    }

    private void writeFrame(byte[] frame) {
        if (closed.get()) return;
        synchronized (writeLock) {
            try {
                OutputStream out = socket.getOutputStream();
                out.write(frame);
                out.flush();
            } catch (IOException e) {
                LOG.warn("[TCP] [{}] Outbound failed: {}", remoteAddress(), e.getMessage());
                close();
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        Account a = account;
        if (a != null) {
            services.registry().unregister(a.accountId(), this);
            com.ptcgo.revival.social.SocialModule.onDisconnect(this); // presence Offline, challenge and room cleanup
            com.ptcgo.revival.game.match.VersusModule.onDisconnect(this); // queues, pairings, detach from a live game
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // already gone
        }
        onClose.run();
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return (InetSocketAddress) socket.getRemoteSocketAddress();
    }

    @Override
    public InetSocketAddress localAddress() {
        return (InetSocketAddress) socket.getLocalSocketAddress();
    }
}
