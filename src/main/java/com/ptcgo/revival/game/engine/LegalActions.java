package com.ptcgo.revival.game.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.game.effects.EffectRegistry;
import com.ptcgo.revival.game.model.Board;
import com.ptcgo.revival.game.model.CardEntity;
import com.ptcgo.revival.game.model.PlayArea;
import com.ptcgo.revival.game.wire.Attr;
import com.ptcgo.revival.game.wire.GameConstants;
import com.ptcgo.revival.game.wire.Msgs;
import com.ptcgo.revival.game.wire.SelectionKind;
import com.ptcgo.revival.progression.Uuid5;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.ptcgo.revival.game.wire.GameConstants.*;

/**
 * Legal-action computation for the main turn loop (legal_actions.py). Builds the targetMap of a
 * SelectionWithTargetsAndActionsRequired offer; the client highlights exactly those entities and does no legality
 * checks itself. Effect-specific gates are consulted through the {@link EffectRegistry}.
 */
public final class LegalActions {
    private static final UUID ACTION_ID_NAMESPACE = UUID.fromString("f6c1b1de-5e1a-4b52-9c40-1d1c4e6a7b0d");
    private static final Set<String> IMMOBILIZING = Set.of(Attr.ASLEEP, Attr.PARALYZED);

    private LegalActions() {}

    /** One targetMap entry; {@code targetInfos} are the interactive target nodes (first = the node the reply fills). */
    public record LegalAction(String entityId, String actionId, String description, String selectionType,
                              List<Map<String, Object>> targetInfos) {
        public Map<String, Object> wire(String gameId) {
            return Msgs.targetMapEntry(gameId, entityId, actionId, description, targetInfos, selectionType);
        }

        public List<String> validTargets(int infoIndex) {
            if (infoIndex >= targetInfos.size()) return List.of();
            Object v = targetInfos.get(infoIndex).get("validTargets");
            List<String> out = new ArrayList<>();
            if (v instanceof List<?> l) for (Object o : l) out.add((String) o);
            return out;
        }

        public String key() {
            return entityId + "|" + actionId;
        }
    }

    /** Deterministic GUID action id (the client runs new Guid(id)). */
    public static String actionId(String entityId, String verb) {
        return Uuid5.of(ACTION_ID_NAMESPACE, entityId + ":" + verb).toString();
    }

    private static LegalAction entry(String entityId, String verb, String description, List<Map<String, Object>> infos) {
        return new LegalAction(entityId, actionId(entityId, verb), description, SELECTION_TYPE_PLAY, infos);
    }

    private static Map<String, Object> targetInfo(List<String> targets) {
        return Msgs.entityListTargetInfo(targets);
    }

    private static List<String> ids(List<CardEntity> cards) {
        List<String> out = new ArrayList<>();
        for (CardEntity c : cards) out.add(c.entityId);
        return out;
    }

    public static boolean isLevelUp(CardEntity c) {
        return c.card.subtypes().stream().anyMatch(s -> s.equalsIgnoreCase("level-up") || s.equalsIgnoreCase("lv.x"));
    }

    // ---- attack cost -------------------------------------------------------------------------

    /** How much one energy card pays toward a cost (client s.y: max option length). */
    public static int energyProvidedCount(CardEntity energy) {
        int best = 0;
        for (List<Integer> o : energy.energyOptions()) best = Math.max(best, o.size());
        return best == 0 ? 1 : best;
    }

    private static Set<Integer> energyProvidedTypes(CardEntity energy) {
        Set<Integer> out = new HashSet<>();
        for (List<Integer> o : energy.energyOptions()) out.addAll(o);
        return out;
    }

