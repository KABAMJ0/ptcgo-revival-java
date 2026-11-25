package com.ptcgo.revival.game.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.game.effects.EffectRegistry;
import com.ptcgo.revival.game.engine.GamePlayer;
import com.ptcgo.revival.game.engine.LegalActions;
import com.ptcgo.revival.game.engine.LegalActions.LegalAction;
import com.ptcgo.revival.game.engine.MatchSettings;
import com.ptcgo.revival.game.engine.TurnState;
import com.ptcgo.revival.game.model.Board;
import com.ptcgo.revival.game.model.CardEntity;
import com.ptcgo.revival.game.model.PlayArea;
import com.ptcgo.revival.game.wire.Attr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.ptcgo.revival.game.wire.GameConstants.*;

/**
 * Turn policy for the built-in AI opponent (ai_policy.py). Pure decision logic: every choice is one of the offered
 * legal actions, applied through the normal action path. The turn, in the order a competent player works through it:
 * bench every Basic, play Trainers (each card at most once), evolve, attach the one Energy where it makes an attack
 * affordable soonest, attach Tools, attack with the hardest-hitting attack it can pay for. Abilities and retreating
 * (except to swap in a ready attacker) are left out on purpose: they need card-specific judgement.
 */
public final class AiPolicy {
    /** Trainers the AI never plays: they return or weaken its own Pokemon. */
    private static final Pattern SELF_HARM_TRAINERS = Pattern.compile("scoop up|devolution|devolve", Pattern.CASE_INSENSITIVE);
    /** Stop playing Trainers at or below this many cards in the deck. */
    public static final int LOW_DECK = 25;

    public record Choice(LegalAction action, List<String> targets) {}

    /** smart (default) | lite | basic; a per-player override wins over the server default. */
    public String policyName(GamePlayer player, MatchSettings settings) {
        if (player instanceof com.ptcgo.revival.game.engine.AiPlayer ai && ai.policy != null) return ai.policy;
        return settings.aiPolicy;
    }

    public boolean usesFullPolicy(GamePlayer player) {
        String n = player instanceof com.ptcgo.revival.game.engine.AiPlayer ai && ai.policy != null ? ai.policy : "smart";
        return n.equals("smart") || n.equals("abilities");
    }

    // ---- attack data -----------------------------------------------------------------------------

    private static JsonNode row(CardEntity pokemon, String abilityId) {
        for (JsonNode r : pokemon.pieAbilities()) if (abilityId.equalsIgnoreCase(r.path("abilityID").asText())) return r;
        return null;
    }

    static int attackDamage(CardEntity pokemon, String abilityId) {
        JsonNode r = pokemon == null ? null : row(pokemon, abilityId);
        return r == null ? 0 : r.path("damage").asInt(0);
    }

    private static boolean oneShot(CardEntity pokemon, String abilityId) {
        JsonNode r = pokemon == null ? null : row(pokemon, abilityId);
        return r != null && r.path("title").path("id").asText("").endsWith("-GX");
    }

    /** (ability id, cost) for each attack the Pokemon carries. */
    private static List<Map.Entry<String, Map<String, Integer>>> attackOptions(CardEntity pokemon) {
        List<Map.Entry<String, Map<String, Integer>>> out = new ArrayList<>();
        for (JsonNode r : pokemon.pieAbilities()) {
            if (r.path("abilityType").asText("").equals("Attack") && !r.path("abilityID").asText("").isEmpty()) {
                out.add(Map.entry(r.path("abilityID").asText(), LegalActions.costOf(r)));
            }
        }
        return out;
    }

    /** Printed damage after the defender's Weakness (Resistance is ignored: rare). */
    static int estimatedDamage(int base, CardEntity attacker, CardEntity defender) {
        if (base == 0 || attacker == null || defender == null) return base;
        List<Integer> mine = attacker.intList(Attr.POKEMON_TYPES);
        List<Integer> weak = defender.intList(Attr.WEAKNESS_TYPES);
        if (mine.stream().anyMatch(weak::contains)) {
            String op = defender.getString(Attr.WEAKNESS_OPERATOR);
            int amount = defender.getInt(Attr.WEAKNESS_AMOUNT, 2);
            if (amount == 0) amount = 2;
            return op == null || op.equalsIgnoreCase("x") || op.equals("×") || op.equals("*") ? base * amount : base + amount;
        }
        return base;
    }

