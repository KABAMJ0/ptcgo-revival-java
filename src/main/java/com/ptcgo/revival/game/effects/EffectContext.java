package com.ptcgo.revival.game.effects;

import com.ptcgo.revival.game.model.Board;
import com.ptcgo.revival.game.model.CardEntity;
import com.ptcgo.revival.game.wire.Attr;
import com.ptcgo.revival.game.wire.GameSequence;
import com.ptcgo.revival.game.wire.Msgs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a card effect sees and may do. Messages an effect produces are queued here and flushed by the engine as
 * per-viewer brackets after the effect returns (attack effects ride inside the Attack bracket; tagged runs flush as
 * their own top-level brackets after it). The primitives mirror the ones the engine itself uses: damage with the
 * full weakness/resistance pipeline, coin flips, status conditions, healing, drawing and targeted selections.
 */
public final class EffectContext {
    /** One queued message: viewer null = everyone; bracket null = the default of the flush. */
    public record Queued(String viewerId, Object msg, String bracket) {}

    public final SessionOps ops;
    public final String playerId;
    /** The acting card: the attacking/ability Pokemon or the played Trainer. */
    public final CardEntity source;
    public final String abilityId;
    public final String title;
    /** Printed damage of the attack (0 for non-attacks). */
    public final int printedDamage;
    public final boolean attack;

    private final List<Queued> messages = new ArrayList<>();
    public final List<CardEntity> knockouts = new ArrayList<>();
    public final List<String> visualTargets = new ArrayList<>();
    public boolean dealtDamage;
    public boolean dealtOpponentDamage;
    public boolean endsTurn;
    public boolean attackKeepsTurn;
    public final List<Boolean> coinResults = new ArrayList<>();
    /** Free-form data for multi-step effects. */
    public final Map<String, Object> scratch = new java.util.LinkedHashMap<>();

    public EffectContext(SessionOps ops, String playerId, CardEntity source, String abilityId, String title,
                         int printedDamage, boolean attack) {
        this.ops = ops;
        this.playerId = playerId;
        this.source = source;
        this.abilityId = abilityId;
        this.title = title;
        this.printedDamage = printedDamage;
        this.attack = attack;
    }

    public Board board() {
        return ops.board();
    }

    public String opponentId() {
        return board().opponentOf(playerId);
    }

    public CardEntity myActive() {
        return board().activePokemon(playerId);
    }

    public CardEntity defender() {
        return board().activePokemon(opponentId());
    }

    public List<Queued> messages() {
        return messages;
    }

    public void queue(Map<String, Object> msg) {
        messages.add(new Queued(null, msg, null));
    }

    /** Queues a game message or a {@link Msgs.NestedSequence}; bracket null = the flush default. */
    public void queue(Object msg, String viewerId, GameSequence bracket) {
        messages.add(new Queued(viewerId, msg, bracket == null ? null : bracket.wire()));
    }

    /** The viewer's messages grouped into consecutive same-bracket runs. */
    public List<Map.Entry<String, List<Object>>> bracketRunsFor(String viewerId, String defaultBracket) {
        List<Map.Entry<String, List<Object>>> runs = new ArrayList<>();
        for (Queued q : messages) {
            if (q.viewerId() != null && !q.viewerId().equals(viewerId)) continue;
            String name = q.bracket() == null ? defaultBracket : q.bracket();
            if (!runs.isEmpty() && runs.get(runs.size() - 1).getKey().equals(name)) {
                runs.get(runs.size() - 1).getValue().add(q.msg());
            } else {
                List<Object> l = new ArrayList<>();
                l.add(q.msg());
                runs.add(Map.entry(name, l));
            }
        }
        return runs;
    }

    // ---- primitives ----------------------------------------------------------------------------

    /** Deals the attack's printed damage to the opposing Active. */
    public int dealDamage() {
        return dealDamage(printedDamage, null, false);
    }