    /**
     * Whether attached energies can pay an attack cost (type name -> count). Each card pays up to its provided count
     * (Double Colorless pays 2); typed requirements consume the most type-constrained cards first, colorless accepts
     * whatever capacity remains.
     */
    public static boolean attackCostSatisfied(Map<String, Integer> cost, List<CardEntity> energies) {
        class Slot {
            final Set<Integer> types;
            int count;

            Slot(CardEntity e) {
                types = energyProvidedTypes(e);
                count = energyProvidedCount(e);
            }
        }
        List<Slot> pool = new ArrayList<>();
        for (CardEntity e : energies) pool.add(new Slot(e));
        int colorlessNeeded = 0;
        for (Map.Entry<String, Integer> c : cost.entrySet()) {
            int type = Attr.typeByName(c.getKey());
            if (type == Attr.TYPE_NO_COLOR || type == Attr.TYPE_COLORLESS) {
                colorlessNeeded += c.getValue();
                continue;
            }
            for (int i = 0; i < c.getValue(); i++) {
                Slot chosen = null;
                for (Slot s : pool) {
                    if (s.types.contains(type) && (chosen == null || s.types.size() < chosen.types.size())) chosen = s;
                }
                if (chosen == null) return false;
                chosen.count--;
                if (chosen.count <= 0) pool.remove(chosen);
            }
        }
        return pool.stream().mapToInt(s -> s.count).sum() >= colorlessNeeded;
    }

    public static Map<String, Integer> costOf(JsonNode ability) {
        Map<String, Integer> cost = new LinkedHashMap<>();
        JsonNode c = ability.path("cost");
        if (c.isObject()) c.fields().forEachRemaining(e -> cost.put(e.getKey(), e.getValue().asInt()));
        return cost;
    }

    // ---- main computation ------------------------------------------------------------------------

    public static List<LegalAction> compute(Board board, TurnState state, String playerId, String gameId,
                                            EffectRegistry registry) {
        List<LegalAction> entries = new ArrayList<>();
        PlayArea hand = board.area(playerId, "hand");
        PlayArea bench = board.area(playerId, "bench");
        if (hand == null || bench == null) return entries;
        List<CardEntity> inPlay = board.pokemonInPlay(playerId);
        boolean benchHasSpace = bench.children.size() < BENCH_CAPACITY;

        for (CardEntity card : board.cardsIn(playerId, "hand")) {
            if (card.isPokemon()) {
                int stage = card.stage();
                if (isLevelUp(card)) {
                    String from = card.getString(Attr.EVOLUTION_LOGIC_FROM);
                    List<CardEntity> targets = new ArrayList<>();
                    for (CardEntity p : inPlay) {
                        if (!isLevelUp(p) && from != null && from.equals(p.getString(Attr.EVOLUTION_LOGIC_NAME))) targets.add(p);
                    }
                    if (from != null && !from.isEmpty() && !targets.isEmpty()) {
                        entries.add(entry(card.entityId, "evolve", ACTION_EVOLVE, List.of(targetInfo(ids(targets)))));
                    }
                    continue;
                }
                if (stage == Attr.STAGE_RESTORED) continue;
                if (stage == Attr.STAGE_BASIC) {
                    if (benchHasSpace) {
                        entries.add(entry(card.entityId, "play", ACTION_PLAY_POKEMON,
                                List.of(targetInfo(List.of(bench.entityId)))));
                    }
                    continue;
                }
                String from = card.getString(Attr.EVOLUTION_LOGIC_FROM);
                if (from == null || from.isEmpty()) continue;
                List<CardEntity> targets = new ArrayList<>();
                for (CardEntity p : inPlay) {
                    if (from.equals(p.getString(Attr.EVOLUTION_LOGIC_NAME)) && state.mayEvolveTarget(p.entityId)) targets.add(p);
                }
                if (!targets.isEmpty()) {
                    entries.add(entry(card.entityId, "evolve", ACTION_EVOLVE, List.of(targetInfo(ids(targets)))));
                }
            } else if (card.isEnergy()) {
                if ((state.energyAttachesUsed >= state.energyAttachSlots && state.alphaGrowthTarget == null)
                        || inPlay.isEmpty()) continue;
                List<CardEntity> targets = new ArrayList<>();
                for (CardEntity p : inPlay) {
                    if (!state.energyAttached || p.entityId.equals(state.alphaGrowthTarget)) targets.add(p);
                }
                if (!targets.isEmpty()) {
                    entries.add(entry(card.entityId, "energy", ACTION_PLAY_ENERGY, List.of(targetInfo(ids(targets)))));
                }
            } else {
                trainerEntries(board, state, playerId, card, inPlay, registry, entries);
            }
        }

        abilityEntries(board, state, playerId, registry, inPlay, entries);
        boolean immobilized = activeImmobilized(board, playerId);
        if (!state.playerAttacksLocked(playerId)) attackEntries(board, state, playerId, registry, immobilized, entries);
        if (!immobilized) retreatEntry(board, state, playerId, entries);
        return entries;
    }

