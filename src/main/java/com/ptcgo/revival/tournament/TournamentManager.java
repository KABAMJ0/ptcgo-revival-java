package com.ptcgo.revival.tournament;

import com.ptcgo.revival.db.TournamentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

/**
 * In-memory cache of the stored tournament definitions (Python {@code TournamentManager}). Handlers only read the
 * cache; call {@link #reload()} after a definition changes.
 */
public final class TournamentManager {
    private static final Logger LOG = LoggerFactory.getLogger(TournamentManager.class);

    private final TournamentRepository repo;
    private volatile List<TournamentDef> tournaments = List.of();

    public TournamentManager(TournamentRepository repo) {
        this.repo = repo;
        reload();
    }

    public void reload() {
        try {
            tournaments = repo.listTournaments(false).stream()
                    .map(r -> new TournamentDef(r.tournamentId(), r.definition(), r.enabled())).toList();
            LOG.info("[Tournaments] Loaded {} tournaments from DB", tournaments.size());
        } catch (Exception e) {
            LOG.error("[Tournaments] Failed to load tournaments", e);
            tournaments = List.of();
        }
    }

    public List<TournamentDef> all() {
        return tournaments;
    }

    /** Case-insensitive lookup; null when unknown. */
    public TournamentDef get(String tournamentId) {
        String wanted = tournamentId == null ? "" : tournamentId.toLowerCase(Locale.ROOT);
        for (TournamentDef t : tournaments) {
            if (t.tournamentId().toLowerCase(Locale.ROOT).equals(wanted)) return t;
        }
        return null;
    }

    /** Enabled tournaments the client should still display. */
    public List<TournamentDef> visible() {
        long now = System.currentTimeMillis();
        return tournaments.stream()
                .filter(t -> t.enabled() && !TournamentDef.STATE_HIDDEN.equals(t.state(now))).toList();
    }
}
