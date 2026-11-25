package com.ptcgo.revival.game.wire;

import com.ptcgo.revival.game.model.Board;
import com.ptcgo.revival.game.model.CardEntity;
import com.ptcgo.revival.game.model.Entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Builders for the in-game messages exactly as ptcgo/game/session/game_session.py and sequence_packets.py emit them.
 * Every nested game message is a polymorphic JsonFx envelope {"name": Type, "value": {...}}; a value that rides the
 * wire inside a SequenceMessage is {sequenceID, gameID, msg: envelope}.
 */
public final class Msgs {
    private Msgs() {}

    /** A child Start/Stop bracket embedded inside a parent sequence. */
    public record NestedSequence(String name, List<Map<String, Object>> messages) {
        public NestedSequence(GameSequence name, List<Map<String, Object>> messages) {
            this(name.wire(), messages);
        }
    }

    public static Map<String, Object> msg(String name, Map<String, Object> value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("value", value);
        return m;
    }

    /** Insertion-ordered map from alternating key/value arguments (null values kept: the client reads them). */
    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /** LocalizableText: {"id": text}. */
    public static Map<String, Object> text(String id) {
        return obj("id", id);
    }

    // ---- sequence brackets ---------------------------------------------------------------------

    /** SequenceMessage value wrapping one nested game message. */
    public static Map<String, Object> sequenceEnvelope(String gameId, String sequenceId, Map<String, Object> msg) {
        return obj("sequenceID", sequenceId, "gameID", gameId, "msg", msg);
    }

    public static Map<String, Object> startSequence(String gameId, String sequenceId, String name) {
        return msg("StartSequence", obj("gameID", gameId, "sequenceID", sequenceId, "name", name));
    }

    public static Map<String, Object> stopSequence(String gameId, String sequenceId, String name) {
        return msg("StopSequence", obj("gameID", gameId, "sequenceID", sequenceId, "name", name));
    }

    /**
     * The SequenceMessage values of one COMPLETE Start/inner/Stop bracket (a bracket is never left open while the
     * server waits for input: nothing renders until StopSequence arrives). Inner items are game-message envelopes
     * or {@link NestedSequence}; a nested bracket rides its own sequence id on every envelope.
     */
    public static List<Map<String, Object>> bracket(String gameId, String name, List<?> inner, Supplier<String> ids) {
        String id = ids.get();
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(sequenceEnvelope(gameId, id, startSequence(gameId, id, name)));
        for (Object o : inner) {
            if (o instanceof NestedSequence n) {
                String child = ids.get();
                out.add(sequenceEnvelope(gameId, child, startSequence(gameId, child, n.name())));
                for (Map<String, Object> m : n.messages()) out.add(sequenceEnvelope(gameId, child, m));
                out.add(sequenceEnvelope(gameId, child, stopSequence(gameId, child, n.name())));
            } else {
                @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
                out.add(sequenceEnvelope(gameId, id, m));
            }
        }
        out.add(sequenceEnvelope(gameId, id, stopSequence(gameId, id, name)));
        return out;
    }

    public static Map<String, Object> standalone(String gameId, Map<String, Object> msg) {
        return sequenceEnvelope(gameId, GameConstants.EMPTY_SEQUENCE_ID, msg);
    }

    // ---- entity messages -----------------------------------------------------------------------

    public static Map<String, Object> entityIntroduced(String gameId, CardEntity card) {
        return msg("EntityIntroduced", obj("gameID", gameId, "entityID", card.entityId,
                "entityName", card.entityName(), "attributeMap", card.serializeAttributes()));
    }

    /** EntityMoved; stamps the client's slot (A.m = positionInParent) on the entity like the client does. */
    public static Map<String, Object> entityMoved(Board board, String entityId, String destinationId, int position) {
        Entity e = board.entity(entityId);
        if (e != null) e.boardSlot = position;
        return msg("EntityMoved", obj("gameID", board.gameId, "entityID", entityId, "destinationID", destinationId,
                "positionInParent", position, "animDuration", GameConstants.MOVE_ANIM_DURATION_MS));
    }

    public static Map<String, Object> attributesReset(String gameId, String entityId) {
        return msg("AttributesReset", obj("gameID", gameId, "entityID", entityId));
    }

