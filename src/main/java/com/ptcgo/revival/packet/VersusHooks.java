package com.ptcgo.revival.packet;

import com.fasterxml.jackson.databind.JsonNode;
import com.ptcgo.revival.Services;
import com.ptcgo.revival.db.PlayerRepository;
import com.ptcgo.revival.db.QuestRepository;
import com.ptcgo.revival.game.engine.MatchHooks;
import com.ptcgo.revival.game.match.AvatarItems;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Wires the match engine's persistence hooks to the repositories (wallet, ladder points, quests, account refresh). */
public final class VersusHooks implements MatchHooks {
    private static final Logger LOG = LoggerFactory.getLogger(VersusHooks.class);
    private static final Object WALLET_LOCK = new Object();
    private final Services services;

    public VersusHooks(Services services) {
        this.services = services;
    }

    @Override
    public int rating(String accountId) {
        try {
            return 1000 + services.progression().versus().progress(accountId, "").allTimePoints();
        } catch (SQLException e) {
            LOG.error("rating lookup failed for {}", accountId, e);
            return 1000;
        }
    }

    @Override
    public List<String> avatarItems(String accountId) {
        try {
            List<JsonNode> decks = new ArrayList<>();
            for (PlayerRepository.DeckRow d : services.players().decks(accountId, true)) if (d.deckData() != null) decks.add(d.deckData());
            return AvatarItems.fromAvatarDecks(decks);
        } catch (SQLException e) {
            LOG.error("avatar lookup failed for {}", accountId, e);
            return null;
        }
    }

    @Override
    public void persistResult(String accountId, int coins, boolean won) {
        try {
            if (coins > 0) {
                synchronized (WALLET_LOCK) {
                    PlayerRepository.Wallet w = services.players().wallet(accountId);
                    services.players().updateWallet(accountId, (long) w.coins() + coins, w.gems(), w.tickets());
                }
            }
            services.progression().versus().awardMatchPoints(accountId, won, services.progression().seasons().active());
        } catch (SQLException e) {
            throw new IllegalStateException("could not persist the match result for " + accountId, e);
        }
    }

    @Override
    public QuestCredit creditQuests(String accountId, String gameId, Map<String, Integer> stats, boolean won, double startedAt) {
        try {
            QuestRepository.MatchCredit r = services.progression().quests().creditMatch(accountId, gameId, stats, won, startedAt);
            List<JsonNode> rewards = new ArrayList<>();
            r.rewards().forEach(rewards::add);
            return new QuestCredit(r.completedQuestsAndXpTotal(), r.progressedQuests(), rewards);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Map<String, Object> walletPayload(String accountId) {
        try {
            return DataSyncHandler.walletPayload(services.players().wallet(accountId));
        } catch (SQLException e) {
            LOG.error("wallet refresh failed for {}", accountId, e);
            return null;
        }
    }

    @Override
    public Map<String, Object> accountPayload(String accountId) {
        try {
            return services.accounts().findById(accountId).map(a -> {
                try {
                    return AccountAttributes.accountObject(services, a);
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }).orElse(null);
        } catch (SQLException e) {
            LOG.error("account refresh failed for {}", accountId, e);
            return null;
        }
    }
}
