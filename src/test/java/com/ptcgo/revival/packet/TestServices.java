package com.ptcgo.revival.packet;

import com.ptcgo.revival.Config;
import com.ptcgo.revival.Services;
import com.ptcgo.revival.auth.CasTickets;
import com.ptcgo.revival.db.AccountRepository;
import com.ptcgo.revival.db.Database;
import com.ptcgo.revival.net.ClientRegistry;
import java.nio.file.Files;
import java.nio.file.Path;

final class TestServices {
    private TestServices() {}

    static Services create(Config cfg) throws Exception {
        Path tmp = Files.createTempFile("ptcgo-test", ".db");
        tmp.toFile().deleteOnExit();
        Database db = new Database("jdbc:sqlite:" + tmp);
        db.migrate();
        return new Services(cfg, new AccountRepository(db), new CasTickets(), new ClientRegistry());
    }
}
