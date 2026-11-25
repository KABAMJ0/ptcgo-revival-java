package com.ptcgo.revival.game.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-game turn bookkeeping consumed by the legality rules (legal_actions.py TurnState). The core once-per-turn flags
 * and the two-turn history ledgers are ported; the many effect-specific locks are reachable through {@link #extras}
 * so effect batches can add them without touching the engine.
 */
public final class TurnState implements com.ptcgo.revival.game.effects.TurnStateView {
    public record TrainerPlayed(String archetypeId, String displayName, int trainerType) {}

    public record AttackUsed(String entityId, String archetypeId, String title) {}

    public record LeftPlay(String entityId, String archetypeId, String ownerId, boolean wasActive, Boolean byAttack) {}

    public int turnNumber;
    public String activePlayerId;
    public boolean supporterPlayed;
    public int supporterSlots = 1;
    public int supportersPlayed;
    public boolean stadiumPlayed;
    public boolean energyAttached;
    public int energyAttachSlots = 1;
    public int energyAttachesUsed;
    /** Pokemon id that may receive a 2nd manual Energy attach this turn (alpha Growth style). */
    public String alphaGrowthTarget;
    public boolean retreated;
    /** True while the between-turns Pokemon Checkup runs. */
    public boolean inCheckup;

    /** entity id -> turn number it entered play (or last evolved); setup placements default to turn 0. */
    public final Map<String, Integer> enteredPlayTurn = new HashMap<>();
    /** "entityId|abilityId" pairs used this turn (once-per-turn abilities). */
    public final Set<String> usedAbilities = new HashSet<>();
    public final Set<String> usedNamedAbilities = new HashSet<>();
    public final Set<String> vstarUsed = new HashSet<>();
    /** Players who already used their once-per-game GX attack. */
    public final Set<String> gxAttackUsed = new HashSet<>();
    /** "entityId|abilityId" -> last turn number the attack stays locked. */
    public final Map<String, Integer> attackLocks = new HashMap<>();
    public final Map<String, Integer> playerAttackLocks = new HashMap<>();
    public final Map<String, Integer> retreatLocks = new HashMap<>();
    /** entity id -> turn it last moved into the Active spot (persistent stamp). */
    public final Map<String, Integer> becameActiveTurn = new HashMap<>();
    /** Free-form state for effect batches (keyed by effect-defined names). */
    public final Map<String, Object> extras = new LinkedHashMap<>();

    // --- two-turn history ledgers, rotated this-turn -> last-turn ---
    public List<TrainerPlayed> trainersPlayed = new ArrayList<>(), trainersPlayedLastTurn = new ArrayList<>();
    public List<AttackUsed> attacksUsed = new ArrayList<>(), attacksUsedLastTurn = new ArrayList<>();
    public Map<String, Integer> damageTaken = new HashMap<>(), damageTakenLastTurn = new HashMap<>();
    public Map<String, Integer> attackDamageTaken = new HashMap<>(), attackDamageTakenLastTurn = new HashMap<>();
    public List<LeftPlay> pokemonLeftPlay = new ArrayList<>(), pokemonLeftPlayLastTurn = new ArrayList<>();
    public List<String> movedToActive = new ArrayList<>(), movedToActiveLastTurn = new ArrayList<>();
    public Map<String, Integer> prizesTaken = new HashMap<>(), prizesTakenLastTurn = new HashMap<>();
    public Set<String> retreatedEntities = new HashSet<>(), retreatedEntitiesLastTurn = new HashSet<>();
    public Set<String> healedEntities = new HashSet<>(), healedEntitiesLastTurn = new HashSet<>();
    public Set<String> turnDrawEntityIds = new HashSet<>(), turnDrawEntityIdsLastTurn = new HashSet<>();

    /** Advances to the next turn, resets the once-per-turn flags and rotates the two-turn history. */
    public void beginTurn(String playerId) {
        turnNumber++;
        activePlayerId = playerId;
        supporterPlayed = false;
        supportersPlayed = 0;
        supporterSlots = 1;
        stadiumPlayed = false;
        energyAttached = false;
        energyAttachSlots = 1;
        energyAttachesUsed = 0;
        alphaGrowthTarget = null;
        retreated = false;
        usedAbilities.clear();
        usedNamedAbilities.clear();
        trainersPlayedLastTurn = trainersPlayed;
        trainersPlayed = new ArrayList<>();
        attacksUsedLastTurn = attacksUsed;
        attacksUsed = new ArrayList<>();
        damageTakenLastTurn = damageTaken;
        damageTaken = new HashMap<>();
        attackDamageTakenLastTurn = attackDamageTaken;
        attackDamageTaken = new HashMap<>();
        pokemonLeftPlayLastTurn = pokemonLeftPlay;
        pokemonLeftPlay = new ArrayList<>();
        movedToActiveLastTurn = movedToActive;
        movedToActive = new ArrayList<>();
        prizesTakenLastTurn = prizesTaken;
        prizesTaken = new HashMap<>();
        retreatedEntitiesLastTurn = retreatedEntities;
        retreatedEntities = new HashSet<>();
        healedEntitiesLastTurn = healedEntities;
        healedEntities = new HashSet<>();
        turnDrawEntityIdsLastTurn = turnDrawEntityIds;
        turnDrawEntityIds = new HashSet<>();
    }

    @Override public int turnNumber() { return turnNumber; }
    @Override public String activePlayerId() { return activePlayerId; }
    @Override public boolean supporterPlayed() { return supporterPlayed; }
    @Override public boolean energyAttached() { return energyAttached; }
    @Override public boolean retreated() { return retreated; }

    public void markEnteredPlay(String entityId) {
        enteredPlayTurn.put(entityId, turnNumber);
    }

    public void markMovedToActive(String entityId) {
        becameActiveTurn.put(entityId, turnNumber);
        if (!movedToActive.contains(entityId)) movedToActive.add(entityId);
    }

    /** A Pokemon may evolve only if in play since a previous turn, and never during either player's first turn. */
    public boolean mayEvolveTarget(String entityId) {
        if (turnNumber <= 2) return false;
        return enteredPlayTurn.getOrDefault(entityId, 0) < turnNumber;
    }

    public static String abilityKey(String entityId, String abilityId) {
        return entityId + "|" + abilityId;
    }

    /** Locks an attack through its user's next turn. */
    public void lockAttack(String entityId, String abilityId) {
        attackLocks.put(abilityKey(entityId, abilityId), turnNumber + 2);
    }

    public boolean attackLocked(String entityId, String abilityId) {
        return turnNumber <= attackLocks.getOrDefault(abilityKey(entityId, abilityId), 0);
    }

    public void lockPlayerAttacks(String playerId, Integer throughTurn) {
        playerAttackLocks.put(playerId, throughTurn == null ? turnNumber : throughTurn);
    }

    public boolean playerAttacksLocked(String playerId) {
        return turnNumber <= playerAttackLocks.getOrDefault(playerId, 0);
    }

    public void lockRetreat(String entityId, Integer throughTurn) {
        retreatLocks.put(entityId, throughTurn == null ? turnNumber + 1 : throughTurn);
    }

    public boolean retreatLocked(String entityId) {
        return turnNumber <= retreatLocks.getOrDefault(entityId, 0);
    }
}
