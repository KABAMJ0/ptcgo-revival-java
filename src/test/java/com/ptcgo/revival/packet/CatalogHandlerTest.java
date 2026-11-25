package com.ptcgo.revival.packet;

import static org.junit.jupiter.api.Assertions.*;

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
import org.junit.jupiter.api.io.TempDir;

class CatalogHandlerTest {
    private record Frame(int flags, byte[] body) {}

    private static Frame request(Socket s, DataInputStream in, String key, int rid) throws Exception {
        byte[] json = WargCodec.JSON.writeValueAsBytes(Map.of("name", "GetProtobufArchetypesList", "value", Map.of("key", key)));
        s.getOutputStream().write(WargCodec.frame(json, rid, 0));
        s.getOutputStream().flush();
        byte[] header = new byte[WargCodec.HEADER_SIZE];
        in.readFully(header);
        int[] h = WargCodec.decodeHeader(header);
        byte[] body = new byte[h[0]];
        in.readFully(body);
        assertEquals(rid, h[1]);
        return new Frame(h[2], WargCodec.inflateIfNeeded(body, h[2], 1 << 20));
    }

    private static boolean contains(byte[] hay, String needle) {
        return new String(hay, StandardCharsets.ISO_8859_1).contains(new String(needle.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1));
    }

    @Test
    void servesCardsOncePerConnectionAndProductsByKeyRule(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("cards"));
        Files.writeString(dir.resolve("cards/SET1.json"), """
                [{"guid":"6b0342a4-a93c-5015-af5a-b9adb049a057","key":"SET1","display_name":"A","set_code":"SET1",
                  "collector_number":1,"kind":"pokemon","wire_attributes":{"200490":{"type":"int","value":50}}}]""");
        Files.writeString(dir.resolve("products.json"), """
                [{"guid":"001d0092-4db0-5b10-856a-1ade56eaad5c","key":"core","kind":"coins","product_type":5,"name":"Coin",
                  "attributes":{"200580":{"type":"string","value":"core"},"10540":{"type":"int","value":5}}},
                 {"guid":"11111111-2222-3333-4444-555555555555","key":"SET1","kind":"booster_pack","product_type":1,"name":"Pack",
                  "attributes":{"200580":{"type":"string","value":"SET1"}}}]""");
        Services base = TestServices.create(new Config());
        Services services = new Services(base.config(), base.accounts(), base.players(), base.tickets(), base.registry(),
                Catalog.load(dir), base.deckValidator(), base.progression());
        PacketRouter router = new PacketRouter(List.of(new CatalogHandler()));
        try (GameServer server = new GameServer(services, router, null);
             Socket s = new Socket("127.0.0.1", server.start(0))) {
            s.setSoTimeout(5000);
            DataInputStream in = new DataInputStream(s.getInputStream());

            Frame first = request(s, in, "SET1", 1);
            assertEquals(WargFlags.PROTOBUF | WargFlags.COMPRESSED, first.flags());
            assertTrue(contains(first.body(), "dwd.Protobuf.Collection.ArchetypesFound"), "ProtoMessage wrapper name");
            assertTrue(contains(first.body(), "SET1"));

            Frame again = request(s, in, "SET1", 2); // card already sent: only the checksum/key remain
            assertTrue(again.body().length < first.body().length);

            // The coin is core's, but a "coins" request pulls every COINS product, retargeted to the requested key.
            Frame coins = request(s, in, "coins", 3);
            assertTrue(contains(coins.body(), "coins"));
            assertFalse(contains(coins.body(), "core"));
            Frame coinsAgain = request(s, in, "coins", 4);
            assertTrue(coinsAgain.body().length < coins.body().length, "products dedup by guid");

            Frame unknown = request(s, in, "NoSuchKey", 5);
            assertTrue(contains(unknown.body(), "NoSuchKey"));
        }
    }
}
