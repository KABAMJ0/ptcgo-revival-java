package com.ptcgo.revival.net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Warg wire format. Frame = length(4) requestId(4) flags(4), all big-endian, where
 * length = bodyLength + 8. Compressed bodies are zlib streams; the client skips the 2-byte
 * zlib header and inflates the raw deflate data.
 */
public final class WargCodec {
    public static final int HEADER_SIZE = 12;
    public static final int MAX_BODY = 10_000_000;
    public static final ObjectMapper JSON = new ObjectMapper();

    private WargCodec() {}

    /** Returns {bodyLength, requestId, flags}. */
    public static int[] decodeHeader(byte[] header) throws IOException {
        if (header.length < HEADER_SIZE) throw new IOException("Short header");
        if ((header[0] & 0xff) == 0x16 && (header[1] & 0xff) == 0x03) {
            throw new IOException("Plain TCP received a TLS ClientHello? (or the reverse): check the socket mode");
        }
        ByteBuffer b = ByteBuffer.wrap(header);
        long length = b.getInt() & 0xffffffffL;
        int requestId = b.getInt();
        int flags = b.getInt();
        long bodyLength = length - 8;
        if (bodyLength < 0 || bodyLength > MAX_BODY) throw new IOException("Invalid body length " + bodyLength);
        return new int[] {(int) bodyLength, requestId, flags};
    }

    public static byte[] encodeHeader(int bodyLength, int requestId, int flags) {
        return ByteBuffer.allocate(HEADER_SIZE).putInt(bodyLength + 8).putInt(requestId).putInt(flags).array();
    }

    /** Inflates a compressed body, refusing anything that expands beyond {@code limit} bytes. */
    public static byte[] inflate(byte[] body, int limit) throws IOException {
        if (body.length < 2) throw new IOException("Compressed body too short");
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(body, 2, body.length - 2);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, body.length * 2));
            byte[] buf = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                out.write(buf, 0, n);
                if (out.size() > limit) throw new IOException("Decompressed body exceeds " + limit + "-byte cap");
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new IOException("Bad compressed body", e);
        } finally {
            inflater.end();
        }
    }

    public static byte[] deflate(byte[] body) {
        Deflater deflater = new Deflater(6); // zlib format, matches Python zlib.compress(body, 6)
        try {
            deflater.setInput(body);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(body.length / 2 + 32);
            byte[] buf = new byte[8192];
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf));
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /** Decodes an inbound body: inflates if flagged, returns the bytes (protobuf) or JSON as a tree. */
    public static byte[] inflateIfNeeded(byte[] body, int flags, int limit) throws IOException {
        return WargFlags.has(flags, WargFlags.COMPRESSED) ? inflate(body, limit) : body;
    }

    public static JsonNode parseJson(byte[] body) {
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Builds the JsonFx polymorphic envelope the client expects: {"name": Type, "value": {...}}.
     * An empty payload still sends "value": {}.
     */
    public static byte[] encodeMessage(String messageName, Map<String, ?> payload) {
        ObjectNode root = JSON.createObjectNode();
        root.put("name", messageName);
        root.set("value", payload == null || payload.isEmpty() ? JSON.createObjectNode() : JSON.valueToTree(payload));
        try {
            return JSON.writeValueAsBytes(root);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A complete frame (header + body) ready to write. */
    public static byte[] frame(byte[] body, int requestId, int flags) {
        byte[] wire = WargFlags.has(flags, WargFlags.COMPRESSED) ? deflate(body) : body;
        byte[] header = encodeHeader(wire.length, requestId, flags);
        byte[] out = new byte[header.length + wire.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(wire, 0, out, header.length, wire.length);
        return out;
    }

    public static byte[] frameMessage(String messageName, Map<String, ?> payload, int requestId, int flags) {
        return frame(encodeMessage(messageName, payload), requestId, flags);
    }

    static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
