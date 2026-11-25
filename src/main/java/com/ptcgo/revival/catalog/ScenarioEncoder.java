package com.ptcgo.revival.catalog;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * The three PvE league scenarios as dwd.Protobuf.Progression.AllScenarios. The seed keeps each scenario's GUID,
 * which the client keys its own progress by, so it must not follow the label (the names are plain text because
 * the client ships no ids_league_* strings).
 */
public final class ScenarioEncoder {
    public static final String MESSAGE_NAME = "dwd.Protobuf.Progression.AllScenarios";

    private record Scenario(String seed, String name, String description, int leagueId) {}

    private static final List<Scenario> LEAGUE_SCENARIOS = List.of(
            new Scenario("ids_league_gold", "Gold League", "Clear the Gold League's Trainers.", 1),
            new Scenario("ids_league_platinum", "Platinum League", "Clear the Platinum League's Trainers.", 2),
            new Scenario("ids_league_city", "City League", "Clear the City League's Trainers.", 3));

    // Attribute ids (ptcgo/game/attributes.py)
    private static final int NAME = 10140, SCENARIO_IMAGE = 10060, SCENARIO_LEAGUE_ID = 201420, SCENARIO_DESCRIPTION = 201430;

    private ScenarioEncoder() {}

    /** uuid.UUID(bytes=md5(seed)): the raw digest as a GUID, with no version bits set. */
    static byte[] seedGuidBytes(String seed) {
        try {
            return MessageDigest.getInstance("MD5").digest(seed.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Python's json.dumps({"id": text}) (note the space after the colon: the bytes must match). */
    private static String idJson(String text) {
        return "{\"id\": " + Catalog.JSON_FACTORY.textNode(text).toString() + "}";
    }

    private static ObjectNode json(String value) {
        return Catalog.JSON_FACTORY.objectNode().put("type", "json").put("value", value);
    }

    public static byte[] allScenarios() {
        ProtoWriter all = new ProtoWriter();
        // available (2) then roots (4), each carrying every scenario
        byte[][] scenarios = new byte[LEAGUE_SCENARIOS.size()][];
        for (int i = 0; i < scenarios.length; i++) scenarios[i] = scenario(LEAGUE_SCENARIOS.get(i));
        for (byte[] s : scenarios) all.bytes(2, s);
        for (byte[] s : scenarios) all.bytes(4, s);
        return all.toByteArray();
    }

    private static byte[] scenario(Scenario s) {
        ByteBuffer b = ByteBuffer.wrap(seedGuidBytes(s.seed()));
        long hi = b.getLong(), lo = b.getLong();
        ProtoWriter guid = new ProtoWriter().fixed64(1, lo).fixed64(2, hi);
        ProtoWriter sc = new ProtoWriter().bytes(1, guid.toByteArray());
        sc.bytes(2, ArchetypeEncoder.attribute(SCENARIO_LEAGUE_ID,
                Catalog.JSON_FACTORY.objectNode().put("type", "int").put("value", s.leagueId())));
        sc.bytes(2, ArchetypeEncoder.attribute(NAME, json(idJson(s.name()))));
        sc.bytes(2, ArchetypeEncoder.attribute(SCENARIO_DESCRIPTION, json(idJson(s.description()))));
        sc.bytes(2, ArchetypeEncoder.attribute(SCENARIO_IMAGE, json(idJson(""))));
        sc.bytes(2, ArchetypeEncoder.attribute(201470, json("{}")));
        sc.bytes(2, ArchetypeEncoder.attribute(201500, json("{}")));
        sc.bytes(2, ArchetypeEncoder.attribute(202260, json("{}")));
        sc.bytes(2, ArchetypeEncoder.attribute(201700, json("[]")));
        sc.bytes(2, ArchetypeEncoder.attribute(202270, json("[]")));
        return sc.toByteArray();
    }
}