    private static void trainerEntries(Board board, TurnState state, String playerId, CardEntity card,
                                       List<CardEntity> inPlay, EffectRegistry registry, List<LegalAction> entries) {
        int type = card.trainerType();
        EffectRegistry.TrainerSpec spec = registry.trainer(card.card.guid());
        if (spec != null && spec.condition() != null && !spec.condition().test(board, state, playerId, card)) return;
        // "Discard N of the other cards in your hand in order to play this card": the cost gates the OFFER, not
        // the play, or an unpayable play soft-locks the client.
        if (spec != null && spec.playCost() > 0 && board.cardsIn(playerId, "hand").size() - 1 < spec.playCost()) return;
        List<Map<String, Object>> infos = List.of();
        if (type == Attr.TT_ITEM || type == Attr.TT_SUPPORTER) {
            if (spec != null && spec.targets() != null) {
                List<CardEntity> targets = spec.targets().targets(board, playerId, card);
                if (targets.isEmpty()) return;
                Map<String, Object> node = Msgs.entityListTargetInfo(ids(targets), 1, 0, false, SelectionKind.ENTITY_LIST.wire());
                node.put("targetPrompt", Msgs.text(spec.targets().prompt()));
                infos = List.of(node);
            }
        }
        if (type == Attr.TT_ITEM) {
            entries.add(entry(card.entityId, "item", ACTION_USE_TRAINER, infos));
        } else if (type == Attr.TT_SUPPORTER) {
            if (state.supportersPlayed < state.supporterSlots && state.turnNumber > 1) {
                entries.add(entry(card.entityId, "supporter", ACTION_USE_TRAINER, infos));
            }
        } else if (type == Attr.TT_STADIUM) {
            if (!state.stadiumPlayed && !sameStadiumInPlay(board, card)) {
                entries.add(entry(card.entityId, "stadium", ACTION_PLAY_STADIUM, List.of()));
            }
        } else if (type == Attr.TT_TOOL || type == Attr.TT_TM) {
            List<CardEntity> targets = new ArrayList<>();
            for (CardEntity p : inPlay) if (toolSlotsFree(p)) targets.add(p);
            if (!targets.isEmpty()) entries.add(entry(card.entityId, "tool", ACTION_ATTACH_TOOL, List.of(targetInfo(ids(targets)))));
        }
    }

    /** A Pokemon may hold one Pokemon Tool (server rule; the client has no cap). */
    public static boolean toolSlotsFree(CardEntity pokemon) {
        for (var c : pokemon.children) {
            if (c instanceof CardEntity ce && ce.isTrainer() && ce.trainerType() == Attr.TT_TOOL) return false;
        }
        return true;
    }

    private static boolean sameStadiumInPlay(Board board, CardEntity card) {
        PlayArea stadium = board.globalArea("activeStadium");
        if (stadium == null) return false;
        Object name = card.get(Attr.NAME);
        for (var existing : stadium.children) {
            if (card.archetypeId.equals(existing.archetypeId)) return true;
            if (name != null && name.toString().equals(String.valueOf(existing.get(Attr.NAME)))) return true;
        }
        return false;
    }

    private static boolean activeImmobilized(Board board, String playerId) {
        CardEntity active = board.activePokemon(playerId);
        return active != null && active.conditions().stream().anyMatch(IMMOBILIZING::contains);
    }