    /** A named entity parameter read by the bracket's executor (Evolve From/Into, Retreat Retreating/NewActive, ...). */
    public static Map<String, Object> entityIdDataEffect(String gameId, String key, String entityId) {
        return msg("EntityIDDataEffect", obj("gameID", gameId, "key", key, "value", entityId));
    }

    public static Map<String, Object> attribute(int name, Object value, Object original, Object mod) {
        return obj("name", name, "value", value, "originalValue", original, "modValue", mod);
    }

    public static Map<String, Object> attributeModified(String gameId, String entityId, int name, Object value,
                                                        Object original) {
        return msg("AttributeModified", obj("gameID", gameId, "entityID", entityId,
                "attribute", attribute(name, value, original, null)));
    }

    /** AttributeModified for HP (200490): value = remaining, originalValue = max. */
    public static Map<String, Object> hpAttribute(String gameId, CardEntity pokemon, int maxHp) {
        return attributeModified(gameId, pokemon.entityId, Attr.HP, pokemon.hp(), maxHp);
    }

    /** AttributeModified carrying the entity's FULL Special Conditions array (executors diff it themselves). */
    public static Map<String, Object> conditionAttribute(String gameId, CardEntity pokemon) {
        List<String> c = pokemon.conditions();
        return attributeModified(gameId, pokemon.entityId, Attr.SPECIAL_CONDITIONS, c, c);
    }

    public static Map<String, Object> placeDamageEffect(String gameId, String targetId, int amount) {
        return msg("PlaceDamageEffect", obj("gameID", gameId, "destinationID", targetId, "originID", null,
                "amount", amount, "abilityName", null));
    }

    public static Map<String, Object> revealCardToAll(String gameId, String entityId, boolean returnToOrigin) {
        return msg("RevealCardToAllEffect", obj("gameID", gameId, "entityID", entityId, "Return", returnToOrigin,
                "alwaysReveal", false));
    }

    public static Map<String, Object> shuffled(String gameId, String deckEntityId) {
        return msg("Shuffled", obj("gameID", gameId, "entityID", deckEntityId));
    }

    public static Map<String, Object> activePlayerSet(String gameId, String accountId) {
        return msg("ActivePlayerSet", obj("gameID", gameId, "accountID", accountId));
    }

    /** MultipleCoinFlipWithContextEffect; results use the wire form 0 = heads, 1 = tails. */
    public static Map<String, Object> coinFlip(String gameId, List<Integer> results, String title, String gameText,
                                               String sourceEntityId) {
        return msg("MultipleCoinFlipWithContextEffect", obj("gameID", gameId, "resultLst", results,
                "title", text(title), "gameText", text(gameText), "source", sourceEntityId,
                "targets", List.of(sourceEntityId)));
    }

    public static Map<String, Object> playlessAttackSources(String gameId, String playmatId, List<String> ids) {
        return attributeModified(gameId, playmatId, Attr.ATTACK_SOURCES, ids, ids);
    }

    // ---- prompts and selection offers ----------------------------------------------------------

    /**
     * CustomChoiceOfferMessage describing the opponent's pending coin/go-first choice. With a null sourceEntity the
     * client command (D.x) is a no-op, but it satisfies the >=1-command requirement of the bracket and steers the
     * dialog into the two-player "OpponentPicks..." animator states.
     */
    public static Map<String, Object> customChoiceOffer(String gameId, String selectingPlayerId, List<String> buttons) {
        List<Object> b = new ArrayList<>();
        for (String s : buttons) b.add(obj("id", s));
        return msg("CustomChoiceOfferMessage", obj("gameID", gameId, "selectingPlayer", selectingPlayerId,
                "prompt", text(""), "buttons", b, "offerLength", 30000, "sourceEntity", null,
                "selection", null, "correctChoice", null));
    }

    public static Map<String, Object> pauseOnPrompt(String gameId, String prompt) {
        return msg("PauseOnPromptEffect", obj("gameID", gameId, "prompt", text(prompt), "buttonText", null,
                "doPause", false));
    }

    public static Map<String, Object> closePauseOnPrompt(String gameId) {
        return msg("ClosePauseOnPromptEffect", obj("gameID", gameId));
    }

