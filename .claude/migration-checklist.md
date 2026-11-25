# Python → Java migration checklist

Status key: `done` = ported and tested · `partial` = some of it ported · `todo` = not started · `blocked` = needs something else first.
Update this file when a subsystem changes status. Mark `done` only after `mvn -q -B test` passes for it.

| Subsystem | Python source | Status | Java location | Notes |
|---|---|---|---|---|
| Warg framing, TLS game socket | `ptcgo/network/` | done | `net/` | |
| Handshake, CAS login, accounts | `ptcgo/packets/handlers/{handshake,auth}.py` | done | `packet/{Handshake,Auth}Handler` | 12-message login burst |
| Wallet, collection, flags, decks, settings | `data_sync.py`, `player_data.py` | done | `packet/DataSyncHandler`, `db/PlayerRepository` | |
| Catalogue and static payloads | `data_sync.py` | done | `catalog/`, `packet/{Catalog,StaticData}Handler` | byte-identical protobuf oracle |
| Deck validation and formats | `ptcgo/game/decks/` | done | `deck/` | |
| Quests, ladder, seasons, daily login | `progression/`, `versus_data.py`, `quests.py` | done | `progression/`, `db/{Quest,Versus,DailyLogin}Repository` | |
| Friends, presence, chat, trade | `handlers/{social,trade}.py` | done | `social/`, `packet/{Friends,Chat,Challenge,Trade}Handler` | friend matches via seam |
| Shop, packs, redemption, starter content | `handlers/{shop,redemption}.py`, `economy_data.py` | done | `shop/`, `packet/{Shop,Redemption}Handler` | |
| Landing pages, notifications, league scenarios | `data_sync.py` | done | `packet/ContentHandler`, `catalog/ScenarioEncoder` | |
| Tournaments (async and live brackets) | `handlers/tournaments.py`, `game/tournaments/` | partial | `tournament/`, `packet/TournamentHandler` | real matches need the match engine (seam) |
| Match engine, matchmaking, bot AI | `game/session/`, `handlers/{matchmaking,gameplay}.py` | partial | `game/match/`, `packet/VersusHooks` | core, AI, queue, friend and bot matches done; effects not yet |
| Card effects | `game/card_effects/`, `game/scripts/cards/` | todo | `game/match/EffectRegistry` (seam) | ~20k card scripts; engine seam ready, no effects ported |
| V-UNION, LEGEND, bench attacks | `game/session/vunion.py`, `legends.py` | todo | — | not in the match core yet |
| Tournament matches | `game/tournaments/live.py` | partial | `tournament/` | runner seam still `UNAVAILABLE`; needs wiring to `VersusModule` |
| Asset bundles (`manifest.version`, `*.manifest`, `*.unity3d`) | `server/http_server.py`, `server/manifest_manager.py` | todo | — | needs UnityPy equivalent or a pre-export step |
| Tutorial, league play | `handlers/{tutorial,league}.py` | todo | — | league needs the engine |
| Admin panel and HTTP admin API | `server/admin*.py` | todo | — | |
| MariaDB/MySQL support | `database/connection.py` | todo | — | SQLite only so far |
