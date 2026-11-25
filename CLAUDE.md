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
- `Services` (config, accounts, CAS tickets, client registry) is passed to every connection and handler. `packet/AuthHandler` does login and the post-login message burst; `db/` is plain JDBC over the Python server's SQLite file (`Database.migrate` only creates missing tables); `auth/CasTickets` is the in-memory single-use ticket store shared with the HTTP login page.
- `packet/DataSyncHandler` + `db/PlayerRepository`: wallet, collection, flags, decks, account settings. `catalog/Catalog` loads the Python exporter's JSON (`wire_attributes` is what the client is sent). `deck/RulesDeckValidator` + `FormatManager` + `ThemeDecks` implement the deck rules (`DeckValidator.PERMISSIVE` remains for tests); `DeckValidationRow.toClientRows` filters the server-only `TrainerChallenge` row from anything sent to the client, and the deck list strips `TrainerChallenge` from attribute 10860.
- `packet/CatalogHandler` serves `GetProtobufArchetypesList` via `catalog/ArchetypeEncoder` (hand-written proto3 writer in `ProtoWriter`; no protoc). `packet/StaticDataHandler` serves the player-independent payloads from `<catalog>/payloads/*.json` (`StaticPayloads`). Protobuf frames are `ProtoEnvelope.wrap(fullMessageName, bytes)` sent with `ClientContext.sendRaw` and flags `PROTOBUF|COMPRESSED` (the avatar list is `PROTOBUF` only).
- `progression/` (`Quests`, `Seasons`, `Progression`, `Uuid5`) + `db/QuestRepository`/`VersusRepository`: daily challenges and the versus ladder; `packet/AccountAttributes` builds the full account attribute list (settings, screen name, modes, season points, quest state) used by login, `AccountUpdated` and quest toggles; `packet/QuestHandler` serves the quest messages. `Database` runs manual transactions in SQLite IMMEDIATE mode (Python used `BEGIN IMMEDIATE`).
- `social/` (`SocialModule`, `PresenceBroadcaster`, `ChatRooms`, plus seams `FriendMatchStarter` and `GameChatRouter`) with `packet/{Friends,Chat,Challenge,Trade}Handler` and `db/{Social,Trade}Repository`. `SocialModule.onLogin` is called from `AuthHandler.success` and `SocialModule.onDisconnect` from `ClientConnection.close` (the latter on a virtual thread: `close()` can run while holding the write lock). Friend matches and in-game chat are no-ops until the match engine implements the two seams.
- `shop/` (`ShopModule`, `ShopManager`, `ShopRepository`, `CodeRepository`, `PackOpener`, `StarterContent`, rule files under `src/main/resources/shop/`) with `packet/ShopHandler` and `RedemptionHandler`. `Services.createAccount` is the only way accounts should be created from handlers/HTTP: it grants starter content (idempotent via a `product_claims` marker). `db/DailyLoginRepository` + `progression/DailyRewards` implement the daily login track and feed the `DailyLogin` message.
- `packet/ContentHandler`: landing pages (`db/DynamicPageRepository`, filtered by start/end for landing pages only), friend-invite notifications, achievements, league scenarios (`catalog/ScenarioEncoder`; its JSON strings use Python's `json.dumps` spacing on purpose).
- `tls/TlsContexts`: builds the game-socket `SSLContext`; generates a keystore with `keytool` if missing.

## Gotchas
- The login burst is now 12 messages (`DailyLogin` was added); a brand-new account's first login also pays the 100-token newbie reward, so it starts at 1100 tokens. `ShopManager.reload()` must be called after shop rows change (nothing triggers it automatically).
- Deliberate differences from Python in social: accepting a friend invitation requires an invitation/friendship row from the other side (Python lets anyone force a friendship); re-inviting a friend doesn't downgrade them; `FriendAdded` carries real presence; trade history is filtered in SQL. Python has no block list and enforces no friend/chat/trade mode attributes, so neither does this port. Trade is a lots model (a private lot is the offer).
- Test helpers read the login burst by count, so adding a login message means updating `SocialTestSupport`, `AuthIntegrationTest`, `DataSyncIntegrationTest`, `QuestHandlerTest` and `DeckRulesIntegrationTest`.
- Login creates the wallet (`players().wallet(id)`), and reward helpers create a missing wallet with the normal defaults (1000/0/100), never with 0.
- Quest ids are `uuid5(NAMESPACE_URL, "spirit:daily:<account>:<date>:<key>")` and the daily offer order sorts by SHA-256 bytes (unsigned), so they match Python exactly; `Uuid5Test` pins this.
- `completedQuestsAndXPTotal` entries must be a pair of quest definitions (the xp total rides in the second one); a bare int fails client deserialization.
- `Seasons.clientPayload` never sends startTime/endTime 0 (it pops the new-season dialog every login) and orders each tier's rewards cards first, products next, tokens last.
- Products requested by key are re-keyed: attributes 200580 (set cache key) and 10020 (expansion) are forced to the *requested* key, and `coins`/`cardSleeves`/`deckBoxes` select products by product type (5/27/11). Cards and products are sent at most once per connection (`ClientState`).
- `banned_cards_by_format` is exported empty (it mirrors an admin DB table); `GetTimeLockedArchetypes` adds `currentServerTime` per request.
- Encoder changes must stay byte-identical to the Python protobuf runtime: `ArchetypeEncoderTest` holds an oracle generated by it.
- Wallet amounts are clamped to int32 (2,000,000,000): larger values make the client fail to deserialize `CurrentWallet`.
- A deck naming a coin/sleeve/deck box the account doesn't own is rewritten to the default on save and on send, or the client can't resolve it.
- Basic energy (`Free_Energy` set) is reported as 99 tradable copies for everyone and is not stored.
- Passwords are unsalted SHA-256 hex on purpose: existing Python-created accounts must keep logging in.
- `AuthenticationSuccessful` carries the full account attribute set (the client replaces its whole set on update); season/quest attributes are neutral defaults until those subsystems are ported.
- Outbound JSON must be the JsonFx envelope `{"name": Type, "value": {...}}`, with `"value": {}` for empty messages. Send concrete types, never bare ints where the client expects an object.
- The client dials the game port itself (39389); it cannot be proxied. The cert name must match the dialled host.
- Handshake endpoint: private/loopback clients are sent back to the address they dialled, others to `PTCGO_PUBLIC_HOST`.
- The Python `PacketRouter` also treats an unwrapped JSON with `queueName`+`gameID`+`path` as `ReadyForMatchConfirmation`; not ported yet.
