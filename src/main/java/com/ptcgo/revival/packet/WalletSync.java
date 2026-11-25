package com.ptcgo.revival.packet;

import java.sql.SQLException;

/** Public bridge to the unsolicited wallet / collection pushes other modules (tournaments) need. */
public final class WalletSync {
    private WalletSync() {}

    /** {@code CurrentWallet} on request id 0 with the stored balances. */
    public static void pushWallet(ClientContext c) throws SQLException {
        if (c.account() == null) return;
        c.send("CurrentWallet", DataSyncHandler.walletPayload(c.services().players().wallet(c.account().accountId())), 0);
    }

    /** {@code CollectionCountFound} on request id 0 (after cards were granted). */
    public static void pushCollection(ClientContext c) throws SQLException {
        if (c.account() == null) return;
        c.send("CollectionCountFound", java.util.Map.of("collectionCountList", DataSyncHandler.collectionPayload(c)), 0);
    }
}
