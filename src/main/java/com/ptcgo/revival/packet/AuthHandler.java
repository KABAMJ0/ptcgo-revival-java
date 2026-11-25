package com.ptcgo.revival.packet;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.db.Account;
import com.ptcgo.revival.db.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Login: GAS token (username/password, auto-registering), CAS ticket (issued by the HTTP login page),
 * and guest/mobile logins when PTCGO_ALLOW_GUESTS=1. Sends the post-login message sequence.
 *
 * <p>Not ported yet: versus season, daily-login rewards, quest configuration, reconnect to a live game,
 * friend presence broadcast, and starter-content grants for new accounts.
 */
public final class AuthHandler implements PacketHandler {
    private static final Logger LOG = LoggerFactory.getLogger(AuthHandler.class);
    static final String GUESTS_DISABLED = "Guest accounts are disabled. Please sign in.";
    static final String ALREADY_ONLINE = "The account you are trying to login is already online.";

    // Attribute ids (ptcgo/game/attributes.py)
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

    @Override
    public Map<String, MessageHandler> handlers() {
        return Map.of(
                "StartAuthentication", AuthHandler::startAuthentication,
                "AuthenticateGASAuthToken", AuthHandler::gasToken,
                "AuthenticateGASGuest", AuthHandler::gasGuest,
                "AuthenticateGASMobile", AuthHandler::gasMobile,
                "AuthenticateCASTicket", AuthHandler::casTicket);
    }

    private static String text(JsonNode v, String field, String def) {
        return v != null && v.hasNonNull(field) ? v.get(field).asText() : def;
    }

    private static void startAuthentication(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        String type = text(v, "authType", "Unknown");
        LOG.info("[TCP] [{}] Authentication started. Type: {}", c.remoteAddress(), type);
        if (type.equals("DeviceID")) {
            if (!c.config().allowGuests) {
                fail(c, rid, GUESTS_DISABLED);
                return;
            }
            Account a = getOrCreate(c, "guest_" + c.remoteAddress().getAddress().getHostAddress(), "guest_password");
            success(c, rid, a);
            return;
        }
        c.send("RequestAuthToken", Map.of(), rid);
    }

    private static void gasToken(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        String username = text(v, "userID", "");
        String password = text(v, "token", "");
        AccountRepository repo = c.services().accounts();
        Optional<Account> existing = repo.findByUsername(username);
        if (existing.isEmpty()) {
            LOG.info("[TCP] [{}] Auto-registering new user: {}", c.remoteAddress(), username);
            existing = repo.create(username, password, false);
            if (existing.isEmpty()) {
                fail(c, rid, "Could not create account. Please try again.");
                return;
            }
        }
        Account a = existing.get();
        // A banned account is refused before the password check so the reason can't be probed.
        if (a.banned()) {
            LOG.warn("[TCP] [{}] Banned account '{}' attempted login", c.remoteAddress(), username);
            fail(c, rid, "This account is suspended.");
            return;
        }
        if (!AccountRepository.verifyPassword(a.passwordHash(), password)) {
            LOG.warn("[TCP] [{}] Failed login attempt for user: {}", c.remoteAddress(), username);
            fail(c, rid, "Incorrect password. Please try again.");
            return;
        }
        success(c, rid, a);
    }

    private static void gasGuest(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        if (!c.config().allowGuests) {
            fail(c, rid, GUESTS_DISABLED);
            return;
        }
        Account a = getOrCreate(c, text(v, "uniqueID", "guest_unknown"), "guest_password");
        if (a.banned()) {
            fail(c, rid, "This account is suspended.");
            return;
        }
        success(c, rid, a);
    }

    private static void gasMobile(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        if (!c.config().allowGuests) {
            fail(c, rid, "Mobile login is disabled. Please sign in.");
            return;
        }
        success(c, rid, getOrCreate(c, text(v, "mobileID", "mobile_unknown"), "mobile_password"));
    }

    private static void casTicket(ClientContext c, JsonNode v, int rid, int f) throws SQLException {
        String username = text(v, "accountName", "");
        String ticket = text(v, "serviceTicket", "");
        if (!c.services().tickets().consume(ticket, username)) {
            LOG.warn("[TCP] [{}] Invalid CAS ticket for user: {}", c.remoteAddress(), username);
            fail(c, rid, "Invalid CAS Ticket.");
            return;
        }
        Optional<Account> a = c.services().accounts().findByUsername(username);
        if (a.isEmpty()) {
            fail(c, rid, "Account sync error.");
            return;
        }
        if (a.get().banned()) {
            fail(c, rid, "This account is suspended.");
            return;
        }
        success(c, rid, a.get());
    }

