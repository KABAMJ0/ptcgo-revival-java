package com.ptcgo.revival.game.wire;

/** Valid StartSequence names (sequence command constructors in the client). */
public enum GameSequence {
    RECURSIVE_RETURN_TO_OWNERS_HAND("RecursiveReturnToOwnersHand"), REVEAL_MULLIGANS("RevealMulligans"),
    ALWAYS_REVEAL("AlwaysReveal"), REPLACE_ACTIVE("ReplaceActive"),
    SIMULTANEOUS_FLIP_THEN_ACTION("SimultaneousFlipThenAction"), TRANSFORM_ENTITY("TransformEntity"),
    WONDER_LOCK("WonderLock"), DEAL_INITIAL_HANDS("DealInitialHands"), DEVOLVE("Devolve"),
    DISCARD_RETREAT_COST("DiscardRetreatCost"), ROBO_SUBSTITUTE("RoboSubstitute"), FLIP_TO_WAKE_UP("FlipToWakeUp"),
    WITH_OPEN_PRIZE_CARDS("WithOpenPrizeCards"), ACTIVE_PLAYER_SET("ActivePlayerSet"), BURN_DAMAGE("BurnDamage"),
    BENCH_SIZE_MODIFIED("BenchSizeModified"), CREATE_LEGEND("CreateLegend"), DRAW("Draw"),
    FLIP_FOR_BURN("FlipForBurn"), FLIP_FOR_CONFUSION("FlipForConfusion"), USE_STADIUM_ABILITY("UseStadiumAbility"),
    PLAY_CARD("PlayCard"), HURT_FROM_CONFUSION("HurtFromConfusion"), INITIAL_COIN_FLIP("InitialCoinFlip"),
    INTRODUCE_INITIAL_POKEMON("IntroduceInitialPokemon"), GROUPED_MOVE("GroupedMove"), ATTACK("Attack"),
    OPPONENT_CHOOSING_TO_GO_FIRST("OpponentChoosingToGoFirst"),
    OPPONENT_PICKING_HEADS_OR_TAILS("OpponentPickingHeadsOrTails"), TRAINER_CARD("TrainerCard"),
    POKE_ABILITY("PokeAbility"), REMOVE_SPECIAL_CONDITION("RemoveSpecialCondition"),
    ADD_SPECIAL_CONDITION("AddSpecialCondition"), MULLIGAN("Mulligan"), DRAW_PRIZE_CARD("DrawPrizeCard"),
    POISON_DAMAGE("PoisonDamage"), DEAL_INITIAL_PRIZE_CARDS("DealInitialPrizeCards"), INITIAL_PICK("InitialPick"),
    KNOCKOUT("Knockout"), PLAY_ACTIVE("PlayActive"), PLAY_ENERGY("PlayEnergy"), PLAY_TOOL("PlayTool"),
    RETREAT("Retreat"), STADIUM_PRESENT("StadiumPresent"), TRAINER_PRESENT("TrainerPresent"),
    MOVE_FROM_BOTTOM_OF_DECK("MoveFromBottomOfDeck"), MOVE_FROM_MIDDLE_OF_DECK("MoveFromMiddleOfDeck"),
    MOVE_FROM_TOP_OF_DECK("MoveFromTopOfDeck"), ATTACH_TO_VUNION("AttachToVUnion"),
    CLOSE_PRIZE_PILE("ClosePrizePile"), DISMISS_ABILITY_SELECT("DismissAbilitySelect"),
    PARALLEL_SEQUENCE("ParallelSequence"), REVEAL_AND_SKIP_MOVE("RevealAndSkipMove"),
    SERIAL_SEQUENCE("SerialSequence"), VUNION_BREAK_SEQUENCE("VUnionBreakSequence"),
    CREATE_VUNION("CreateVUnion"), ACTIVE_CARD_AND_ATTACHMENTS_SHUFFLED("ActiveCardAndAttachmentsShuffled"),
    HAND_SHUFFLED_AND_MOVED_TO_DECK("HandShuffledAndMovedToDeck"), TRANSFORM_SWAP("TransformSwap"),
    EVOLVE("Evolve");

    private final String wire;

    GameSequence(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    @Override
    public String toString() {
        return wire;
    }
}
