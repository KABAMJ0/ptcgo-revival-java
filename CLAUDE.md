# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Java 21 / Maven port of the Python server in `KABAMJ0/ptcgo-revival`. That repo is the reference: the
reverse-engineered protocol is in its `notes/client-*.md`, and behaviour should match its handlers.

## Commands
- `mvn -q test` · single test: `mvn -q test -Dtest=WargCodecTest#headerRoundTrip`
- `mvn -q -DskipTests package` builds the shaded jar `target/ptcgo-revival-0.1.0-SNAPSHOT.jar` (main class `com.ptcgo.revival.Main`).
- `java -jar ... --gen-cert <host>` regenerates `certs/server.p12`.

## Architecture
- `net/`: `WargCodec` (12-byte big-endian header: length = body+8, requestId, flags; zlib bodies with the 2-byte header skipped; output-size cap), `ClientConnection` (read loop on a virtual thread, serialized writes, token-bucket rate limit), `GameServer` (TLS accept loop), `ProtoEnvelope` (protobuf wrapper: name = field 1, tag 100 = field 2, payload at field 100).
- `packet/`: `PacketRouter` dispatches the inbound `{"name","value"}` JSON envelope to `PacketHandler` groups. Add a group by implementing `PacketHandler` and listing it in `Main`.
- `http/HttpApp`: patch/messaging manifests and static assets (`com.sun.net.httpserver`).
- `tls/TlsContexts`: builds the game-socket `SSLContext`; generates a keystore with `keytool` if missing.

## Gotchas
- Outbound JSON must be the JsonFx envelope `{"name": Type, "value": {...}}`, with `"value": {}` for empty messages. Send concrete types, never bare ints where the client expects an object.
- The client dials the game port itself (39389); it cannot be proxied. The cert name must match the dialled host.
- Handshake endpoint: private/loopback clients are sent back to the address they dialled, others to `PTCGO_PUBLIC_HOST`.
- The Python `PacketRouter` also treats an unwrapped JSON with `queueName`+`gameID`+`path` as `ReadyForMatchConfirmation`; not ported yet.
