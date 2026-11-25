package com.ptcgo.revival.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Landing pages and maintenance banners (the {@code dynamic_pages} table the Python admin panel edits). */
public final class DynamicPageRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Database db;

    public DynamicPageRepository(Database db) {
        this.db = db;
    }

    public record Page(long id, String pageType, int sortOrder, ObjectNode content, boolean enabled) {}

    /** Pages ordered by (sort_order, id); the database columns are the source of truth over anything embedded in the JSON. */
    public List<Page> list(boolean enabledOnly) throws SQLException {
        List<Page> out = new ArrayList<>();
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT id, page_type, sort_order, content_json, enabled FROM dynamic_pages "
                        + (enabledOnly ? "WHERE enabled = 1 " : "") + "ORDER BY sort_order, id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                JsonNode content;
                try {
                    content = JSON.readTree(rs.getString(4));
                } catch (Exception e) {
                    continue; // unreadable page: skip rather than break the login screen
                }
                if (content instanceof ObjectNode obj) out.add(new Page(rs.getLong(1), rs.getString(2), rs.getInt(3), obj, rs.getBoolean(5)));
            }
        }
        return out;
    }

    public long insert(String pageType, int sortOrder, JsonNode content, boolean enabled) throws SQLException {
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO dynamic_pages (page_type, sort_order, content_json, enabled) VALUES (?,?,?,?)",
                java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, pageType);
            ps.setInt(2, sortOrder);
            ps.setString(3, content.toString());
            ps.setBoolean(4, enabled);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) {
                return k.next() ? k.getLong(1) : -1;
            }
        }
    }
}
