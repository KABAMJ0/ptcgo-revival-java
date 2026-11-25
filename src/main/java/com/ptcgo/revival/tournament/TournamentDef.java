package com.ptcgo.revival.tournament;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ptcgo.revival.db.TournamentRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** One stored tournament (definition_json + enabled). Port of {@code TournamentDef} in tournaments/manager.py. */
public final class TournamentDef {
    public static final long FAR_FUTURE_MS = 4102444800000L; // 2100-01-01

    public static final String STATE_BEFORE_PREVIEW = "BeforePreview";
    public static final String STATE_PREVIEW = "Preview";
    public static final String STATE_OPEN = "Open";
    public static final String STATE_ENTRY_CLOSED = "EntryClosed";
    public static final String STATE_RESOLVED = "Resolved";
    public static final String STATE_HIDDEN = "Hidden";

    /** Legacy G.H feeType strings (client wallet sprites key off these). */
    static final Map<String, String> LEGACY_FEE_TYPES = Map.of(
            "coins", "Tokens", "tokens", "Tokens", "tickets", "TournamentTicket",
            "tournamentticket", "TournamentTicket", "gems", "Tokens");
    public static final List<String> LEGACY_FORMATS = List.of("Unlimited", "ThemeDeck", "Modified", "Expanded", "Legacy");

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final String tournamentId;
    private final JsonNode definition;
    private final boolean enabled;

    public TournamentDef(String tournamentId, JsonNode definition, boolean enabled) {
        this.tournamentId = tournamentId;
        this.definition = definition == null || !definition.isObject() ? F.objectNode() : definition;
        this.enabled = enabled;
    }

    public String tournamentId() {
        return tournamentId;
    }

    public JsonNode definition() {
        return definition;
    }

    public boolean enabled() {
        return enabled;
    }

    public long previewTime() { return Json.intOr(definition, "previewTime", 0); }
    public long startTime() { return Json.intOr(definition, "startTime", 0); }
    public long entryClosingTime() { return Json.intOr(definition, "entryClosingTime", FAR_FUTURE_MS); }
    public long resolutionTime() { return Json.intOr(definition, "resolutionTime", FAR_FUTURE_MS); }
    public long disappearTime() { return Json.intOr(definition, "disappearTime", FAR_FUTURE_MS); }

    public JsonNode runConfig() {
        return definition.path("run");
    }

    public JsonNode leaderboardConfig() {
        return definition.path("leaderboard");
    }

    /** Lifecycle state computed from the configured times. */
    public String state(long atMs) {
        if (atMs > disappearTime()) return STATE_HIDDEN;
        if (atMs > resolutionTime()) return STATE_RESOLVED;
        if (atMs > entryClosingTime()) return STATE_ENTRY_CLOSED;
        if (atMs > startTime()) return STATE_OPEN;
        if (atMs > previewTime()) return STATE_PREVIEW;
        return STATE_BEFORE_PREVIEW;
    }

    public int maxSize() {
        return (int) Json.intOr(definition, "maxSize", 8);
    }

    public int roundLength() {
        return (int) Json.intOr(definition, "roundLength", 30);
    }

    public int delayBetweenRounds() {
        return (int) Json.intOr(definition, "delayBetweenRounds", 10);
    }

    /** The rows the server charges for a live tournament (all rows with amount > 0, currency lower-cased). */
    public List<TournamentRepository.Fee> legacyEntryFees() {
        List<TournamentRepository.Fee> out = new ArrayList<>();
        JsonNode fees = runConfig().path("entryFee");
        if (fees.isArray()) {
            for (JsonNode f : fees) {
                long amount = Json.intOr(f, "amount", 0);
                if (amount > 0) out.add(new TournamentRepository.Fee(Json.strOr(f, "currency", "coins").toLowerCase(Locale.ROOT), amount));
            }
        }
        return out;
    }

    // ---- wire shapes -----------------------------------------------------------------------------

    /** LocalizableText: the client localizer renders unknown ids verbatim. */
    static ObjectNode loc(JsonNode text) {
        if (text != null && text.isObject()) return (ObjectNode) text;
        ObjectNode n = F.objectNode();
        n.put("id", Json.truthy(text) ? text.asText() : "");
        return n;
    }

    /** Name must be "NoReward": the only RewardDefinition subclass in the client's TypeHinting map. */
    public static ObjectNode clientReward(JsonNode r) {
        ObjectNode n = F.objectNode();
        n.put("name", "NoReward");
        n.put("rewardType", Json.strOr(r, "rewardType", "Tokens"));
        n.put("rewardAmount", Json.intOr(r, "rewardAmount", 0));
        n.put("flavor", Json.strOr(r, "flavor", ""));
        return n;
    }

    public static ArrayNode clientRewards(Iterable<? extends JsonNode> rewards) {
        ArrayNode a = F.arrayNode();
        if (rewards != null) rewards.forEach(r -> a.add(clientReward(r)));
        return a;
    }

