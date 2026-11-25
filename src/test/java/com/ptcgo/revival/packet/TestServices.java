package com.ptcgo.revival.packet;

import com.ptcgo.revival.Config;
import com.ptcgo.revival.Services;
import com.ptcgo.revival.auth.CasTickets;
import com.ptcgo.revival.catalog.Catalog;
import com.ptcgo.revival.db.AccountRepository;
import com.ptcgo.revival.db.PlayerRepository;
import com.ptcgo.revival.deck.DeckValidator;
import com.ptcgo.revival.db.Database;
import com.ptcgo.revival.db.QuestRepository;
import com.ptcgo.revival.db.VersusRepository;
import com.ptcgo.revival.net.ClientRegistry;
import com.ptcgo.revival.progression.Progression;
import com.ptcgo.revival.progression.Seasons;
import java.nio.file.Files;
import java.nio.file.Path;

final class TestServices {
    private TestServices() {}

    static Services create(Config cfg) throws Exception {
        Path tmp = Files.createTempFile("ptcgo-test", ".db");
        tmp.toFile().deleteOnExit();
        Database db = new Database("jdbc:sqlite:" + tmp);
        db.migrate();
        return new Services(cfg, new AccountRepository(db), new PlayerRepository(db), new CasTickets(),
                new ClientRegistry(), Catalog.empty(), DeckValidator.PERMISSIVE,
                new Progression(new QuestRepository(db), new VersusRepository(db), Seasons.loadBundled(),
                        new com.ptcgo.revival.db.DailyLoginRepository(db, com.ptcgo.revival.progression.DailyRewards.loadBundled(),
                                Catalog.empty(), java.time.Instant::now, new java.util.Random(1))),
                null);
    }
}
