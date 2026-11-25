package com.ptcgo.revival.net;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The {@code ProtoMessage} wrapper used for protobuf frames: messageName (field 1), messageTag
 * (field 2, always 100), then the payload appended as a length-delimited field numbered 100.
 */
public final class ProtoEnvelope {
    public static final int MESSAGE_TAG = 100;

    private ProtoEnvelope() {}

    public static byte[] wrap(String fullName, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + fullName.length() + 16);
        byte[] name = fullName.getBytes(StandardCharsets.UTF_8);
        writeVarint(out, (1 << 3) | 2);
        writeVarint(out, name.length);
        out.writeBytes(name);
        writeVarint(out, (2 << 3));
        writeVarint(out, MESSAGE_TAG);
        writeVarint(out, ((long) MESSAGE_TAG << 3) | 2);
        writeVarint(out, payload.length);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    public static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7fL) != 0) {
            out.write((int) ((value & 0x7f) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    public static int zigzagEncode(int n) {
        return (n << 1) ^ (n >> 31);
    }

    public static int zigzagDecode(int n) {
        return (n >>> 1) ^ -(n & 1);
    }
}
