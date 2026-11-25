package com.ptcgo.revival.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.net.WargCodec;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks Java-emitted messages against the key sets and value types the Python server emitted (recorded by running
 * its GameSession headless: src/test/resources/python-wire-shapes.json). Types: s string, i int, f float, b bool,
 * n null, a array, o object.
 */
public final class PythonShapeOracle {
    private final JsonNode shapes;
    public final Set<String> unknownMessages = new HashSet<>();
    public final Map<String, Integer> checked = new HashMap<>();

    public PythonShapeOracle() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/python-wire-shapes.json")) {
            shapes = WargCodec.JSON.readTree(in);
        }
    }

    public Set<String> sequenceNames() {
        Set<String> s = new HashSet<>();
        shapes.get("_sequences").forEach(n -> s.add(n.asText()));
        return s;
    }

    static String type(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) return "n";
        if (v.isBoolean()) return "b";
        if (v.isIntegralNumber()) return "i";
        if (v.isNumber()) return "f";
        if (v.isTextual()) return "s";
        if (v.isArray()) return "a";
        return "o";
    }

    /** Problems with one message value against its recorded shape (empty = conforms). */
    public List<String> check(String name, JsonNode value) {
        List<String> problems = new ArrayList<>();
        JsonNode shape = shapes.get(name);
        if (shape == null) {
            unknownMessages.add(name);
            return problems;
        }
        checked.merge(name, 1, Integer::sum);
        Map<String, Set<String>> allowed = new HashMap<>();
        List<String> required = new ArrayList<>();
        List<String> order = new ArrayList<>();
        for (JsonNode k : shape.get("keys")) {
            String key = k.get("k").asText();
            order.add(key);
            Set<String> types = new HashSet<>();
            k.get("types").forEach(t -> types.add(t.asText()));
            allowed.put(key, types);
            if (k.get("required").asBoolean()) required.add(key);
        }
        List<String> keys = new ArrayList<>();
        value.fieldNames().forEachRemaining(keys::add);
        for (String k : keys) {
            if (!allowed.containsKey(k)) {
                problems.add(name + ": unexpected key '" + k + "'");
                continue;
            }
            String t = type(value.get(k));
            Set<String> ok = allowed.get(k);
            // int vs float: JSON numbers are interchangeable to the client's coercion
            if (!ok.contains(t) && !(t.equals("i") && ok.contains("f")) && !(t.equals("f") && ok.contains("i"))) {
                problems.add(name + "." + k + ": type " + t + " not in " + ok);
            }
        }
        for (String r : required) if (!value.has(r)) problems.add(name + ": missing required key '" + r + "'");
        // key order as emitted by Python (insertion order of the recorded key set)
        int last = -1;
        for (String k : keys) {
            int idx = order.indexOf(k);
            if (idx >= 0 && idx < last) {
                problems.add(name + ": key '" + k + "' out of order");
                break;
            }
            if (idx >= 0) last = idx;
        }
        return problems;
    }

    /** Checks a SequenceMessage and its nested message; collects the bracket names. */
    public List<String> checkSequenceMessage(JsonNode value, Set<String> bracketNames) {
        List<String> problems = new ArrayList<>(check("SequenceMessage", value));
        JsonNode msg = value.get("msg");
        String inner = msg.get("name").asText();
        if (inner.equals("StartSequence")) bracketNames.add(msg.get("value").get("name").asText());
        problems.addAll(check(inner, msg.get("value")));
        return problems;
    }
}
