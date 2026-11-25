package com.ptcgo.revival;

import com.ptcgo.revival.auth.CasTickets;
import com.ptcgo.revival.catalog.Catalog;
import com.ptcgo.revival.db.Account;
import com.ptcgo.revival.db.AccountRepository;
import com.ptcgo.revival.db.PlayerRepository;
import com.ptcgo.revival.deck.DeckValidator;
import com.ptcgo.revival.net.ClientRegistry;
import com.ptcgo.revival.progression.Progression;
import com.ptcgo.revival.shop.StarterContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Optional;

/**
 * Shared server-wide state handed to connections and handlers. {@code starter} may be null (tests): new
 * accounts then get no starter decks or packs.
 */
public record Services(Config config, AccountRepository accounts, PlayerRepository players, CasTickets tickets,
                       ClientRegistry registry, Catalog catalog, DeckValidator deckValidator,
                       Progression progression, StarterContent starter) {
    private static final Logger LOG = LoggerFactory.getLogger(Services.class);

    /** Creates an account and grants its starter decks and packs; empty if the username is taken. */
    public Optional<Account> createAccount(String username, String password, boolean admin) throws SQLException {
        Optional<Account> created = accounts.create(username, password, admin);
        if (created.isPresent() && starter != null) {
            try {
                starter.grant(created.get().accountId());
            } catch (SQLException | RuntimeException e) {
                LOG.error("Starter content grant failed for new account {}", username, e); // the account still exists
            }
        }
        return created;
    }
}
