package com.ptcgo.revival.game.wire;

import java.util.Map;

/** Client attribute ids and enum values used by the match engine (ptcgo/game/attributes.py). */
public final class Attr {
    private Attr() {}

    public static final int ARCHETYPE_ID = 10000;
    public static final int EXPANSION = 10020;
    public static final int NAME = 10140;
    public static final int SCREEN_NAME = 10360;
    public static final int CARD_TYPE = 200300;
    public static final int TRAINER_TYPE = 200270;
    public static final int SET_CACHE_KEY = 200580;
    public static final int HP = 200490;
    public static final int STAGE = 200540;
    public static final int POKEMON_TYPES = 200570;
    public static final int WEAKNESS_TYPES = 200590;
    public static final int WEAKNESS_OPERATOR = 200660;
    public static final int WEAKNESS_AMOUNT = 200820;
    public static final int RESISTANCE_TYPES = 200600;
    public static final int RESISTANCE_OPERATOR = 200650;
    public static final int RESISTANCE_AMOUNT = 200830;
    public static final int RETREAT_COST = 200800;
    public static final int PIE_ABILITIES = 200740;
    public static final int SPECIAL_CONDITIONS = 200340;
    public static final int ENERGY_INFO = 201040;
    public static final int IS_SPECIAL_ENERGY = 200970;
    public static final int EVOLUTION_LOGIC_NAME = 200630;
    public static final int EVOLUTION_LOGIC_FROM = 200640;
    public static final int ATTACK_SOURCES = 201870;
    public static final int AREA_SLOTS = 201920;
    public static final int AREA_EMPTY_SLOTS = 201860;
    public static final int SELECTED_COIN = 200670;
    public static final int SELECTED_SLEEVE = 200680;
    public static final int SELECTED_DECK_BOX = 200690;
    public static final int HAS_GX_TOKEN = 202130;
    public static final int HAS_VSTAR_TOKEN = 203300;

    // CardType
    public static final int CT_POKEMON = 0, CT_LEGEND_HALF = 1, CT_TRAINER = 2, CT_ENERGY = 3;
    // PokemonStage
    public static final int STAGE_BASIC = 0, STAGE_1 = 1, STAGE_2 = 2, STAGE_RESTORED = 3, STAGE_LEVELUP = 4,
            STAGE_LEGEND = 5, STAGE_BREAK = 6, STAGE_VMAX = 7, STAGE_VUNION = 8, STAGE_VSTAR = 9;
    // TrainerType
    public static final int TT_ITEM = 0, TT_STADIUM = 1, TT_SUPPORTER = 2, TT_TM = 3, TT_TOOL = 4, TT_TOOL_F = 5;
    // PokemonTypes
    public static final int TYPE_NO_COLOR = 0, TYPE_COLORLESS = 1;

    /** Client Cake.enums.PokemonTypes member names (cost keys coerce by NAME only). */
    public static final Map<Integer, String> TYPE_NAMES = Map.ofEntries(
            Map.entry(0, "NoColor"), Map.entry(1, "Colorless"), Map.entry(2, "Darkness"), Map.entry(3, "Dragon"),
            Map.entry(4, "Fairy"), Map.entry(5, "Fighting"), Map.entry(6, "Fire"), Map.entry(7, "Grass"),
            Map.entry(8, "Lightning"), Map.entry(9, "Metal"), Map.entry(10, "Psychic"), Map.entry(11, "Water"),
            Map.entry(12, "Strong"), Map.entry(13, "Herbal"));

    public static int typeByName(String name) {
        for (var e : TYPE_NAMES.entrySet()) if (e.getValue().equals(name)) return e.getKey();
        return Integer.parseInt(name);
    }

    // Special conditions: attr 200340 arrays coerce by NAME on the client.
    public static final String ASLEEP = "Asleep", BURNED = "Burned", CONFUSED = "Confused", PARALYZED = "Paralyzed",
            POISONED = "Poisoned";
}