    private static Account getOrCreate(ClientContext c, String username, String password) throws SQLException {
        AccountRepository repo = c.services().accounts();
        Optional<Account> a = repo.findByUsername(username);
        if (a.isPresent()) return a.get();
        return repo.create(username, password, false)
                .or(() -> {
                    try {
                        return repo.findByUsername(username);
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .orElseThrow();
    }

    static void success(ClientContext c, int rid, Account account) {
        String id = account.accountId();
        var registry = c.services().registry();
        // Claim the account before doing anything else so two concurrent logins can't both pass the guard.
        if (!registry.beginLogin(id) || registry.find(id).filter(o -> o != c).isPresent()) {
            registry.endLogin(id);
            LOG.warn("[TCP] [{}] Prevented concurrent login for '{}'", c.remoteAddress(), account.username());
            fail(c, rid, ALREADY_ONLINE);
            return;
        }
        try {
            c.authenticated(account);
            LOG.info("[TCP] [{}] User '{}' authenticated.", c.remoteAddress(), account.username());

            Map<String, Object> acct = new LinkedHashMap<>();
            acct.put("username", account.username());
            acct.put("accountID", id);
            acct.put("attributes", accountAttributes(account));
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("account", acct);
            ok.put("sessionID", c.sessionId());
            ok.put("supplementaryInfo", null);
            c.send("AuthenticationSuccessful", ok, rid);

            c.send("EulaSuccessful", Map.of("version", 1), 0);
            c.send("CohortsForAccount", Map.of("cohorts", Map.of(), "redirectScenes", Map.of(),
                    "cohortFeatures", List.of()), 0);
            c.send("PlayerNotInGame", Map.of(), 0);
            c.send("NetworkStatusIndicatorConfiguration",
                    Map.of("pingWarningThreshold", 500, "pingErrorThreshold", 1000), 0);
            c.send("DefaultTrainerChallengeDeckMessage", Map.of("defaultDeckName", "Mental Might"), 0);
            c.send("AccountPropertiesUpdated", Map.of("accountID", id, "attributes", List.of(
                    attr(FRIEND_CHAT_MODE, "OpenChat"), attr(FRIEND_MODE, "Open"), attr(FRIEND_TRADE_MODE, "Free"),
                    attr(GAME_CHAT_MODE, "OpenChat"), attr(PUBLIC_CHAT_MODE, "OpenChat"),
                    attr(SHOPPING_MODE, "Open"), attr(TRADE_MODE, "Free"),
                    attr(PRIVATE_MESSAGING_MODE, "OpenChat"), attr(DECK_SHARE_MODE, "Everybody"))), 0);
            c.send("DynamicVersions", Map.of("versionData", dynamicVersions()), 0);
        } finally {
            registry.endLogin(id);
        }
    }

    /** AccountUpdated replaces the client's whole attribute set, so this must stay complete. */
    static List<Map<String, Object>> accountAttributes(Account a) {
        List<Map<String, Object>> l = new ArrayList<>();
        l.add(attr(ACCOUNT_SETTINGS, a.settings()));
        l.add(attr(SCREEN_NAME, a.displayName()));
        l.add(attr(FRIEND_CHAT_MODE, "OpenChat"));
        l.add(attr(FRIEND_MODE, "Open"));
        l.add(attr(FRIEND_TRADE_MODE, "Free"));
        l.add(attr(GAME_CHAT_MODE, "OpenChat"));
        l.add(attr(PUBLIC_CHAT_MODE, "OpenChat"));
        l.add(attr(SHOPPING_MODE, "Open"));
        l.add(attr(TRADE_MODE, "Free"));
        l.add(attr(PRIVATE_MESSAGING_MODE, "OpenChat"));
        l.add(attr(DECK_SHARE_MODE, "Everybody"));
        // Versus season and quest state are not ported: neutral defaults.
        l.add(attr(SEASON_POINTS, 0));
        l.add(attr(ALL_TIME_SEASON_POINTS, 0));
        l.add(attr(QUESTS_ENABLED, false));
        l.add(attr(QUEST_AFFINITY_XP, 0));
        return l;
    }

    private static Map<String, Object> attr(int name, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("value", value);
        return m;
    }

    private static Map<String, String> dynamicVersions() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : List.of("major_version_number", "content_data_version", "cachedDisplayVersion",
                "cachedCollectionVersion", "androidpatchversion", "iospatchversion", "lastUpdatedAtVersion",
                "opponentsDataVersion", "progressionDataVersion", "cachedDarkenDataVersion", "LatestMacPatcherVersion",
                "LatestWindowsPatcherVersion", "LatestMacClientVersion", "LatestWindowsClientVersion")) {
            m.put(k, "1");
        }
        m.put("minor_version_number", "0");
        m.put("content_data_version_id", "0");
        return m;
    }

    static void fail(ClientContext c, int rid, String reason) {
        c.send("AuthenticationFailed", Map.of("reason", Map.of("token", reason, "bundle", Map.of("en", reason))), rid);
    }
}
