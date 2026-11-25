/**
 * Tournaments: async (run + leaderboard) tournaments and the legacy Events-scene live brackets.
 *
 * <h2>Wiring (not done here, to keep merges with parallel branches clean)</h2>
 * <pre>{@code
 * // Main, next to SocialModule.create(...); the match engine passes its own runner / matchmaker:
 * TournamentModule tournaments = TournamentModule.create(services,
 *         TournamentMatchRunner.UNAVAILABLE, AsyncTournamentMatchmaker.UNAVAILABLE);
 * handlers.addAll(tournaments.handlers());           // the PacketRouter groups
 * // ClientConnection.close(), next to SocialModule.onDisconnect(this):
 * com.ptcgo.revival.tournament.TournamentModule.onDisconnect(this);
 * }</pre>
 * No AuthHandler change is needed (nothing happens at login).
 *
 * <h2>What the match engine plugs in</h2>
 * <ul>
 *   <li>{@link com.ptcgo.revival.tournament.TournamentMatchRunner}: play one live-bracket game between two seats and
 *       call back with the winner exactly once.</li>
 *   <li>{@link com.ptcgo.revival.tournament.AsyncTournamentMatchmaker} +
 *       {@link com.ptcgo.revival.tournament.TournamentModule#recordAsyncGame}: queue an async-run game and report its
 *       result for both runs.</li>
 * </ul>
 */
package com.ptcgo.revival.tournament;
