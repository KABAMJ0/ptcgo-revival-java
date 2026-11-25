package com.ptcgo.revival.packet;

import com.ptcgo.revival.Services;
import com.ptcgo.revival.db.Account;
import com.ptcgo.revival.progression.Quests;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The full SerializableAccount attribute list. AccountUpdated replaces the client's whole set, so every sender
 * must include everything (ptcgo/game/progression/account.py: build_account_attributes).
 */
public final class AccountAttributes {
    static final int ACCOUNT_SETTINGS = 10230;
    static final int SCREEN_NAME = 10360;
    static final int FRIEND_CHAT_MODE = 201510;
    static final int FRIEND_MODE = 201520;
    static final int FRIEND_TRADE_MODE = 201530;
    static final int GAME_CHAT_MODE = 201540;
    static final int PUBLIC_CHAT_MODE = 201550;
    static final int SHOPPING_MODE = 201560;
    static final int TRADE_MODE = 201570;
    static final int PRIVATE_MESSAGING_MODE = 201580;
    static final int DECK_SHARE_MODE = 201850;
    static final int QUEST_AFFINITY_XP = 201790;
    static final int QUESTS_ENABLED = 201800;
    static final int SEASON_POINTS = 201810;
    static final int ALL_TIME_SEASON_POINTS = 201840;

    private AccountAttributes() {}

    public static List<Map<String, Object>> build(Services s, Account account) throws SQLException {
        String id = account.accountId();
        Account fresh = s.accounts().findById(id).orElse(account);
        var progress = s.progression().versus().progress(id, s.progression().seasons().activeId());
        var quests = s.progression().quests().accountState(id);

        List<Map<String, Object>> l = new ArrayList<>();
        l.add(attr(ACCOUNT_SETTINGS, s.players().accountSettings(id)));
        l.add(attr(SCREEN_NAME, fresh.displayName()));
        l.add(attr(FRIEND_CHAT_MODE, "OpenChat"));
        l.add(attr(FRIEND_MODE, "Open"));
        l.add(attr(FRIEND_TRADE_MODE, "Free"));
        l.add(attr(GAME_CHAT_MODE, "OpenChat"));
        l.add(attr(PUBLIC_CHAT_MODE, "OpenChat"));
        l.add(attr(SHOPPING_MODE, "Open"));
        l.add(attr(TRADE_MODE, "Free"));
        l.add(attr(PRIVATE_MESSAGING_MODE, "OpenChat"));
        l.add(attr(DECK_SHARE_MODE, "Everybody"));
        l.add(attr(SEASON_POINTS, progress.points()));
        l.add(attr(ALL_TIME_SEASON_POINTS, progress.allTimePoints()));
        l.add(attr(QUESTS_ENABLED, quests.enabled()));
        l.add(attr(QUEST_AFFINITY_XP, Quests.xpAttribute(quests.affinityXp())));
        return l;
    }

    /** The account object AccountUpdated/AuthenticationSuccessful carry. */
    public static Map<String, Object> accountObject(Services s, Account account) throws SQLException {
        Map<String, Object> acct = new LinkedHashMap<>();
        acct.put("username", account.username());
        acct.put("accountID", account.accountId());
        acct.put("attributes", build(s, account));
        return acct;
    }

    static Map<String, Object> attr(int name, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("value", value);
        return m;
    }
}