    /**
     * Damages a Pokemon (default: the opposing Active) and returns the final amount after modifiers. Weakness and
     * resistance apply only to the opposing Active. {@code asCounters} places raw counters (no weakness/resistance,
     * no modifiers) with the counter-drop animation instead of the attack lunge.
     */
    public int dealDamage(int amount, CardEntity target, boolean asCounters) {
        CardEntity t = target != null ? target : defender();
        if (t == null || amount <= 0) return 0;
        boolean applyWr = t == defender();
        int dealt;
        DamageCalc calc = null;
        if (asCounters) {
            dealt = amount;
        } else {
            calc = DamageCalc.compute(board(), source, t, amount, attack, applyWr, title, ops.registry().damageModifiers());
            if (calc.prevented) return 0;
            dealt = calc.amount;
        }
        int current = t.hp();
        int remaining = Math.max(0, current - dealt);
        t.set(Attr.HP, remaining);
        String gid = ops.gameId();
        if (asCounters) {
            queue(Msgs.placeDamageEffect(gid, t.entityId, dealt));
            if (!visualTargets.contains(t.entityId)) visualTargets.add(t.entityId);
        } else {
            if (dealt > 0) dealtDamage = true;
            List<Integer> types = source.intList(Attr.POKEMON_TYPES);
            String typeName = types.isEmpty() ? "Colorless" : Attr.TYPE_NAMES.getOrDefault(types.get(0), "Colorless");
            queue(Msgs.cakeAttackEffect(gid, source.entityId, t.entityId, calc.weaknessHit, calc.resistanceHit, typeName,
                    title == null ? "" : title, dealt));
        }
        if (!java.util.Objects.equals(t.owningPlayerId, source.owningPlayerId)) {
            if (!asCounters) dealtOpponentDamage = true;
            if (dealt > 0) ops.recordDamage(t.entityId, dealt, attack && !asCounters);
            ops.statAdd(playerId, "damagedealt", dealt);
            ops.creditCardDamage(playerId, source, dealt);
            if (attack && !asCounters) ops.statMax(playerId, "biggestattack", dealt);
        }
        queueHpUpdate(t);
        if (remaining <= 0 && !knockouts.contains(t)) knockouts.add(t);
        return dealt;
    }

    private void queueHpUpdate(CardEntity t) {
        queue(Msgs.hpAttribute(ops.gameId(), t, ops.maxHp(t)));
    }

    /** Knocks a Pokemon Out directly (an effect, not damage). */
    public boolean knockOut(CardEntity target) {
        if (target == null) return false;
        target.set(Attr.HP, 0);
        queueHpUpdate(target);
        if (!knockouts.contains(target)) knockouts.add(target);
        return true;
    }

    /** Heals up to {@code amount} damage from a Pokemon (default: own Active); returns the healed amount. */
    public int heal(int amount, CardEntity target) {
        CardEntity t = target != null ? target : myActive();
        if (t == null || amount <= 0) return 0;
        int current = t.hp();
        int healed = Math.min(ops.maxHp(t), current + amount) - current;
        if (healed <= 0) return 0;
        t.set(Attr.HP, current + healed);
        ops.markHealed(t.entityId);
        ops.statAdd(playerId, "damagehealed", healed);
        String gid = ops.gameId();
        queue(Msgs.creatureHeal(gid, source.entityId, List.of(t.entityId), healed));
        queue(Msgs.creatureHeal(gid, source.entityId, List.of(), 0)); // trailing empty event: fire-and-forget path
        queueHpUpdate(t);
        return healed;
    }

    /** Flips coins for an effect; results are true = heads. One MultipleCoinFlipWithContextEffect shows the run. */
    public List<Boolean> flipCoins(int count, String flipTitle) {
        List<Boolean> out = new ArrayList<>();
        if (count <= 0) return out;
        List<Integer> wire = new ArrayList<>();
        for (int i = 0; i < count; i++) wire.add(ops.rng().nextBoolean() ? 0 : 1);
        int heads = (int) wire.stream().filter(r -> r == 0).count();
        ops.statAdd(playerId, "headsflipped", heads);
        ops.statAdd(playerId, "tailsflipped", count - heads);
        for (int r : wire) out.add(r == 0);
        coinResults.addAll(out);
        String name = flipTitle != null && !flipTitle.isEmpty() ? flipTitle : source.card.displayName();
        queue(Msgs.coinFlip(ops.gameId(), wire, name, heads + " heads", source.entityId));
        return out;
    }

    /**
     * "Flip a coin until you get tails": one coin screen shows the whole run; returns the number of heads (Python
     * {@code flip_until_tails}).
     */
    public int flipUntilTails(String flipTitle) {
        List<Integer> wire = new ArrayList<>();
        do {
            wire.add(ops.rng().nextBoolean() ? 0 : 1);
        } while (wire.get(wire.size() - 1) == 0);
        int heads = wire.size() - 1;
        ops.statAdd(playerId, "headsflipped", heads);
        ops.statAdd(playerId, "tailsflipped", 1);
        for (int r : wire) coinResults.add(r == 0);
        String name = flipTitle != null && !flipTitle.isEmpty() ? flipTitle : source.card.displayName();
        queue(Msgs.coinFlip(ops.gameId(), wire, name, heads + " heads", source.entityId));
        return heads;
    }

