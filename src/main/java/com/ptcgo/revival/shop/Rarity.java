package com.ptcgo.revival.shop;

import java.util.List;

/** Rarity and card-type codes (ptcgo/game/attributes.py Rarities / CardType) and the card attribute ids. */
public final class Rarity {
    private Rarity() {}

    public static final int COMMON = 0, UNCOMMON = 1, RARE = 2, RARE_HOLO = 3, RARE_HOLO_EX = 4, RARE_HOLO_VSTAR = 8,
            ACE = 15, RARE_ULTRA = 16, RARE_SECRET = 17, RARE_RAINBOW = 18, BREAK_RARE = 27, AMAZING = 30,
            RARE_RADIANT = 31;
    public static final int CARD_TYPE_ENERGY = 3;

    public static final int ATTR_CARD_TYPE = 200300;
    public static final int ATTR_RARITY = 200550;
    public static final int ATTR_IS_SPECIAL_ENERGY = 200970;

    /** Names by code, as the Python Rarities enum names them (foil_rules.json refers to these). */
    private static final List<String> NAMES = List.of("Common", "Uncommon", "Rare", "RareHolo", "RareHoloEX",
            "RareHoloGX", "RareHoloV", "RareHoloVMAX", "RareHoloVSTAR", "ChrRareHolo", "ChrRareHoloV", "ChrRareSecret",
            "ChrRareUltra", "RarePrime", "Legendary", "Ace", "RareUltra", "RareSecret", "RareRainbow", "DisplayNone",
            "medium", "PromoExclusive", "RarePromo", "Token", "ExtraRare", "VeryRare", "Unassigned", "BreakRare",
            "Shining", "Prism", "Amazing", "RareRadiant", "RareDitto");

    public static String name(int code) {
        return code >= 0 && code < NAMES.size() ? NAMES.get(code) : "UNSET";
    }
}