    /** Stock playmat timer payload for a remaining duration. */
    public static Map<String, Object> idleTimer(String playerId, long remainingMs) {
        int remaining = (int) Math.max(0, Math.min(Integer.MAX_VALUE, remainingMs));
        int countdown = Math.min(GameConstants.ACTION_COUNTDOWN_DURATION_MS, remaining);
        return obj("playerID", playerId, "inactivityDuration", remaining - countdown, "endTurnDuration", countdown,
                "forced", false);
    }

    public static Map<String, Object> entityListTargetInfo(List<String> validTargets, int numberToSelect,
                                                           int minimumToSelect, boolean forced, String kind) {
        return obj("name", kind, "selected", true, "validTargets", validTargets, "numberToSelect", numberToSelect,
                "minimumToSelect", minimumToSelect, "forced", forced);
    }

    public static Map<String, Object> entityListTargetInfo(List<String> validTargets) {
        return entityListTargetInfo(validTargets, 1, 1, true, SelectionKind.ENTITY_LIST.wire());
    }

    public static Map<String, Object> selectableAction(String gameId, String actionId, String description,
                                                       String selectionType) {
        return obj("gameID", gameId, "actionID", actionId, "description", description, "selectionType", selectionType);
    }

    /** One targetMap entry of the main-turn offer. */
    public static Map<String, Object> targetMapEntry(String gameId, String entityId, String actionId,
                                                     String description, List<Map<String, Object>> targetInfos,
                                                     String selectionType) {
        return obj("entityID", entityId, "selectableAction",
                selectableAction(gameId, actionId, description, selectionType), "targetInfoLst", targetInfos);
    }

    /** Main-turn SelectionWithTargetsAndActionsRequired value. No prompt: any DisplayText becomes a banner. */
    public static Map<String, Object> mainOffer(String gameId, int counter, long nowMs, List<Map<String, Object>> targetMap) {
        return obj("gameID", gameId, "counter", counter, "prompt", null, "offerLength", GameConstants.ACTION_TIMEOUT_MS,
                "startingTimestamp", nowMs, "forced", false, "targetType", GameConstants.TARGET_TYPE_MAIN_TURN,
                "ignoreFirst", false, "selectionParams", obj(), "optimalPlayMap", List.of(), "sourceID", null,
                "targetMap", targetMap);
    }

    /** Setup/promotion placement SelectionWithTargetsRequired value: targetMap = {cardId: []}. */
    public static Map<String, Object> placementOffer(String gameId, int counter, long nowMs, String prompt,
                                                     List<String> candidates, String targetType) {
        Map<String, Object> tm = new LinkedHashMap<>();
        for (String c : candidates) tm.put(c, List.of());
        return obj("gameID", gameId, "counter", counter, "prompt", text(prompt), "offerLength", 60000,
                "startingTimestamp", nowMs, "forced", false, "targetType", targetType, "ignoreFirst", false,
                "selectionParams", obj(), "optimalPlayMap", List.of(), "sourceID", null, "targetMap", tm);
    }

    /** Single-node SelectionWithTargetsRequired pick offer (ignoreFirst dives straight into the target node). */
    public static Map<String, Object> pickOffer(String gameId, int counter, long nowMs, boolean forced,
                                                String targetType, String sourceId, Map<String, Object> node) {
        return obj("gameID", gameId, "counter", counter, "prompt", null, "offerLength", 60000,
                "startingTimestamp", nowMs, "forced", forced, "targetType", targetType, "ignoreFirst", true,
                "selectionParams", obj(), "optimalPlayMap", List.of(), "sourceID", sourceId,
                "targetMap", obj(sourceId, List.of(node)));
    }