    LegalAction pickAttack(List<LegalAction> entries, CardEntity attacker, CardEntity defender, boolean smart) {
        java.util.function.ToIntFunction<LegalAction> score = e -> {
            int base = attackDamage(attacker, e.actionId());
            return smart ? estimatedDamage(base, attacker, defender) : base;
        };
        if (smart && defender != null) {
            int hp = defender.hp();
            List<LegalAction> kills = new ArrayList<>();
            for (LegalAction e : entries) if (hp > 0 && score.applyAsInt(e) >= hp) kills.add(e);
            if (!kills.isEmpty()) {
                kills.sort((a, b) -> {
                    int c = Boolean.compare(oneShot(attacker, a.actionId()), oneShot(attacker, b.actionId()));
                    return c != 0 ? c : Integer.compare(score.applyAsInt(a), score.applyAsInt(b));
                });
                return kills.get(0);
            }
        }
        LegalAction best = null;
        for (LegalAction e : entries) {
            if (best == null) {
                best = e;
                continue;
            }
            int a = score.applyAsInt(e), b = score.applyAsInt(best);
            if (smart && oneShot(attacker, e.actionId()) != oneShot(attacker, best.actionId())) {
                // a once-a-game attack must beat the alternative by a clear margin
                if (oneShot(attacker, e.actionId())) a -= 60;
                else b -= 60;
            }
            if (a > b) best = e;
        }
        return best;
    }

    // ---- energy / evolve targets ------------------------------------------------------------------

    double energyTargetScore(CardEntity pokemon, CardEntity energyCard, boolean isActive) {
        List<CardEntity> attached = Board.attachedEnergies(pokemon);
        double score = isActive ? 10.0 : 0.0;
        var options = attackOptions(pokemon);
        if (options.isEmpty()) return score - 5;
        List<CardEntity> withNew = new ArrayList<>(attached);
        withNew.add(energyCard);
        List<Integer> unlocked = new ArrayList<>();
        for (var o : options) {
            if (!LegalActions.attackCostSatisfied(o.getValue(), attached) && LegalActions.attackCostSatisfied(o.getValue(), withNew)) {
                unlocked.add(attackDamage(pokemon, o.getKey()));
            }
        }
        if (!unlocked.isEmpty()) return score + 100 + unlocked.stream().mapToInt(Integer::intValue).max().getAsInt() / 10.0;
        if (options.stream().anyMatch(o -> LegalActions.attackCostSatisfied(o.getValue(), attached))) return score - 3;
        return score + 5 + attached.size() * 0.5; // still building toward an attack
    }

    String bestEnergyTarget(Board board, String playerId, String energyCardId, List<String> targets) {
        CardEntity active = board.activePokemon(playerId);
        String activeId = active == null ? null : active.entityId;
        CardEntity energy = board.card(energyCardId);
        String best = null;
        Double bestScore = null;
        for (String t : targets) {
            CardEntity p = board.card(t);
            if (p == null || energy == null) continue;
            double s = energyTargetScore(p, energy, t.equals(activeId));
            if (bestScore == null || s > bestScore) {
                best = t;
                bestScore = s;
            }
        }
        return best != null ? best : (targets.isEmpty() ? null : targets.get(0));
    }

    /** Evolve the Pokemon carrying the most Energy (the one already being built up). */
    String bestEvolveTarget(Board board, List<String> targets) {
        String best = null;
        int bestHave = -1;
        for (String t : targets) {
            CardEntity p = board.card(t);
            int have = p == null ? 0 : Board.attachedEnergies(p).size();
            if (have > bestHave) {
                best = t;
                bestHave = have;
            }
        }
        return best;
    }

    // ---- actions ----------------------------------------------------------------------------------------

    /** The next non-attack action to take, or null when it is time to attack. */
    public Choice nextAction(Board board, TurnState turn, String playerId, String gameId, EffectRegistry registry,
                             Set<String> tried) {
        List<LegalAction> entries = LegalActions.compute(board, turn, playerId, gameId, registry);
        PlayArea deck = board.area(playerId, "deck");
        boolean deckIsLow = deck != null && deck.children.size() <= LOW_DECK;
        List<String> order = List.of(ACTION_PLAY_POKEMON, ACTION_EVOLVE, ACTION_USE_TRAINER, ACTION_PLAY_STADIUM,
                ACTION_PLAY_ENERGY, ACTION_ATTACH_TOOL);
        for (String wanted : order) {
            for (LegalAction entry : entries) {
                if (!entry.description().equals(wanted) || tried.contains(entry.key())) continue;
                if (wanted.equals(ACTION_USE_TRAINER)) {
                    if (deckIsLow) continue;
                    CardEntity card = board.card(entry.entityId());
                    String name = card == null || card.card.displayName() == null ? "" : card.card.displayName();
                    if (SELF_HARM_TRAINERS.matcher(name).find()) continue;
                }
                List<String> targets = entry.validTargets(0);
                if (wanted.equals(ACTION_PLAY_ENERGY)) {
                    String t = bestEnergyTarget(board, playerId, entry.entityId(), targets);
                    if (t == null) continue;
                    return new Choice(entry, List.of(t));
                }
                if (wanted.equals(ACTION_EVOLVE)) {
                    String t = bestEvolveTarget(board, targets);
                    if (t == null) continue;
                    return new Choice(entry, List.of(t));
                }
                return new Choice(entry, targets.isEmpty() ? List.of() : List.of(targets.get(0)));
            }
        }
        return null;
    }