    private static ArrayNode clientPrizeTable(JsonNode table) {
        ArrayNode out = F.arrayNode();
        if (table != null && table.isArray()) {
            for (JsonNode p : table) {
                ObjectNode row = out.addObject();
                row.put("start", Json.intOr(p, "start", 0));
                row.put("end", Json.intOr(p, "end", 0));
                row.set("rewards", p.path("rewards").isArray() ? clientRewards(p.get("rewards")) : F.arrayNode());
            }
        }
        return out;
    }

    /** ActiveAsyncTournaments definition entry. */
    public ObjectNode toClientDict() {
        JsonNode d = definition;
        JsonNode run = runConfig();
        JsonNode lb = leaderboardConfig();
        JsonNode game = d.path("game");
        ObjectNode n = F.objectNode();
        n.put("id", tournamentId);
        n.put("name", Json.strOr(d, "name", "Tournament"));
        n.set("title", loc(Json.truthy(d.get("title")) ? d.get("title")
                : Json.truthy(d.get("name")) ? d.get("name") : F.textNode("Tournament")));
        n.set("description", loc(d.get("description")));
        n.put("img", Json.strOr(d, "img", ""));
        n.put("background", Json.strOr(d, "background", ""));
        n.put("url", Json.strOr(d, "url", ""));
        n.put("previewTime", previewTime());
        n.put("startTime", startTime());
        n.put("entryClosingTime", entryClosingTime());
        n.put("resolutionTime", resolutionTime());
        n.put("disappearTime", disappearTime());
        n.put("maxRuns", Json.intOr(d, "maxRuns", 0));
        ObjectNode lbn = n.putObject("leaderboard");
        lbn.put("runs", Json.intOr(lb, "runs", 0));
        lbn.put("winValue", Json.intOr(lb, "winValue", 3));
        lbn.put("lossValue", Json.intOr(lb, "lossValue", 0));
        lbn.set("prizeTable", clientPrizeTable(lb.get("prizeTable")));
        ObjectNode rn = n.putObject("run");
        ArrayNode fees = rn.putArray("entryFee");
        if (run.path("entryFee").isArray()) {
            for (JsonNode f : run.get("entryFee")) {
                ObjectNode fe = fees.addObject();
                fe.put("currency", Json.strOr(f, "currency", "Tokens"));
                fe.put("amount", Json.intOr(f, "amount", 0));
            }
        }
        rn.set("prizeTable", clientPrizeTable(run.get("prizeTable")));
        JsonNode ads = run.get("allowDeckSwitching");
        rn.put("allowDeckSwitching", ads == null || ads.isNull() ? true : Json.truthy(ads));
        rn.put("maxWins", Json.intOr(run, "maxWins", 0));
        rn.put("maxLosses", Json.intOr(run, "maxLosses", 0));
        rn.put("maxGames", Json.intOr(run, "maxGames", 0));
        n.put("active", enabled);
        ObjectNode gn = n.putObject("game");
        if (Json.truthy(game.get("format"))) gn.put("format", game.get("format").asText()); else gn.putNull("format");
        gn.put("matchStructure", Json.strOr(game, "matchStructure", "SingleGame"));
        gn.put("strategy", Json.strOr(game, "strategy", "Swiss"));
        gn.set("options", Json.truthy(game.get("options")) ? game.get("options") : F.objectNode());
        n.putNull("limited");
        n.putNull("league");
        n.put("prizeBy", Json.strOr(d, "prizeBy", "wins"));
        return n;
    }

    /** Run prize rows as the legacy G.I[] (start/end are 1-based places there). */
    private ArrayNode legacyPrizes() {
        ArrayNode out = F.arrayNode();
        JsonNode table = runConfig().path("prizeTable");
        if (table.isArray()) {
            for (JsonNode row : table) {
                for (JsonNode reward : row.path("rewards")) {
                    boolean card = "Archetype".equals(reward.path("rewardType").asText(null));
                    ObjectNode p = out.addObject();
                    ObjectNode type = p.putObject("prizeType");
                    type.put("type", card ? "Archetype" : "Tokens");
                    if (card) type.put("archetypeID", reward.path("rewardProductID").asText("None")); else type.putNull("archetypeID");
                    p.put("amount", Json.intOr(reward, "rewardAmount", 0));
                    p.put("startPlace", Json.intOr(row, "start", 0));
                    p.put("endPlace", Json.intOr(row, "end", 0));
                }
            }
        }
        return out;
    }