    /** Prize-pile pick offer (r.B fan-out UI). */
    public static Map<String, Object> prizeOffer(String gameId, int counter, long nowMs, String prompt,
                                                 String prizeAreaId, List<String> prizeIds, int count, int needed) {
        Map<String, Object> info = obj("name", SelectionKind.PRIZE_CARD.wire(), "selected", true,
                "targetPrompt", text(prompt), "validTargets", prizeIds, "numberToSelect", count,
                "minimumToSelect", needed, "forced", needed > 0, "presentPrizesAllowed", true,
                "horizontalLayout", false);
        return obj("gameID", gameId, "counter", counter, "prompt", text(prompt), "offerLength", 60000,
                "startingTimestamp", nowMs, "forced", needed > 0, "targetType", SelectionKind.PRIZE_CARD.wire(),
                "ignoreFirst", true, "selectionParams", obj(), "optimalPlayMap", List.of(), "sourceID", prizeAreaId,
                "targetMap", obj(prizeAreaId, List.of(info)));
    }

    /** Button dialog (generic R.y) CustomChoiceRequired value. */
    public static Map<String, Object> customChoiceRequired(String gameId, int counter, long nowMs, String prompt,
                                                           List<String> buttons, String sortType) {
        List<Object> b = new ArrayList<>();
        for (String s : buttons) b.add(obj("id", s));
        return obj("gameID", gameId, "counter", counter, "prompt", text(prompt), "offerLength", 0,
                "startingTimestamp", nowMs, "sortType", sortType, "buttons", b, "sourceEntity", null, "kind", "");
    }

    /** Pregame CoinFlipChoiceRequired / GoFirstChoiceRequired value (fixed counters 1 and 2). */
    public static Map<String, Object> pregameChoice(String gameId, int counter, long nowMs, String sortType,
                                                    List<String> buttons) {
        List<Object> b = new ArrayList<>();
        for (String s : buttons) b.add(obj("id", s));
        return obj("gameID", gameId, "counter", counter, "prompt", text(""), "offerLength", 30000,
                "startingTimestamp", nowMs, "sortType", sortType, "buttons", b, "sourceEntity", null);
    }

    public static Map<String, Object> mulliganReveal(String gameId, String playerId, String screenName,
                                                     List<Map<String, Object>> piles, String deckId) {
        return msg("MulliganRevealCardsEffect", obj("gameID", gameId, "player", playerId, "entityIDPiles", piles,
                "prompt", text(GameConstants.TEXT_MULLIGAN_REVEAL_PROMPT),
                "revealTitle", text(screenName + " had no Basic Pokémon"), "revealSource", deckId));
    }

    // ---- attack choreography -------------------------------------------------------------------

    public static Map<String, Object> abilityPlayed(String gameId, String sourceEid, String abilityId, String title,
                                                    String abilityType) {
        return msg("AbilityPlayedEffect", obj("gameID", gameId, "eID", sourceEid, "abilityID", abilityId,
                "abilityTitle", text(title), "abilityType", abilityType));
    }

    public static Map<String, Object> abilityFinished(String gameId, String eid) {
        return msg("AbilityFinishedEffect", obj("gameID", gameId, "eID", eid));
    }

    public static Map<String, Object> nonDamagingTargets(String gameId, List<String> targets) {
        return msg("NonDamagingTargetsEffect", obj("gameID", gameId, "targets", targets));
    }

    public static Map<String, Object> cleanupAttack(String gameId, String attackerId, String curvePrefix) {
        Map<String, Object> v = obj("gameID", gameId, "entityID", attackerId);
        if (curvePrefix != null) v.put("cleanupCurvePrefix", curvePrefix);
        return msg("CleanupAttackEffect", v);
    }

    public static Map<String, Object> cakeAttackEffect(String gameId, String sourceId, String targetId,
                                                       boolean weakness, boolean resistance, String damageType,
                                                       String attackName, int amount) {
        return msg("CakeAttackEffect", obj("gameID", gameId, "damageSource", sourceId, "entityID", targetId,
                "weaknessTriggered", weakness, "resistanceTrigger", resistance, "damageType", List.of(damageType),
                "attackName", text(attackName), "damageAmount", amount, "damageModification", 0, "visualType", 0));
    }

    public static Map<String, Object> creatureHeal(String gameId, String sourceId, List<String> targets, int amount) {
        return msg("CreatureHealWithContextEvent", obj("gameID", gameId, "source", sourceId, "targets", targets,
                "amount", amount));
    }

    public static Map<String, Object> vstarPowerUsed(String gameId, String playerId) {
        return msg("VSTARPowerUsedEffect", obj("gameID", gameId, "user", playerId));
    }
}
