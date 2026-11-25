package com.ptcgo.revival.game.model;

import com.ptcgo.revival.game.wire.Attr;

/** A pile / zone on the board (PlayArea): deck, hand, bench, activePokemonArea, discard, prizePile, lostZone, ... */
public final class PlayArea extends Entity {
    public PlayArea(String id, String areaName, String owningPlayerId) {
        super(id, owningPlayerId, null);
        set(Attr.NAME, areaName);
    }

    public String areaName() {
        return getString(Attr.NAME);
    }

    @Override
    public String entityName() {
        return "com.direwolfdigital.game.core.PlayArea";
    }
}
