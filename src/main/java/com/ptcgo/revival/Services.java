package com.ptcgo.revival;

import com.ptcgo.revival.auth.CasTickets;
import com.ptcgo.revival.catalog.Catalog;
import com.ptcgo.revival.db.AccountRepository;
import com.ptcgo.revival.db.PlayerRepository;
import com.ptcgo.revival.deck.DeckValidator;
import com.ptcgo.revival.net.ClientRegistry;
import com.ptcgo.revival.progression.Progression;

/** Shared server-wide state handed to connections and handlers. */
public record Services(Config config, AccountRepository accounts, PlayerRepository players, CasTickets tickets,
                       ClientRegistry registry, Catalog catalog, DeckValidator deckValidator,
                       Progression progression) {}
