package com.ptcgo.revival.tournament;

import com.fasterxml.jackson.databind.JsonNode;

/** Python-truthiness helpers for reading the admin-shaped (loosely typed) tournament definitions. */
final class Json {
    private Json() {}

    static boolean truthy(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return false;
        if (n.isBoolean()) return n.asBoolean();
        if (n.isNumber()) return n.asDouble() != 0;
        if (n.isTextual()) return !n.asText().isEmpty();
        return n.size() > 0; // container
    }

    /** {@code int(d.get(key) or dflt)}; unparsable text reads as dflt. */
    static long intOr(JsonNode d, String key, long dflt) {
        JsonNode v = d == null ? null : d.get(key);
        if (!truthy(v)) return dflt;
        if (v.isNumber()) return v.asLong();
        if (v.isBoolean()) return 1;
        try {
            return Long.parseLong(v.asText().strip());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /** {@code str(d.get(key) or dflt)}. */
    static String strOr(JsonNode d, String key, String dflt) {
        JsonNode v = d == null ? null : d.get(key);
        return truthy(v) ? (v.isValueNode() ? v.asText() : v.toString()) : dflt;
    }
}