    /**
     * Applies a Special Condition (attr 200340). Asleep/Confused/Paralyzed are mutually exclusive and replace each
     * other; Poisoned/Burned stack with everything. Returns false for a target that is not in play.
     */
    public boolean applySpecialCondition(CardEntity target, String condition) {
        return applySpecialCondition(target, condition, 1);
    }

    /**
     * As {@link #applySpecialCondition(CardEntity, String)}, with {@code checkupCoins} coins flipped at Pokemon Checkup
     * for Asleep (waking needs all heads; Python {@code checkup_coins}).
     */
    public boolean applySpecialCondition(CardEntity target, String condition, int checkupCoins) {
        if (target == null) return false;
        if (!visualTargets.contains(target.entityId)) visualTargets.add(target.entityId);
        List<String> current = target.conditions();
        List<String> exclusive = List.of(Attr.ASLEEP, Attr.CONFUSED, Attr.PARALYZED);
        if (exclusive.contains(condition)) {
            for (String other : exclusive) {
                if (other.equals(condition)) continue;
                current.remove(other);
                if (other.equals(Attr.ASLEEP)) ops.conditions().sleepCheckupCoins.remove(target.entityId);
                else ops.conditions().paralyzedSince.remove(target.entityId);
            }
        }
        if (!current.contains(condition)) current.add(condition);
        target.setConditions(current);
        switch (condition) {
            case Attr.ASLEEP -> ops.conditions().sleepCheckupCoins.put(target.entityId, Math.max(1, checkupCoins));
            case Attr.POISONED -> ops.conditions().poisonCounters.put(target.entityId, 1);
            case Attr.CONFUSED -> ops.conditions().confusionDamage.remove(target.entityId);
            case Attr.PARALYZED -> ops.conditions().paralyzedSince.put(target.entityId, ops.turn().turnNumber());
            default -> { }
        }
        // The executor diffs the full new array; its ctor requires a "Target" data effect.
        queue(Msgs.entityIdDataEffect(ops.gameId(), "Target", target.entityId), null, GameSequence.ADD_SPECIAL_CONDITION);
        queue(Msgs.conditionAttribute(ops.gameId(), target), null, GameSequence.ADD_SPECIAL_CONDITION);
        return true;
    }

    /** Locks the target's retreat through the opponent's next turn (cleared when it leaves the Active spot). */
    public void lockRetreat(CardEntity target) {
        if (target != null) ops.lockRetreat(target);
    }

    /** Pokemon in play (Active first, then Bench) for "mine", "opponent"/"theirs" or "both". */
    public List<CardEntity> pokemonInPlay(String side) {
        List<CardEntity> out = new ArrayList<>();
        if (side.equals("both")) {
            out.addAll(board().pokemonInPlay(playerId));
            out.addAll(board().pokemonInPlay(opponentId()));
        } else {
            out.addAll(board().pokemonInPlay(sideId(side)));
        }
        return out;
    }

    /** Benched Pokemon for "mine", "opponent"/"theirs" or "both". */
    public List<CardEntity> bench(String side) {
        List<CardEntity> out = new ArrayList<>();
        if (side.equals("both")) {
            out.addAll(board().benchPokemon(playerId));
            out.addAll(board().benchPokemon(opponentId()));
        } else {
            out.addAll(board().benchPokemon(sideId(side)));
        }
        return out;
    }

    /** The player id for "opponent"/"theirs", else the acting player (Python {@code _side_pid}). */
    public String sideId(String side) {
        return side.equals("opponent") || side.equals("theirs") ? opponentId() : playerId;
    }

    public int handSize(String side) {
        return board().cardsIn(sideId(side), "hand").size();
    }

    public int prizesTaken(String side) {
        return board().prizesTaken(sideId(side));
    }

    // ---- card choices and card movement (Python effects.py choose_cards / discard_cards / deck_top) ------------

    /**
     * Card pick for the acting player (Python {@code choose_cards}). Cards the chooser can click where they sit (own
     * hand, in-play Pokemon and their attachments, the Stadium) glow in place; anything else opens the full-screen
     * browser. {@code minimum} null means exactly {@code count} (or every card if fewer exist); 0 makes it optional.
     */
    public List<CardEntity> chooseCards(List<CardEntity> cards, int count, Integer minimum, String prompt) {
        if (cards.isEmpty() || count <= 0) return List.of();
        int min = minimum == null ? Math.min(count, cards.size()) : minimum;
        String src = source == null ? null : source.entityId;
        if (visibleInPlace(cards, playerId)) return ops.pickEntities(playerId, src, cards, count, min, prompt);
        return ops.pickCards(playerId, src, cards, count, min, prompt);
    }

