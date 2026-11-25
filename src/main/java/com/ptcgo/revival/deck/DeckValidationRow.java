package com.ptcgo.revival.deck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * One DeckValidationResult row: a deck checked against one format. {@code failures} is empty exactly when the deck
 * is valid. {@link #toJson()} is the wire shape ({deckID, format, formatName, valid, results}).
 */
public record DeckValidationRow(String deckId, String format, String formatName, boolean valid, List<Failure> failures) {

    /**
     * One broken rule. {@code failureType} coerces by the client's enum member name; {@code explanation} renders
     * verbatim for unknown localisation keys.
     */
    public record Failure(String failureType, String explanation, List<String> offendingArchetypeIds) {
        ObjectNode toJson() {
            ObjectNode n = JsonNodeFactory.instance.objectNode();
            n.putObject("explanation").put("id", explanation);
            n.put("failureType", failureType);
            ArrayNode offenders = n.putArray("offendingArchetypeIDs");
            offendingArchetypeIds.forEach(offenders::add);
            n.putNull("additionalData");
            return n;
        }
    }

    public ObjectNode toJson() {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        if (deckId == null) n.putNull("deckID"); else n.put("deckID", deckId);
        n.put("format", format);
        n.put("formatName", formatName);
        n.put("valid", valid);
        ArrayNode results = n.putArray("results");
        failures.forEach(f -> results.add(f.toJson()));
        return n;
    }

    /**
     * The rows as the client wants them: TrainerChallenge is ours, and a format name the client's {@code DeckFormat}
     * switch does not know leaves every deck in no category, so it is dropped.
     */
    public static ArrayNode toClientRows(List<DeckValidationRow> rows) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        for (DeckValidationRow r : rows) {
            if (!"TrainerChallenge".equals(r.formatName())) out.add((JsonNode) r.toJson());
        }
        return out;
    }
}
