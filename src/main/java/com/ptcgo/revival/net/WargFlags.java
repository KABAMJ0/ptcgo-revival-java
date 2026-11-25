package com.ptcgo.revival.net;

/** Bit flags in the Warg frame header. */
public final class WargFlags {
    public static final int CLEAR = 0;
    public static final int COMPRESSED = 1;
    public static final int PROTOBUF = 2;
    public static final int PING_PONG = 4;
    public static final int CONNECTION_ERROR = 16;
    public static final int ACKNOWLEDGE_REQUEST = 32;
    public static final int RECONNECT_SESSION = 64;
    public static final int GRANTED_SESSION = 128;

    private WargFlags() {}

    public static boolean has(int flags, int flag) {
        return (flags & flag) != 0;
    }
}
