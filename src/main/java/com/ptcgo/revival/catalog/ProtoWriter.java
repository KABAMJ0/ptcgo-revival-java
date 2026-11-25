package com.ptcgo.revival.catalog;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Minimal proto3 writer for the handful of messages the client uses (dwd.Protobuf.*). proto3 omits
 * zero/empty scalars, and so does this, so output is byte-identical to the Python protobuf runtime.
 */
final class ProtoWriter {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    byte[] toByteArray() {
        return out.toByteArray();
    }

    private void varint(long v) {
        while ((v & ~0x7fL) != 0) {
            out.write((int) ((v & 0x7f) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }

    private void tag(int field, int wire) {
        varint(((long) field << 3) | wire);
    }

    /** int32/enum: negatives are sign-extended to 10 bytes. */
    ProtoWriter int32(int field, int v) {
        if (v == 0) return this;
        tag(field, 0);
        varint(v);
        return this;
    }

    ProtoWriter sint32(int field, int v) {
        int z = (v << 1) ^ (v >> 31);
        if (z == 0) return this;
        tag(field, 0);
        varint(z & 0xffffffffL);
        return this;
    }

    ProtoWriter bool(int field, boolean v) {
        if (!v) return this;
        tag(field, 0);
        varint(1);
        return this;
    }

    ProtoWriter fixed64(int field, long v) {
        if (v == 0) return this;
        tag(field, 1);
        out.writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array());
        return this;
    }

    ProtoWriter float32(int field, float v) {
        if (Float.floatToRawIntBits(v) == 0) return this;
        tag(field, 5);
        out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array());
        return this;
    }

    ProtoWriter string(int field, String s) {
        if (s == null || s.isEmpty()) return this;
        return bytes(field, s.getBytes(StandardCharsets.UTF_8));
    }

    /** Length-delimited field; written even when empty (used for sub-messages). */
    ProtoWriter bytes(int field, byte[] b) {
        tag(field, 2);
        varint(b.length);
        out.writeBytes(b);
        return this;
    }

    /** Appends already-encoded repeated entries verbatim (concatenation is valid protobuf). */
    ProtoWriter raw(byte[] b) {
        out.writeBytes(b);
        return this;
    }
}
