package com.ptcgo.revival.social;

import com.ptcgo.revival.packet.ClientContext;

import java.util.List;

/**
 * Resolves who receives an in-game chat line. Python: {@code GameSessionManager().get_session_by_player_id(account)}
 * and, if its {@code game_id} matches, {@code session.broadcast_packet(NotifyGameChat, ...)} to the match's viewers.
 * The match engine is not ported, so the default drops the message (as Python does when the sender is in no session).
 */
public interface GameChatRouter {
    /** Clients to deliver to, or an empty list if the account is not in the game with this id. */
    List<ClientContext> recipients(String senderAccountId, String gameId);

    GameChatRouter NONE = (sender, gameId) -> List.of();
}
