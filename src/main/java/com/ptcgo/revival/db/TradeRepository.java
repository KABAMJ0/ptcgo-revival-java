package com.ptcgo.revival.db;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The {@code trade_offers} table and the atomic card swap. Mirrors the trade section of ptcgo/database/economy_data.py. */
public final class TradeRepository {
    private static final Logger LOG = LoggerFactory.getLogger(TradeRepository.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Client-facing error keys (same strings as the Python server). */
    public static final String ERR_CREATING = "trade.errorCreatingLotDialog.body";
    public static final String ERR_NOT_ENOUGH = "trade.createLot.notEnoughCards.body";
    public static final String ERR_PURCHASING = "trade.errorPurchasingLotDialog.bodyFormat";

    public record Offer(long id, String offerId, String senderId, String senderName, String recipientId,
                        Map<String, Integer> offering, Map<String, Integer> requesting, String status,
                        String acceptedBy, String createdAt) {}

    /** Exactly one of offer / error is set. */
    public record Result(Offer offer, String error) {}

    /**
     * Query filter. {@code publicOnly} keeps recipient-less offers; null fields do not filter;
     * {@code status} defaults to "open" (null = any).
     */
    public record Filter(boolean publicOnly, String sender, String recipient, String excludeSender, String status) {
        public static Filter open() {
            return new Filter(false, null, null, null, "open");
        }

        public Filter withPublic() {
            return new Filter(true, sender, recipient, excludeSender, status);
        }

        public Filter withSender(String v) {
            return new Filter(publicOnly, v, recipient, excludeSender, status);
        }

        public Filter withRecipient(String v) {
            return new Filter(publicOnly, sender, v, excludeSender, status);
        }

        public Filter withExcludeSender(String v) {
            return new Filter(publicOnly, sender, recipient, v, status);
        }
    }

    private static final String SELECT = "SELECT t.id, t.offer_id, t.sender_id, COALESCE(NULLIF(a.screen_name,''), a.username, ''), "
            + "t.recipient_id, t.offering_json, t.requesting_json, t.status, t.accepted_by, t.created_at "
            + "FROM trade_offers t LEFT JOIN accounts a ON a.account_id = t.sender_id ";

    private final Database db;

    public TradeRepository(Database db) {
        this.db = db;
    }

    // ---- queries ---------------------------------------------------------------------------------

    private static String where(Filter f, List<String> args) {
        StringBuilder w = new StringBuilder(" WHERE 1=1");
        if (f.status() != null) {
            w.append(" AND t.status = ?");
            args.add(f.status());
        }
        if (f.publicOnly()) w.append(" AND t.recipient_id IS NULL");
        if (f.sender() != null && !f.sender().isEmpty()) {
            w.append(" AND t.sender_id = ?");
            args.add(f.sender());
        }
        if (f.recipient() != null && !f.recipient().isEmpty()) {
            w.append(" AND t.recipient_id = ?");
            args.add(f.recipient());
        }
        if (f.excludeSender() != null && !f.excludeSender().isEmpty()) {
            w.append(" AND t.sender_id != ?");
            args.add(f.excludeSender());
        }
        return w.toString();
    }

    public int count(Filter f) throws SQLException {
        List<String> args = new ArrayList<>();
        String w = where(f, args);
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM trade_offers t" + w)) {
            for (int i = 0; i < args.size(); i++) ps.setString(i + 1, args.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Newest first. */
    public List<Offer> list(Filter f, int offset, int limit) throws SQLException {
        List<String> args = new ArrayList<>();
        String w = where(f, args);
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                SELECT + w + " ORDER BY t.created_at DESC, t.id DESC LIMIT ? OFFSET ?")) {
            int i = 1;
            for (String a : args) ps.setString(i++, a);
            ps.setInt(i++, Math.max(0, limit));
            ps.setInt(i, Math.max(0, offset));
            return readAll(ps);
        }
    }

    /** Accepted offers an account was party to (as sender or accepter), newest first. */
    public List<Offer> history(String accountId, int limit) throws SQLException {
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                SELECT + " WHERE t.status = 'accepted' AND (t.sender_id = ? OR t.accepted_by = ?) "
                        + "ORDER BY t.created_at DESC, t.id DESC LIMIT ?")) {
            ps.setString(1, accountId);
            ps.setString(2, accountId);
            ps.setInt(3, limit);
            return readAll(ps);
        }
    }

    public Optional<Offer> get(String offerId) throws SQLException {
        try (Connection c = db.connect()) {
            return get(c, offerId, null);
        }
    }

    private static Optional<Offer> get(Connection c, String offerId, String status) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                SELECT + " WHERE t.offer_id = ?" + (status != null ? " AND t.status = ?" : ""))) {
            ps.setString(1, offerId);
            if (status != null) ps.setString(2, status);
            List<Offer> l = readAll(ps);
            return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
        }
    }

    private static List<Offer> readAll(PreparedStatement ps) throws SQLException {
        List<Offer> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Offer(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        counts(rs.getString(6)), counts(rs.getString(7)), rs.getString(8), rs.getString(9), rs.getString(10)));
            }
        }
        return out;
    }

    private static Map<String, Integer> counts(String json) {
        try {
            return json == null ? new LinkedHashMap<>() : JSON.readValue(json, new TypeReference<LinkedHashMap<String, Integer>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>(); // unreadable column reads as {} like the Python JSONEncodedDict
        }
    }

    // ---- mutations -------------------------------------------------------------------------------

    /** Creates an offer after checking the sender owns every offered card as tradable copies. */
    public Result create(String senderId, Map<String, Integer> offering, Map<String, Integer> requesting,
                         String recipientId) throws SQLException {
        offering = positive(offering);
        requesting = positive(requesting);
        if (offering.isEmpty() || requesting.isEmpty()) return new Result(null, ERR_CREATING);
        String offerId = UUID.randomUUID().toString();
        try (Connection c = db.connect()) {
            begin(c);
            try {
                if (!hasTradable(c, senderId, offering)) {
                    rollback(c);
                    return new Result(null, ERR_NOT_ENOUGH);
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO trade_offers (offer_id, sender_id, recipient_id, offering_json, requesting_json, status) "
                                + "VALUES (?,?,?,?,?, 'open')")) {
                    ps.setString(1, offerId);
                    ps.setString(2, senderId);
                    ps.setString(3, recipientId);
                    ps.setString(4, JSON.writeValueAsString(offering));
                    ps.setString(5, JSON.writeValueAsString(requesting));
                    ps.executeUpdate();
                }
                commit(c);
            } catch (Exception e) {
                rollback(c);
                if (e instanceof SQLException se) throw se;
                throw new SQLException(e);
            }
            return new Result(get(c, offerId, null).orElseThrow(), null);
        }
    }

    /** Sender cancels, or a private recipient declines. False if the offer is not open or the account is not a party. */
    public boolean cancel(String offerId, String accountId) throws SQLException {
        try (Connection c = db.connect()) {
            begin(c);
            try {
                Optional<Offer> o = get(c, offerId, "open");
                if (o.isEmpty() || !(accountId.equals(o.get().senderId()) || accountId.equals(o.get().recipientId()))) {
                    rollback(c);
                    return false;
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE trade_offers SET status = ? WHERE offer_id = ? AND status = 'open'")) {
                    ps.setString(1, accountId.equals(o.get().senderId()) ? "cancelled" : "declined");
                    ps.setString(2, offerId);
                    ps.executeUpdate();
                }
                commit(c);
                return true;
            } catch (SQLException e) {
                rollback(c);
                throw e;
            }
        }
    }

    /**
     * Atomically swaps the traded cards. One IMMEDIATE transaction: any failed precondition rolls back everything
     * (except that an offer whose sender no longer owns the offered cards is marked cancelled, as in Python).
     */
    public Result accept(String offerId, String accepterId) {
        try (Connection c = db.connect()) {
            begin(c);
            try {
                Optional<Offer> found = get(c, offerId, "open");
                if (found.isEmpty()) {
                    rollback(c);
                    return new Result(null, ERR_PURCHASING);
                }
                Offer o = found.get();
                if (o.senderId().equals(accepterId) || (o.recipientId() != null && !o.recipientId().equals(accepterId))) {
                    rollback(c);
                    return new Result(null, ERR_PURCHASING);
                }
                if (!hasTradable(c, o.senderId(), o.offering())) {
                    setStatus(c, offerId, "cancelled", null);
                    commit(c);
                    return new Result(null, ERR_PURCHASING);
                }
                if (!hasTradable(c, accepterId, o.requesting())) {
                    rollback(c);
                    return new Result(null, ERR_NOT_ENOUGH);
                }
                transfer(c, o.senderId(), accepterId, o.offering());
                transfer(c, accepterId, o.senderId(), o.requesting());
                setStatus(c, offerId, "accepted", accepterId);
                commit(c);
                return new Result(get(c, offerId, null).orElseThrow(), null);
            } catch (Exception e) {
                rollback(c);
                throw e;
            }
        } catch (Exception e) {
            LOG.error("[Economy] Trade accept failed for {}: {}", offerId, e.toString(), e);
            return new Result(null, ERR_PURCHASING);
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static Map<String, Integer> positive(Map<String, Integer> m) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (m != null) m.forEach((k, v) -> {
            if (v != null && v > 0) out.merge(k, v, Integer::sum);
        });
        return out;
    }

    /** Stored archetype id (case-insensitive match) of the account's row with enough tradable copies, else null. */
    private static String storedIdIfEnough(Connection c, String accountId, String guid, int needed) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT archetype_id, tradable_count FROM collection WHERE account_id = ? AND lower(archetype_id) = ?")) {
            ps.setString(1, accountId);
            ps.setString(2, guid.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) if (rs.getInt(2) >= needed) return rs.getString(1);
            }
        }
        return null;
    }

    private static boolean hasTradable(Connection c, String accountId, Map<String, Integer> wanted) throws SQLException {
        for (Map.Entry<String, Integer> e : wanted.entrySet()) {
            if (storedIdIfEnough(c, accountId, e.getKey(), e.getValue()) == null) return false;
        }
        return true;
    }

    private static void transfer(Connection c, String from, String to, Map<String, Integer> items) throws SQLException {
        for (Map.Entry<String, Integer> e : items.entrySet()) {
            int n = e.getValue();
            String stored = storedIdIfEnough(c, from, e.getKey(), n);
            if (stored == null) throw new SQLException("source lost cards mid-transfer: " + e.getKey());
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE collection SET tradable_count = tradable_count - ? WHERE account_id = ? AND archetype_id = ?")) {
                ps.setInt(1, n);
                ps.setString(2, from);
                ps.setString(3, stored);
                ps.executeUpdate();
            }
            String dst = null;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT archetype_id FROM collection WHERE account_id = ? AND lower(archetype_id) = ?")) {
                ps.setString(1, to);
                ps.setString(2, e.getKey().toLowerCase(Locale.ROOT));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) dst = rs.getString(1);
                }
            }
            if (dst != null) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE collection SET tradable_count = tradable_count + ? WHERE account_id = ? AND archetype_id = ?")) {
                    ps.setInt(1, n);
                    ps.setString(2, to);
                    ps.setString(3, dst);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO collection (account_id, archetype_id, tradable_count, nontradable_count) VALUES (?,?,?,0)")) {
                    ps.setString(1, to);
                    ps.setString(2, stored);
                    ps.setInt(3, n);
                    ps.executeUpdate();
                }
            }
        }
    }

    private static void setStatus(Connection c, String offerId, String status, String acceptedBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE trade_offers SET status = ?, accepted_by = ? WHERE offer_id = ?")) {
            ps.setString(1, status);
            ps.setString(2, acceptedBy);
            ps.setString(3, offerId);
            ps.executeUpdate();
        }
    }

    /** BEGIN IMMEDIATE: takes the write lock up front so concurrent trades serialise instead of failing on upgrade. */
    private static void begin(Connection c) throws SQLException {
        c.setAutoCommit(true);
        try (Statement st = c.createStatement()) {
            st.execute("BEGIN IMMEDIATE");
        }
    }

    private static void commit(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("COMMIT");
        }
    }

    private static void rollback(Connection c) {
        try (Statement st = c.createStatement()) {
            st.execute("ROLLBACK");
        } catch (SQLException ignored) {
            // no open transaction
        }
    }
}
