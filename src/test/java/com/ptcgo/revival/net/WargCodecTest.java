package com.ptcgo.revival.net;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WargCodecTest {
    @Test
    void headerRoundTrip() throws IOException {
        byte[] h = WargCodec.encodeHeader(5, 42, WargFlags.GRANTED_SESSION);
        assertEquals(12, h.length);
        assertEquals(13, ByteBuffer.wrap(h).getInt()); // length = body + 8
        int[] d = WargCodec.decodeHeader(h);
        assertArrayEquals(new int[] {5, 42, 128}, d);
    }

    @Test
    void rejectsTlsClientHello() {
        byte[] hello = new byte[12];
        hello[0] = 0x16;
        hello[1] = 0x03;
        assertThrows(IOException.class, () -> WargCodec.decodeHeader(hello));
    }

    @Test
    void rejectsOversizedBody() {
        byte[] h = ByteBuffer.allocate(12).putInt(WargCodec.MAX_BODY + 9).putInt(1).putInt(0).array();
        assertThrows(IOException.class, () -> WargCodec.decodeHeader(h));
    }

    @Test
    void compressionRoundTripAndCap() throws IOException {
        byte[] data = "hello hello hello hello".repeat(100).getBytes();
        byte[] z = WargCodec.deflate(data);
        assertArrayEquals(data, WargCodec.inflate(z, 1 << 20));
        assertThrows(IOException.class, () -> WargCodec.inflate(z, 100)); // zlib-bomb guard
    }

    @Test
    void envelopeAlwaysCarriesValueObject() throws IOException {
        String empty = new String(WargCodec.encodeMessage("Pong", Map.of()));
        assertEquals("{\"name\":\"Pong\",\"value\":{}}", empty);
        var tree = WargCodec.JSON.readTree(WargCodec.encodeMessage("X", Map.of("a", 1)));
        assertEquals("X", tree.get("name").asText());
        assertEquals(1, tree.get("value").get("a").asInt());
    }

    @Test
    void frameMatchesHeaderLength() {
        byte[] f = WargCodec.frameMessage("Pong", Map.of(), 7, WargFlags.PING_PONG);
        ByteBuffer b = ByteBuffer.wrap(f);
        assertEquals(f.length - 4, b.getInt());
        assertEquals(7, b.getInt());
        assertEquals(WargFlags.PING_PONG, b.getInt());
    }

    @Test
    void protoEnvelopeLayout() {
        byte[] payload = {1, 2, 3};
        byte[] w = ProtoEnvelope.wrap("a.B", payload);
        // field1 "a.B", field2 = 100, field 100 (tag 802 = 0xa2 0x06) len 3
        assertArrayEquals(new byte[] {0x0a, 3, 'a', '.', 'B', 0x10, 100, (byte) 0xa2, 0x06, 3, 1, 2, 3}, w);
    }

    @Test
    void zigzag() {
        for (int n : new int[] {0, -1, 1, -2, 12345, -12345}) {
            assertEquals(n, ProtoEnvelope.zigzagDecode(ProtoEnvelope.zigzagEncode(n)));
        }
    }
}
