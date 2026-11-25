# PTCGO Revival (Java)

Java 21 port of the Python PTCGO revival server ([ptcgo-revival](https://github.com/KABAMJ0/ptcgo-revival)),
which emulates the discontinued Pokémon TCG Online server for an existing client.

## Status: stage 8 (login, player data, catalogue, deck rules, quests, ladder, social, trade, shop, packs)

| Area | State |
| --- | --- |
| Warg framing (header, flags, zlib, zlib-bomb cap, JSON envelope, protobuf envelope) | done, unit-tested |
| TLS game socket (39389), virtual thread per client, idle timeouts, inbound rate limit | done |
| Handshake: `RequestConnectionServiceWithVersion`, `RequestSession`, `RequestLogin`, `Ping` | done, integration-tested |
| HTTP (8000): `/health`, `manifest.json`, `messaging/manifest.json`, static assets | done |
| Login: GAS token (auto-register), CAS ticket + `/cas/sso/login` page, guest/mobile (opt-in), ban check, concurrent-login guard, post-login message sequence | done, integration-tested (reduced: see below) |
| SQLite `accounts` table, same schema/file and SHA-256 password hash as the Python server | done |
| Versus seasons + ladder points (tier rewards granted once), season/quest account attributes, login anchoring of the ladder animation | done, tested |
| Daily challenges: offers (2 per UTC day), select, abandon, enable/disable, exactly-once match credit with xp and coin rewards | done, tested (`creditMatch` waits for the match engine to call it) |
| Friends (invite/accept/decline/remove, roster), presence on login/disconnect, chat (private, lobby, game-chat seam), friend challenges (accepting fails cleanly until the match engine exists) | done, two-socket tests |
| Trade (private lots between players, search, history, atomic accept in one transaction), archetype for-trade/review flags | done, tested incl. concurrent accepts |
| Shop (listing, featured/top-selling, purchases, open-by-archetype), booster pack opening (rarity slots, pack rules/merges, injectable RNG), code redemption, starter decks/packs for new accounts | done, tested incl. 1000 real SWSH9 packs (opt-in) |
| Daily login rewards (streak, newbie/standard tracks, once per UTC day) and the `DailyLogin` message | done, tested |
| Reconnect-to-game | needs the match engine (versus branch in progress) |
| Wallet, collection, archetype flags, decks (list/save/delete), account settings; same tables as the Python DB | done, tested |
| Card/product catalogue loader (JSON exported from the Python server; 20,483 cards load in ~1 s) | done |
| Catalogue download: `GetProtobufArchetypesList` (protobuf `ArchetypesFound`, per-connection dedup, product key rule); encoder verified byte-for-byte against the Python protobuf runtime and against a whole real set | done |
| Static data: archetype keys, set data, family maps, format legality, localizations (48k strings), MOTD, feature flags, dynamic versions, time-locked archetypes, avatar items | done (served from exported payloads) |
| Deck validation: 60 cards, copy limits, ACE SPEC/Radiant/Prism Star, owned cards, per-format set legality, bans, theme decks; `DecksValidated` rows; valid-format attribute on save and deck list | done, tested (server-wide ban list not ported: nothing is banned) |
| Landing pages / maintenance banners (`dynamic_pages` table), friend-invite notifications, achievements (empty), league scenario list (protobuf, byte-identical to Python) | done, tested |
| Banned cards (served empty), tournaments, league play, tutorial, admin panel | not ported |
| **Asset bundles**: `manifest.version`, `*.manifest` and `*.unity3d` served from `PTCGO_BUNDLE_DIR` (default `bundles`). The bundles are produced by the Python `tools/export_bundles.py` (being written in the Python repo); only files listed in the directory's `manifest.json` are served, anything else is an empty 404 | served from the export; the Java side does not build bundles, so the client needs the export run first |
| MariaDB/MySQL support | not ported |
| Protobuf messages (`.proto` sources live in the Python repo) | not ported |
| Bundle manifest builder (`*.manifest`, `manifest.version`) | not ported |
| Match engine, card effects (~20k scripts), AI, admin panel | not ported |

## Build and run

```sh
mvn test
mvn package
java -jar target/ptcgo-revival-0.1.0-SNAPSHOT.jar
java -jar target/ptcgo-revival-0.1.0-SNAPSHOT.jar --create-admin <name> <password>
```

The first start creates a self-signed keystore at `certs/server.p12` for `PTCGO_PUBLIC_HOST`.
The client checks the certificate name against the host it dials, so regenerate it after a hostname change:

```sh
java -jar target/ptcgo-revival-0.1.0-SNAPSHOT.jar --gen-cert play.example.com
```

## Card catalogue

Card definitions are Python scripts, so the catalogue is exported once from the Python repo and read here:

```sh
# in the ptcgo-revival (Python) checkout
PYTHONPATH=. ./venv/bin/python tools/export_catalog.py --out ../catalog-export
# then
PTCGO_CATALOG_DIR=../catalog-export java -jar target/ptcgo-revival-0.1.0-SNAPSHOT.jar
```

The export (~100 MB, `cards/`, `products.json` and `payloads/`) is not committed. Card *effects* are code and are not part of it; they belong to the match-engine stage.
`mvn test -Dcatalog.dir=<export>` also runs a load check against the full export.

## Configuration

Same variables as the Python server: `PTCGO_PUBLIC_HOST`, `PTCGO_HTTP_PORT` (8000), `PTCGO_TCP_PORT` (39389),
`PTCGO_HTTP_BASE_URL`, `PTCGO_CLIENT_VERSION`, `PTCGO_IDLE_TIMEOUT`, `PTCGO_PREAUTH_TIMEOUT`,
`PTCGO_MAX_DECOMPRESSED`, `PTCGO_INBOUND_RATE`, `PTCGO_INBOUND_BURST`. New: `PTCGO_ASSET_DIR` (default `assets`),
`PTCGO_KEYSTORE`, `PTCGO_KEYSTORE_PASSWORD`, `PTCGO_DB_PATH` (default `ptcgo_server.db`), `PTCGO_ALLOW_GUESTS=1`, `PTCGO_CATALOG_DIR` (default `catalog`).

The client always dials 39389 directly: the game hostname must be DNS-only (not proxied) if it sits behind Cloudflare.
