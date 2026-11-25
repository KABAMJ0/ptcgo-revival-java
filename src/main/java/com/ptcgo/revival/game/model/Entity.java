package com.ptcgo.revival.game.model;

import com.ptcgo.revival.game.wire.Attr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Anything on the client's playmat tree (ptcgo/game/models/board.py BoardEntity). */
public abstract class Entity {
    public final String entityId;
    public Entity parent;
    public String owningPlayerId;
    public String archetypeId;
    /** Mirror of the client's A.m slot stamp: positionInParent of the last EntityMoved sent for this entity. */
    public Integer boardSlot;
    public final Map<Integer, Object> attributes = new LinkedHashMap<>();
    /** Per-attribute base values, serialized as originalValue when the live value diverges (HP max vs remaining). */
    public final Map<Integer, Object> attributeOriginals = new LinkedHashMap<>();
    public final List<Entity> children = new ArrayList<>();
    /** A Prize card turned face up is public knowledge. */
    public boolean faceUp;

    protected Entity(String entityId, String owningPlayerId, String archetypeId) {
        this.entityId = entityId;
        this.owningPlayerId = owningPlayerId;
        this.archetypeId = archetypeId;
    }

    public void set(int key, Object value) {
        attributes.put(key, value);
    }

    public Object get(int key) {
        return attributes.get(key);
    }

    public int getInt(int key, int def) {
        Object v = attributes.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof com.fasterxml.jackson.databind.JsonNode j && j.isNumber()) return j.asInt();
        return def;
    }

    public String getString(int key) {
        Object v = attributes.get(key);
        if (v instanceof String s) return s;
        if (v instanceof com.fasterxml.jackson.databind.JsonNode j && j.isTextual()) return j.asText();
        return null;
    }

    public void addChild(Entity child) {
        addChild(child, null);
    }

    /** Appends (position == null / past the end) or inserts at a specific index (an evolution taking a slot). */
    public void addChild(Entity child, Integer position) {
        child.parent = this;
        if (position == null || position >= children.size()) children.add(child);
        else children.add(Math.max(position, 0), child);
    }

    public void removeChild(Entity child) {
        if (children.remove(child)) child.parent = null;
    }

    public String parentId() {
        return parent == null ? null : parent.entityId;
    }

    /** Fully qualified client class name. */
    public abstract String entityName();

    /** Whether this entity's identity is hidden from the viewer (serialized with attributes=null). */
    public boolean isHiddenFrom(String viewerId) {
        return false;
    }

    /** Client attribute list: [{name, value, originalValue, modValue}]. */
    public List<Map<String, Object>> serializeAttributes() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<Integer, Object> e : attributes.entrySet()) {
            Object formatted = e.getValue();
            if (e.getKey() == Attr.NAME && formatted instanceof String s) formatted = Map.of("id", s);
            out.add(attributeEntry(e.getKey(), formatted, attributeOriginals.getOrDefault(e.getKey(), formatted), formatted));
        }
        return out;
    }

    public static Map<String, Object> attributeEntry(int name, Object value, Object original, Object mod) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("value", value);
        m.put("originalValue", original);
        m.put("modValue", mod);
        return m;
    }

    /** Recursively serializes the entity to the SerializedGameState node shape. */
    public Map<String, Object> serialize(String viewerId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entityID", entityId);
        m.put("parentID", parentId());
        m.put("owningPlayerID", owningPlayerId);
        m.put("entityName", entityName());
        m.put("archetypeID", archetypeId);
        m.put("attributes", isHiddenFrom(viewerId) ? null : serializeAttributes());
        List<Map<String, Object>> kids = new ArrayList<>();
        for (Entity c : children) kids.add(c.serialize(viewerId));
        m.put("children", kids);
        return m;
    }
}
