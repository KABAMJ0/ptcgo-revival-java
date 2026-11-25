# PTCGO Revival (Java)

Java 21 port of the Python PTCGO revival server ([ptcgo-revival](https://github.com/KABAMJ0/ptcgo-revival)),
which emulates the discontinued Pokémon TCG Online server for an existing client.

## Status: stage 1 (core server)

| Area | State |
| --- | --- |
| Warg framing (header, flags, zlib, zlib-bomb cap, JSON envelope, protobuf envelope) | done, unit-tested |
| TLS game socket (39389), virtual thread per client, idle timeouts, inbound rate limit | done |
| Handshake: `RequestConnectionServiceWithVersion`, `RequestSession`, `RequestLogin`, `Ping` | done, integration-tested |
| HTTP (8000): `/health`, `manifest.json`, `messaging/manifest.json`, static assets | done |
| Auth, data sync, shop, social, trade, tournaments, quests, league | not ported |
| Database (SQLite/MariaDB), card model + loader | not ported |
| Protobuf messages (`.proto` sources live in the Python repo) | not ported |
| Bundle manifest builder (`*.manifest`, `manifest.version`) | not ported |
| Match engine, card effects (~20k scripts), AI, admin panel | not ported |

## Build and run

```sh
mvn test
mvn package
java -jar target/ptcgo-revival-0.1.0-SNAPSHOT.jar
```

The first start creates a self-signed keystore at `certs/server.p12` for `PTCGO_PUBLIC_HOST`.
The client checks the certificate name against the host it dials, so regenerate it after a hostname change:

```sh
java -jar target/ptcgo-revival-0.1.0-SNAPSHOT.jar --gen-cert play.example.com
```

## Configuration

Same variables as the Python server: `PTCGO_PUBLIC_HOST`, `PTCGO_HTTP_PORT` (8000), `PTCGO_TCP_PORT` (39389),
`PTCGO_HTTP_BASE_URL`, `PTCGO_CLIENT_VERSION`, `PTCGO_IDLE_TIMEOUT`, `PTCGO_PREAUTH_TIMEOUT`,
`PTCGO_MAX_DECOMPRESSED`, `PTCGO_INBOUND_RATE`, `PTCGO_INBOUND_BURST`. New: `PTCGO_ASSET_DIR` (default `assets`),
`PTCGO_KEYSTORE`, `PTCGO_KEYSTORE_PASSWORD`.

The client always dials 39389 directly: the game hostname must be DNS-only (not proxied) if it sits behind Cloudflare.
