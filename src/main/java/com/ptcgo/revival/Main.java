package com.ptcgo.revival;

import com.ptcgo.revival.auth.CasTickets;
import com.ptcgo.revival.catalog.Catalog;
import com.ptcgo.revival.db.AccountRepository;
import com.ptcgo.revival.db.PlayerRepository;
import com.ptcgo.revival.deck.DeckValidator;
import com.ptcgo.revival.deck.FormatManager;
import com.ptcgo.revival.deck.RulesDeckValidator;
import com.ptcgo.revival.deck.ThemeDecks;
import com.ptcgo.revival.db.Database;
import com.ptcgo.revival.http.HttpApp;
import com.ptcgo.revival.net.ClientRegistry;
import com.ptcgo.revival.net.GameServer;
import com.ptcgo.revival.packet.AuthHandler;
import com.ptcgo.revival.packet.CatalogHandler;
import com.ptcgo.revival.packet.DataSyncHandler;
import com.ptcgo.revival.packet.HandshakeHandler;
import com.ptcgo.revival.packet.PacketHandler;
import com.ptcgo.revival.packet.PacketRouter;
import com.ptcgo.revival.social.FriendMatchStarter;
import com.ptcgo.revival.social.GameChatRouter;
import com.ptcgo.revival.social.SocialModule;
import com.ptcgo.revival.packet.QuestHandler;
import com.ptcgo.revival.progression.Progression;
import com.ptcgo.revival.progression.Seasons;
import com.ptcgo.revival.db.QuestRepository;
import com.ptcgo.revival.db.VersusRepository;
import com.ptcgo.revival.packet.StaticDataHandler;
import com.ptcgo.revival.tls.TlsContexts;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public final class Main {
    public static void main(String[] args) throws Exception {
        Config cfg = new Config();
        if (args.length > 0 && args[0].equals("--gen-cert")) {
            String host = args.length > 1 ? args[1] : cfg.publicHost;
            TlsContexts.generate(cfg, host, true);
            System.out.println("Wrote " + cfg.keystorePath + " for " + host);
            return;
        }
        Database db = new Database(cfg);
        db.migrate();
        AccountRepository accounts = new AccountRepository(db);
        if (args.length > 1 && args[0].equals("--create-admin")) { // --create-admin <name> <password>
            System.out.println(accounts.create(args[1], args.length > 2 ? args[2] : "password", true).isPresent()
                    ? "Created admin " + args[1] : "Username already exists");
            return;
        }
        Catalog catalog = Catalog.load(cfg.catalogDir);
        ThemeDecks themes = ThemeDecks.load(cfg.catalogDir);
        // Server-wide card bans (an admin table in the Python server) are not ported: nothing is banned.
        FormatManager formats = FormatManager.load(cfg.catalogDir, catalog, themes, guid -> false, System::currentTimeMillis);
        DeckValidator validator = new RulesDeckValidator(catalog, formats, themes);
        Services services = new Services(cfg, accounts, new PlayerRepository(db), new CasTickets(), new ClientRegistry(),
                catalog, validator,
                new Progression(new QuestRepository(db), new VersusRepository(db), Seasons.loadBundled()));
        // Friend matches need the match engine; until then accepting a challenge fails cleanly for both players.
        com.ptcgo.revival.game.match.VersusModule versus = com.ptcgo.revival.game.match.VersusModule.create(services, themes);
        SocialModule social = SocialModule.create(services, versus.manager(), versus.manager());
        List<PacketHandler> groups = new ArrayList<>(List.of(new HandshakeHandler(), new AuthHandler(), new DataSyncHandler(),
                new CatalogHandler(), new StaticDataHandler(), new QuestHandler()));
        groups.addAll(social.handlers());
        groups.addAll(versus.handlers());
        PacketRouter router = new PacketRouter(groups);
        try (HttpApp http = new HttpApp(services);
             GameServer game = new GameServer(services, router, TlsContexts.serverContext(cfg))) {
            http.start(cfg.httpPort);
            game.start(cfg.tcpPort);
            new CountDownLatch(1).await();
        }
    }
}
