package com.ptcgo.revival.packet;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.Config;
import com.ptcgo.revival.Services;
import com.ptcgo.revival.catalog.Catalog;
import com.ptcgo.revival.net.GameServer;
import com.ptcgo.revival.net.WargCodec;
import com.ptcgo.revival.net.WargFlags;
import java.io.DataInputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

class StaticDataHandlerTest {
    private record Reply(int flags, byte[] body) {
        JsonNode json() {
            return WargCodec.parseJson(body);
        }
    }

    private static Reply ask(Socket s, DataInputStream in, String name, Object value, int rid) throws Exception {
        byte[] json = WargCodec.JSON.writeValueAsBytes(Map.of("name", name, "value", value));
        s.getOutputStream().write(WargCodec.frame(json, rid, 0));
        s.getOutputStream().flush();
        byte[] header = new byte[WargCodec.HEADER_SIZE];
        in.readFully(header);
        int[] h = WargCodec.decodeHeader(header);
        byte[] body = new byte[h[0]];
        in.readFully(body);
        assertEquals(rid, h[1]);
        return new Reply(h[2], WargCodec.inflateIfNeeded(body, h[2], 64 << 20));
    }

    private static void run(Catalog catalog, java.util.function.Consumer<Object[]> checks) throws Exception {
        Services base = TestServices.create(new Config());
        Services services = new Services(base.config(), base.accounts(), base.players(), base.tickets(), base.registry(),
                catalog, base.deckValidator(), base.progression(), null);
        PacketRouter router = new PacketRouter(List.of(new StaticDataHandler()));
        try (GameServer server = new GameServer(services, router, null);
             Socket s = new Socket("127.0.0.1", server.start(0))) {
            s.setSoTimeout(30000);
            checks.accept(new Object[] {s, new DataInputStream(s.getInputStream())});
        }
    }

    @Test
    void servesExportedPayloads(@TempDir Path dir) throws Exception {
        Path p = Files.createDirectories(dir.resolve("payloads"));
        Files.writeString(p.resolve("archetype_keys.json"), "[\"BW1\",\"SWSH9\"]");
        Files.writeString(p.resolve("sets.json"), "{\"checksum\":\"abc\",\"setDataList\":[{\"name\":\"BW1\"}]}");
        Files.writeString(p.resolve("family_map.json"), "{\"0\":\"{}\"}");
        Files.writeString(p.resolve("localizations.json"), "[{\"key\":\"k\",\"value\":\"v\"}]");
        Files.writeString(p.resolve("localizations_meta.json"), "{\"md5\":\"spirit_hash_1\",\"version\":\"spirit_v1\"}");
        Files.writeString(p.resolve("misc.json"), "{\"motd\":{\"messageName\":\"Motd\",\"id\":1,\"text\":{\"token\":\"hi\"}}}");
        Files.writeString(p.resolve("avatar_archetypes.json"), """
                {"archetypes":[{"guid":"00000000-0000-0000-0000-000000000a00","attributes":{"10140":{"type":"json","value":"{}"}}}]}""");
        run(Catalog.load(dir), a -> {
            try {
                Socket s = (Socket) a[0];
                DataInputStream in = (DataInputStream) a[1];

                JsonNode keys = ask(s, in, "GetArchetypeListKeys", Map.of(), 1).json();
                assertEquals("ArchetypeKeys", keys.get("name").asText());
                assertEquals("SWSH9", keys.get("value").get("keys").get(1).asText());

                JsonNode sets = ask(s, in, "GetSetData", Map.of(), 2).json();
                assertEquals("ChecksumDiff", sets.get("name").asText());
                JsonNode info = sets.get("value").get("ckSumDiff").get(0);
                assertEquals("ChecksumDiffInfo", info.get("name").asText());
                assertEquals("abc", info.get("value").get("ckSum").asText());
                assertEquals("SetDataList", info.get("value").get("newData").get("name").asText());
                assertEquals("BW1", info.get("value").get("newData").get("value").get("setDataList").get(0).get("name").asText());

                JsonNode loc = ask(s, in, "GetAllLocalizationReleases", Map.of("locale", "en_US", "keyedChecksums", Map.of()), 3).json();
                assertEquals("v", loc.get("value").get("releases").get("core").get("localizationList").get(0).get("value").asText());
                JsonNode cached = ask(s, in, "GetAllLocalizationReleases",
                        Map.of("locale", "en_US", "keyedChecksums", Map.of("core", "spirit_hash_1")), 4).json();
                assertEquals(0, cached.get("value").get("releases").size(), "client already has this md5");

                JsonNode motd = ask(s, in, "GetMotd", Map.of(), 5).json();
                assertEquals("Motd", motd.get("name").asText());
                assertEquals("hi", motd.get("value").get("text").get("token").asText());

                JsonNode locked = ask(s, in, "GetTimeLockedArchetypes", Map.of(), 6).json();
                assertTrue(locked.get("value").get("currentServerTime").asLong() > 1_700_000_000L);

                Reply av = ask(s, in, "GetProtobufAllAvatarArchetypesList", Map.of(), 7);
                assertEquals(WargFlags.PROTOBUF, av.flags());
                assertTrue(new String(av.body(), StandardCharsets.ISO_8859_1).contains("dwd.Protobuf.cake.item.AllAvatarArchetypesFound"));

                // Missing payloads degrade to empty answers rather than leaving the client waiting.
                JsonNode fam = ask(s, in, "GetArchetypeIDsByFamily", Map.of(), 8).json();
                assertEquals(0, fam.get("value").get("familyMap").size());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** Opt-in against the real export: mvn test -Dcatalog.dir=/home/user/catalog-export */
    @Test
    @EnabledIfSystemProperty(named = "catalog.dir", matches = ".+")
    void fullExportCounts() throws Exception {
        run(Catalog.load(Path.of(System.getProperty("catalog.dir"))), a -> {
            try {
                Socket s = (Socket) a[0];
                DataInputStream in = (DataInputStream) a[1];
                assertEquals(205, ask(s, in, "GetArchetypeListKeys", Map.of(), 1).json().get("value").get("keys").size());
                assertEquals(20485, ask(s, in, "GetFormatLegalityForArchetypes", Map.of(), 2).json().get("value").get("archLegality").size());
                JsonNode loc = ask(s, in, "GetAllLocalizationReleases", Map.of("locale", "en_US"), 3).json();
                assertEquals(48428, loc.get("value").get("releases").get("core").get("localizationList").size());
                assertEquals(197, ask(s, in, "GetSetData", Map.of(), 4).json().get("value").get("ckSumDiff").get(0)
                        .get("value").get("newData").get("value").get("setDataList").size());
                assertTrue(ask(s, in, "GetPokemonFamilyMap", Map.of(), 5).json().get("value").get("pokemonFamilyMap").has("0"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
