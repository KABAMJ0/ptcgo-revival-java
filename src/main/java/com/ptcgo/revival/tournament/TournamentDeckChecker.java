package com.ptcgo.revival.tournament;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.deck.DeckValidationRow;
import com.ptcgo.revival.deck.DeckValidator;
import com.ptcgo.revival.deck.RulesDeckValidator;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deck legality for tournament entry. Python: {@code validate_tournament_deck} (resolve the tournament's format to a
 * format guid, validate the deck against just that format).
 */
public interface TournamentDeckChecker {
    /** {@code formatError} is set when the format cannot be resolved; otherwise {@code rows} has the one format row. */
    record Result(List<DeckValidationRow> rows, String formatError) {}

    Result check(JsonNode deck, Map<String, Integer> owned, String formatValue);

    /** First human-readable failure of the first row, or the generic text. */
    static String errorText(List<DeckValidationRow> rows) {
        if (rows != null && !rows.isEmpty() && !rows.get(0).failures().isEmpty()) {
            String id = rows.get(0).failures().get(0).explanation();
            if (id != null && !id.isEmpty()) return id;
        }
        return "This deck is not valid for this tournament.";
    }

    /**
     * The checker for the server's validator. {@link RulesDeckValidator} resolves real formats. Any other validator (the
     * permissive placeholder used until the catalogue is loaded) is asked for {@code validFormats} instead: the deck is
     * valid when that list contains the format (case-insensitive), so a placeholder validator only admits Unlimited.
     */
    static TournamentDeckChecker of(DeckValidator validator) {
        return (deck, owned, formatValue) -> {
            if (validator instanceof RulesDeckValidator rules) {
                String guid = rules.formats().resolveFormatGuid(formatValue);
                if (guid == null) return new Result(List.of(), "The " + formatValue + " tournament format is not available on this server.");
                return new Result(rules.validate(deck, owned, List.of(guid)), null);
            }
            boolean ok = validator.validFormats(deck, owned).stream()
                    .anyMatch(f -> f.equalsIgnoreCase(formatValue) || f.toLowerCase(Locale.ROOT).equals(formatValue.toLowerCase(Locale.ROOT)));
            String deckId = deck.hasNonNull("deckID") ? deck.get("deckID").asText() : null;
            List<DeckValidationRow.Failure> failures = ok ? List.of() : List.of(new DeckValidationRow.Failure(
                    "BoolRestriction", "This deck is not legal in the " + formatValue + " format.", List.of()));
            return new Result(List.of(new DeckValidationRow(deckId, formatValue, formatValue, ok, failures)), null);
        };
    }
}