    public LegalAction attackAction(Board board, TurnState turn, String playerId, String gameId, EffectRegistry registry,
                                    boolean smart) {
        List<LegalAction> entries = LegalActions.compute(board, turn, playerId, gameId, registry).stream()
                .filter(e -> e.description().equals(ACTION_USE_ATTACK)).toList();
        if (entries.isEmpty()) return null;
        CardEntity attacker = board.activePokemon(playerId);
        CardEntity defender = board.activePokemon(board.opponentOf(playerId));
        return pickAttack(entries, attacker, defender, smart);
    }

    /** Swap a stuck Active for a benched Pokemon that can attack right now (null unless one is ready). */
    public Choice retreatAction(Board board, TurnState turn, String playerId, String gameId, EffectRegistry registry) {
        for (LegalAction entry : LegalActions.compute(board, turn, playerId, gameId, registry)) {
            if (!entry.description().equals(ACTION_RETREAT)) continue;
            if (entry.targetInfos().isEmpty()) return null;
            int bestDamage = -1;
            String bestId = null;
            for (String benchId : entry.validTargets(0)) {
                CardEntity p = board.card(benchId);
                if (p == null) continue;
                List<CardEntity> attached = Board.attachedEnergies(p);
                int ready = -1;
                for (var o : attackOptions(p)) {
                    if (LegalActions.attackCostSatisfied(o.getValue(), attached)) ready = Math.max(ready, attackDamage(p, o.getKey()));
                }
                if (ready >= 0 && (bestId == null || ready > bestDamage)) {
                    bestDamage = ready;
                    bestId = benchId;
                }
            }
            if (bestId == null) return null;
            List<String> chosen = new ArrayList<>(List.of(bestId));
            if (entry.targetInfos().size() > 1) {
                Object vts = entry.targetInfos().get(1).get("valueToSelect");
                int need = vts instanceof Number n ? n.intValue() : 0, paid = 0;
                for (String eid : entry.validTargets(1)) {
                    CardEntity e = board.card(eid);
                    if (e == null) continue;
                    chosen.add(eid);
                    paid += LegalActions.energyProvidedCount(e);
                    if (paid >= need) break;
                }
            }
            return new Choice(entry, chosen);
        }
        return null;
    }

    /** One that can attack right away comes first (harder hits better), then the closest to attacking, then the sturdiest. */
    public CardEntity bestPromotion(List<CardEntity> candidates) {
        CardEntity best = null;
        long[] bestKey = null;
        for (CardEntity p : candidates) {
            List<CardEntity> attached = Board.attachedEnergies(p);
            var options = attackOptions(p);
            int ready = -1;
            for (var o : options) if (LegalActions.attackCostSatisfied(o.getValue(), attached)) ready = Math.max(ready, attackDamage(p, o.getKey()));
            int minNeed = 99;
            if (!options.isEmpty()) {
                minNeed = Integer.MAX_VALUE;
                for (var o : options) minNeed = Math.min(minNeed, o.getValue().values().stream().mapToInt(Integer::intValue).sum() - attached.size());
            }
            long[] key = {ready, -minNeed, p.hp()};
            if (bestKey == null || java.util.Arrays.compare(key, bestKey) > 0) {
                best = p;
                bestKey = key;
            }
        }
        return best;
    }

    /** The opening Active: the Basic whose cheapest attack costs least, then sturdiest. */
    public CardEntity bestSetupActive(List<CardEntity> basics) {
        CardEntity best = null;
        long[] bestKey = null;
        for (CardEntity p : basics) {
            var options = attackOptions(p);
            int minNeed = 99;
            if (!options.isEmpty()) {
                minNeed = Integer.MAX_VALUE;
                for (var o : options) minNeed = Math.min(minNeed, o.getValue().values().stream().mapToInt(Integer::intValue).sum());
            }
            long[] key = {-minNeed, p.hp()};
            if (bestKey == null || java.util.Arrays.compare(key, bestKey) > 0) {
                best = p;
                bestKey = key;
            }
        }
        return best;
    }
}
