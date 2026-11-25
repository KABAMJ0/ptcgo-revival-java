package com.ptcgo.revival.db;

import java.util.Map;

/** Row of the {@code accounts} table (same schema as the Python server's SQLAlchemy model). */
public record Account(long id, String accountId, String username, String passwordHash, String screenName,
                      boolean admin, boolean banned, Map<String, Object> settings, String createdAt) {
    public String displayName() {
        return screenName != null && !screenName.isBlank() ? screenName : username;
    }
}
