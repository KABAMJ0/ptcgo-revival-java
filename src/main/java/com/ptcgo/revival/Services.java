package com.ptcgo.revival;

import com.ptcgo.revival.auth.CasTickets;
import com.ptcgo.revival.db.AccountRepository;
import com.ptcgo.revival.net.ClientRegistry;

/** Shared server-wide state handed to connections and handlers. */
public record Services(Config config, AccountRepository accounts, CasTickets tickets, ClientRegistry registry) {}
