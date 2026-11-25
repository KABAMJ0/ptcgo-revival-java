package com.ptcgo.revival.game.model;

import com.ptcgo.revival.game.wire.Attr;

/** A player wrapper on the playmat (CakePlayerEntity). */
public final class PlayerEntity extends Entity {
    public PlayerEntity(String id, String playerId) {
        super(id, playerId, null);
        set(Attr.NAME, "player");
    }

    @Override
    public String entityName() {
        return "com.direwolfdigital.cake.rules.entities.CakePlayerEntity";
    }
}