    private static void abilityEntries(Board board, TurnState state, String playerId, EffectRegistry registry,
                                       List<CardEntity> inPlay, List<LegalAction> entries) {
        for (CardEntity pokemon : inPlay) {
            Set<String> seen = new HashSet<>();
            for (JsonNode row : pokemon.pieAbilities()) {
                String id = row.path("abilityID").asText("");
                if (id.isEmpty() || !seen.add(id)) continue;
                if (row.path("abilityType").asText("").equals("Attack")) continue;
                EffectRegistry.AbilitySpec spec = registry.ability(id);
                if (spec == null || spec.effect() == null) continue; // unimplemented abilities are not offered
                if (spec.activation() != EffectRegistry.Activation.UNLIMITED
                        && state.usedAbilities.contains(TurnState.abilityKey(pokemon.entityId, id))) continue;
                if (spec.vstar() && state.vstarUsed.contains(playerId)) continue;
                if (spec.condition() != null && !spec.condition().test(board, state, playerId, pokemon)) continue;
                // actionID must be the PIE_ABILITIES abilityID so the pulled-back panel can resolve the text.
                entries.add(new LegalAction(pokemon.entityId, id, ACTION_USE_ABILITY, SELECTION_TYPE_PANEL, List.of()));
            }
        }
    }

    private static boolean isGxAttack(JsonNode row) {
        return row.path("title").path("id").asText("").endsWith("-GX");
    }

    private static void attackEntries(Board board, TurnState state, String playerId, EffectRegistry registry,
                                      boolean immobilized, List<LegalAction> entries) {
        CardEntity active = board.activePokemon(playerId);
        if (active == null) return;
        List<CardEntity> energies = Board.attachedEnergies(active);
        Set<String> seen = new HashSet<>();
        for (JsonNode row : active.pieAbilities()) {
            if (!row.path("abilityType").asText("").equals("Attack")) continue;
            String id = row.path("abilityID").asText("");
            if (id.isEmpty() || !seen.add(id)) continue;
            if (state.attackLocked(active.entityId, id)) continue;
            EffectRegistry.AttackSpec spec = registry.attack(id);
            if (immobilized && (spec == null || !spec.usableDespiteConditions())) continue;
            // The player going first cannot attack on turn 1 unless the attack explicitly allows it.
            if (state.turnNumber == 1 && (spec == null || !spec.usableFirstTurn())) continue;
            if (isGxAttack(row) && state.gxAttackUsed.contains(playerId)) continue;
            if (spec != null && spec.condition() != null && !spec.condition().test(board, state, playerId, active)) continue;
            if (attackCostSatisfied(costOf(row), energies)) {
                entries.add(new LegalAction(active.entityId, id, ACTION_USE_ATTACK, SELECTION_TYPE_PANEL, List.of()));
            }
        }
    }

    private static void retreatEntry(Board board, TurnState state, String playerId, List<LegalAction> entries) {
        if (state.retreated) return;
        CardEntity active = board.activePokemon(playerId);
        if (active == null || state.retreatLocked(active.entityId)) return;
        List<CardEntity> benched = board.benchPokemon(playerId);
        if (benched.isEmpty()) return;
        int cost = Math.max(0, active.getInt(Attr.RETREAT_COST, 0));
        List<CardEntity> energies = Board.attachedEnergies(active);
        if (energies.stream().mapToInt(LegalActions::energyProvidedCount).sum() < cost) return;
        // New active FIRST, cost LAST: the Done button only renders on a node with no successor.
        Map<String, Object> newActive = Msgs.entityListTargetInfo(ids(benched), 1, 1, true,
                SelectionKind.RETREAT_NEW_ACTIVE.wire());
        newActive.put("targetPrompt", Msgs.text(PROMPT_RETREAT_NEW_ACTIVE));
        List<Map<String, Object>> infos = new ArrayList<>(List.of(newActive));
        if (cost > 0) {
            // d.j's pip tray tallies each picked card's provided amount against valueToSelect.
            Map<String, Object> info = Msgs.entityListTargetInfo(ids(energies), cost, -1, true,
                    SelectionKind.RETREAT_COST_ENTITY_LIST.wire());
            info.put("valueToSelect", cost);
            info.put("targetPrompt", Msgs.text(PROMPT_RETREAT_COST));
            infos.add(info);
        }
        entries.add(new LegalAction(active.entityId, actionId(active.entityId, "retreat"), ACTION_RETREAT,
                SELECTION_TYPE_PANEL, infos));
    }
}