    /** Legacy Events-scene entry (pie J.G.g). entryFee / prizes must be non-null arrays: renderers deref them unguarded. */
    public ObjectNode toLegacyDict() {
        JsonNode d = definition;
        JsonNode descSrc = d.get("description");
        String desc = "";
        if (Json.truthy(descSrc)) {
            if (descSrc.isObject()) desc = descSrc.path("id").asText(""); else desc = descSrc.asText();
        }
        ObjectNode n = F.objectNode();
        n.put("tournamentID", tournamentId);
        n.put("name", Json.strOr(d, "name", "Tournament"));
        n.putNull("il8nName");
        n.put("description", desc);
        n.putNull("il8nDescription");
        n.put("format", Json.strOr(d, "format", "Unlimited"));
        n.put("maxSize", maxSize());
        n.put("tournamentType", Json.strOr(d, "tournamentType", "premiumTournament"));
        n.put("matchStructure", Json.strOr(d, "matchStructure", "SingleElimination"));
        n.put("roundLength", roundLength());
        n.put("delayBetweenRounds", delayBetweenRounds());
        ArrayNode fees = n.putArray("entryFee");
        for (TournamentRepository.Fee f : legacyEntryFees()) {
            ObjectNode fe = fees.addObject();
            fe.put("feeType", LEGACY_FEE_TYPES.getOrDefault(f.currency(), "Tokens"));
            fe.put("feeAmount", f.amount());
        }
        n.set("prizes", legacyPrizes());
        n.put("active", enabled && STATE_OPEN.equals(state(System.currentTimeMillis())));
        return n;
    }

    /** Sanity-checks an admin-supplied definition. Returns error text or null. */
    public static String validateDefinition(JsonNode definition) {
        if (definition == null || !definition.isObject()) return "definition must be an object";
        if (Json.strOr(definition, "name", "").isBlank()) return "name required";
        List<Long> stages = new ArrayList<>();
        for (String k : new String[] {"previewTime", "startTime", "entryClosingTime", "resolutionTime", "disappearTime"}) {
            JsonNode v = definition.get(k);
            long t;
            if (!Json.truthy(v)) t = 0;
            else if (v.isNumber()) t = v.asLong();
            else {
                try {
                    t = Long.parseLong(v.asText().strip());
                } catch (NumberFormatException e) {
                    return "times must be epoch milliseconds";
                }
            }
            if (t != 0) stages.add(t);
        }
        for (int i = 1; i < stages.size(); i++) {
            if (stages.get(i - 1) > stages.get(i)) {
                return "times must be in order: preview <= start <= entryClosing <= resolution <= disappear";
            }
        }
        JsonNode ms = definition.get("maxSize");
        if (Json.truthy(ms) && !ms.isNumber()) {
            try {
                Long.parseLong(ms.asText().strip());
            } catch (NumberFormatException e) {
                return "maxSize must be a number";
            }
        }
        long maxSize = Json.intOr(definition, "maxSize", 8);
        if (maxSize != 2 && maxSize != 4 && maxSize != 8) {
            return "maxSize must be 2, 4 or 8 (the bracket UI renders 8-player brackets)";
        }
        if (!"SingleElimination".equals(Json.strOr(definition, "matchStructure", "SingleElimination"))) {
            return "only SingleElimination matchStructure is supported";
        }
        JsonNode fmt = definition.get("format");
        if (Json.truthy(fmt) && !LEGACY_FORMATS.contains(fmt.asText())) {
            return "format must be one of " + String.join(", ", LEGACY_FORMATS);
        }
        JsonNode run = definition.path("run");
        if (!(Json.intOr(run, "maxWins", 0) > 0 || Json.intOr(run, "maxLosses", 0) > 0 || Json.intOr(run, "maxGames", 0) > 0)) {
            return "run needs at least one of maxWins/maxLosses/maxGames > 0";
        }
        for (JsonNode fee : run.path("entryFee")) {
            String cur = fee.path("currency").asText("").toLowerCase(Locale.ROOT);
            if (!TournamentRepository.CURRENCY_FIELDS.containsKey(cur)) {
                return "unknown entry fee currency: " + fee.path("currency").asText("None");
            }
            if (Json.intOr(fee, "amount", 0) < 0) return "entry fee amount must be >= 0";
        }
        for (JsonNode owner : new JsonNode[] {run, definition.path("leaderboard")}) {
            for (JsonNode prize : owner.path("prizeTable")) {
                if (Json.intOr(prize, "start", 0) > Json.intOr(prize, "end", 0)) return "prize row start must be <= end";
                for (JsonNode r : prize.path("rewards")) {
                    String rtype = r.path("rewardType").asText(null);
                    if (!"Tokens".equals(rtype) && !"Archetype".equals(rtype)) {
                        return "unsupported rewardType: " + (rtype == null ? "None" : rtype) + " (use Tokens or Archetype)";
                    }
                    if ("Archetype".equals(rtype) && Json.strOr(r, "rewardProductID", "").isBlank()) {
                        return "Archetype reward missing rewardProductID";
                    }
                }
            }
        }
        return null;
    }
}
