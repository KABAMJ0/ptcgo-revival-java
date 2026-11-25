package com.ptcgo.revival.shop;

import java.util.Map;

/** Shop price-map currencies and the wallet column each one is paid from. */
public final class Currency {
    private Currency() {}

    public static final long TRAINER_TOKENS = -1784319558L;
    public static final long GEMS = 2012906479L;
    public static final long EVENT_TICKETS = -992199324L;

    private static final Map<Long, String> WALLET_FIELDS = Map.of(TRAINER_TOKENS, "coins", GEMS, "gems", EVENT_TICKETS, "tickets");

    /** The wallet column for a currency id, or null when the shop cannot take that currency. */
    public static String walletField(long currency) {
        return WALLET_FIELDS.get(currency);
    }
}
