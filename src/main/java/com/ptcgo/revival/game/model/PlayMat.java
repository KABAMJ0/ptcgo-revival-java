package com.ptcgo.revival.game.model;

import com.ptcgo.revival.game.wire.Attr;

/** Root Playmat scene entity (CakePlayMat). */
public final class PlayMat extends Entity {
    public PlayMat(String id) {
        super(id, null, null);
        set(Attr.NAME, "playmat");
    }

    @Override
    public String entityName() {
        return "com.direwolfdigital.cake.rules.entities.CakePlayMat";
    }
}
