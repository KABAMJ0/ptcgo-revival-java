package com.ptcgo.revival.game.wire;

/**
 * Every selection-node Kind the client's UI command factory recognizes (PieSelectionNodeCommandFactory.Start()).
 * A selection offer's Kind (SelectionMessage targetType, or a TargetInformation's name) picks the UI command that
 * drives highlights, drag/drop gating and drop-zone glow. {@link #ENTITY_LIST} is NOT in the factory table.
 */
public enum SelectionKind {
    ABILITY("Ability"),
    OUT_OF_PLAY("OutOfPlay"),
    ABILITY_SELECTION("AbilitySelection"),
    RETREAT_NEW_ACTIVE("RetreatNewActiveTargetInformation"),
    ACTIVE_POKEMON("ActivePokemonTargetInformation"),
    KNOCKOUT_POKEMON("KnockoutPokemonTargetInformation"),
    INITIAL_BENCHED("InitialBenchedTargetInformation"),
    PRIZE_CARD("PrizeCardTargetInformation"),
    RETREAT_COST_ENTITY_LIST("RetreatCostEntityListTargetInformation"),
    ENERGY_COST_ENTITY_LIST("EnergyCostEntityListTargetInformation"),
    COIN_FLIP_CHOICE("CoinFlipChoice"),
    GO_FIRST_CHOICE("GoFirstChoice"),
    ORIENTATION_CUSTOM_CHOICE("OrientationCustomChoiceTargetInformation"),
    CAKE_ATTACK_CUSTOM_CHOICE("CakeAttackCustomChoiceTargetInformation"),
    CUSTOM_CHOICE_AS_ABILITY_SELECT("CustomChoiceAsAbilitySelectTargetInformation"),
    CUSTOM_CHOICE_AS_ABILITY_SELECT_TAG_BONUS("CustomChoiceAsAbilitySelectTargetInformationWithTAGBonus"),
    PARAMETERIZED_LOC_CUSTOM_CHOICE_REQUIRED("ParameterizedLocCustomChoiceRequired"),
    CAKE_CUSTOM_CHOICE_SHOW_REQUIRED("CakeCustomChoiceShowRequiredTargetInformation"),
    COMPOSITE_REVEAL("CompositeRevealEntityListTargetInformation"),
    OR_COMPOSITE_REVEAL("OrCompositeRevealEntityListTargetInformation"),
    ANY_COMPOSITE_REVEAL("AnyCompositeRevealEntityListTargetInformation"),
    AND_COMPOSITE_REVEAL("AndCompositeRevealEntityListTargetInformation"),
    EXCLUSIVE_MULTI_COMPOSITE_REVEAL("ExclusiveMultiCompositeRevealEntityListTargetInformation"),
    REVEAL_ASSOCIATED("RevealAssociatedEntityListTargetInformation"),
    COMPOSITE_REVEAL_ASSOCIATED("CompositeRevealAssociatedEntityListTargetInformation"),
    ALIGNED_ENTITY_LIST("AlignedEntityListTargetInformation"),
    MULTI_SELECT_ENTITY_LIST("MultiSelectEntityListTargetInformation"),
    SLOT_ASSOCIATED_ENTITY_LIST("SlotAssociatedEntityListTargetInformation"),
    ENTITY_LIST("EntityListTargetInformation");

    private final String wire;

    SelectionKind(String wire) {
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
