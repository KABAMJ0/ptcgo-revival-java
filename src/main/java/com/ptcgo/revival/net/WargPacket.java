package com.ptcgo.revival.net;

/** A decoded frame: request id, flags and the (already inflated) body bytes. */
public record WargPacket(int requestId, int flags, byte[] body) {}