    /** Python {@code _visible_in_place}: every card sits where the chooser can click it directly. */
    static boolean visibleInPlace(List<CardEntity> cards, String playerId) {
        for (CardEntity card : cards) {
            com.ptcgo.revival.game.model.Entity top = card;
            while (top.parent instanceof CardEntity holder) top = holder;
            if (!(top.parent instanceof com.ptcgo.revival.game.model.PlayArea area)) return false;
            String name = area.areaName();
            if ("hand".equals(name)) {
                if (!java.util.Objects.equals(card.owningPlayerId, playerId) || top != card) return false;
            } else if (!("activePokemonArea".equals(name) || "bench".equals(name) || "activeStadium".equals(name))) {
                return false;
            }
        }
        return true;
    }

    /** Energy attached to a Pokemon (Python {@code attached_energies}). */
    public List<CardEntity> attachedEnergies(CardEntity pokemon) {
        return pokemon == null ? List.of() : Board.attachedEnergies(pokemon);
    }

    /** Top {@code count} cards of a deck, topmost first; a pure read with no wire messages (Python {@code deck_top}). */
    public List<CardEntity> deckTop(int count, String deckOwner) {
        List<CardEntity> deck = board().cardsIn(deckOwner, "deck");
        List<CardEntity> out = new ArrayList<>();
        for (int i = deck.size() - 1; i >= 0 && out.size() < count; i--) out.add(deck.get(i));
        return out;
    }

    /**
     * Moves cards to their owner's discard pile (Python {@code discard_cards} / {@code _move_to_public_pile}). Entering
     * a public pile introduces the card to the opponent, and to the owner too when it left a face-down zone (deck,
     * Prizes); each move rides one GroupedMove bracket. Passive redirects and discard shields are not ported.
     */
    public void discardCards(List<CardEntity> cards) {
        for (CardEntity card : cards) {
            String owner = card.owningPlayerId != null ? card.owningPlayerId : playerId;
            var pile = board().area(owner, "discard");
            if (pile == null) continue;
            String from = card.parent instanceof com.ptcgo.revival.game.model.PlayArea a ? a.areaName() : null;
            boolean ownerBlind = "deck".equals(from) || "prizePile".equals(from);
            int position = pile.children.size();
            if (!board().moveCard(card.entityId, pile.entityId)) continue;
            Map<String, Object> intro = Msgs.entityIntroduced(ops.gameId(), card);
            queue(intro, board().opponentOf(owner), GameSequence.GROUPED_MOVE);
            if (ownerBlind) queue(intro, owner, GameSequence.GROUPED_MOVE);
            queue(Msgs.entityMoved(board(), card.entityId, pile.entityId, position), null, GameSequence.GROUPED_MOVE);
        }
    }

    /**
     * Discards {@code count} Energy attached to {@code pokemon} (Python {@code discard_energy_from}): all matching
     * energies when {@code count} covers them, otherwise the acting player chooses which.
     */
    public List<CardEntity> discardEnergyFrom(CardEntity pokemon, int count, java.util.function.Predicate<CardEntity> pred,
                                              Integer minimum, String prompt) {
        List<CardEntity> energies = new ArrayList<>();
        for (CardEntity e : attachedEnergies(pokemon)) if (pred == null || pred.test(e)) energies.add(e);
        if (energies.isEmpty() || count <= 0) return List.of();
        List<CardEntity> picked = energies.size() <= count ? energies
                : chooseCards(energies, count, minimum == null ? count : minimum, prompt);
        discardCards(picked);
        return picked;
    }

    /** Draws cards for the acting player; the drawer's bracket carries the face intros, the opponent's does not. */
    public List<CardEntity> draw(int count) {
        List<Board.Moved> drawn = board().drawCards(playerId, count);
        if (drawn.isEmpty()) return List.of();
        ops.statAdd(playerId, "cardsdrawn", drawn.size());
        List<Map<String, Object>> moves = new ArrayList<>();
        List<CardEntity> cards = new ArrayList<>();
        for (Board.Moved d : drawn) {
            moves.add(Msgs.entityMoved(board(), d.entityId(), d.destinationId(), d.position()));
            cards.add(d.card());
        }
        for (CardEntity c : cards) queue(Msgs.entityIntroduced(ops.gameId(), c), playerId, GameSequence.DRAW);
        queue(new Msgs.NestedSequence(GameSequence.GROUPED_MOVE, moves), null, GameSequence.DRAW);
        return cards;
    }

    /** Targeted selection: the acting player picks up to {@code count} (at least {@code minimum}) candidates. */
    public List<CardEntity> pick(List<CardEntity> candidates, int count, int minimum, String prompt) {
        if (candidates.isEmpty() || count <= 0) return List.of();
        return ops.pickEntities(playerId, source == null ? null : source.entityId, candidates, count, minimum, prompt);
    }
}
