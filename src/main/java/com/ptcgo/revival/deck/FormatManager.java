package com.ptcgo.revival.deck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ptcgo.revival.catalog.Catalog;
import com.ptcgo.revival.catalog.Catalog.Card;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * The play formats and per-card legality (formats.json plus the always-present Theme and TrainerChallenge formats).
 * Card attribute ids used here: 10020 set key, 200300 card type, 200970 special energy.
 */
public final class FormatManager {
    private static final Logger LOG = LoggerFactory.getLogger(FormatManager.class);

    public static final String STANDARD = "6402e830-7fed-4cd1-b172-2a320047c2bb";
    public static final String EXPANDED = "98c83df9-ec82-4193-84a8-104115ce4e25";
    public static final String UNLIMITED = "6a1dec5a-34db-4cee-a503-4ee759304135";
    public static final String LEGACY = "6b33d420-73cc-40d4-ada5-88a7d68063a9";
    public static final String THEME = "1414fd67-a632-4e38-ae04-0adf0074ac16";
    public static final String TRAINER_CHALLENGE = "6a1dec5a-34db-4cee-a503-4ee759304136";

    /**
     * The names the client hashes in DeckValidationManager.updateValidations ("Standard" is "Modified" on the wire).
     * They win over formats.json so a renamed format cannot clear the flags the deck list filters on.
     */
    public static final Map<String, String> WIRE_NAMES = Map.of(
            STANDARD, "Modified", EXPANDED, "Expanded", UNLIMITED, "Unlimited", LEGACY, "Legacy",
            THEME, "ThemeDeck", TRAINER_CHALLENGE, "TrainerChallenge");

    /** The formats a client asks about when it names none, in the client's order. */
    public static final List<String> DEFAULT_VALIDATION_FORMATS =
            List.of(STANDARD, EXPANDED, UNLIMITED, LEGACY, THEME, TRAINER_CHALLENGE);

    private static final int ATTR_SET_KEY = 10020;
    private static final int ATTR_CARD_TYPE = 200300;
    private static final int ATTR_IS_SPECIAL_ENERGY = 200970;
    private static final int CARD_TYPE_ENERGY = 3;
    private static final Set<String> LEGACY_SETS = Set.of("BW1");
    private static final Set<String> STANDARD_NON_SWSH_SETS = Set.of("CEL25", "PGO", "CZ", "CUSTOM", "Free_Energy");

    private record Refs(Set<String> banned, Set<String> extra) {}

    private final Catalog catalog;
    private final ThemeDecks themeDecks;
    private final Predicate<String> serverBanned;
    private final LongSupplier clockMs;
    private final List<GameFormat> formats;
    private final Map<String, GameFormat> byGuid = new HashMap<>();
    private final Map<String, Refs> refCache = new ConcurrentHashMap<>();
    private volatile Map<String, String> guidByRef;

    /**
     * @param formats      the configured formats; Theme and TrainerChallenge are appended when absent
     * @param serverBanned guid (lower-case) to "banned in every format" (the server-wide card ban list)
     * @param clockMs      current epoch milliseconds, for {@code legalFrom} gates
     */
    public FormatManager(List<GameFormat> formats, Catalog catalog, ThemeDecks themeDecks,
                         Predicate<String> serverBanned, LongSupplier clockMs) {
        this.catalog = catalog;
        this.themeDecks = themeDecks;
        this.serverBanned = serverBanned;
        this.clockMs = clockMs;
        List<GameFormat> all = new ArrayList<>(formats);
        if (all.stream().noneMatch(f -> f.guid().equals(THEME))) {
            all.add(GameFormat.of("Theme", THEME, "ThemeDeck", Set.of(), true));
        }
        // Plain deck-building legality: any complete deck can be played in Trainer Challenge.
        if (all.stream().noneMatch(f -> f.guid().equals(TRAINER_CHALLENGE))) {
            all.add(GameFormat.of("TrainerChallenge", TRAINER_CHALLENGE, "TrainerChallenge", Set.of(), true));
        }
        this.formats = List.copyOf(all);
        for (GameFormat f : this.formats) byGuid.put(f.guid(), f);
    }

    /**
     * Loads {@code <dir>/formats.json} ({"formats": [...]}). A missing or unreadable file falls back to
     * {@link #defaultFormats(Set)} over the catalogue's sets; the fallback is not written back to disk.
     */
    public static FormatManager load(Path dir, Catalog catalog, ThemeDecks themeDecks,
                                     Predicate<String> serverBanned, LongSupplier clockMs) {
        Path file = dir.resolve("formats.json");
        List<GameFormat> formats = null;
        if (Files.isRegularFile(file)) {
            try {
                formats = parse(new ObjectMapper().readTree(file.toFile()));
                LOG.info("[Formats] Loaded {} formats from {}", formats.size(), file);
            } catch (IOException | RuntimeException e) {
                LOG.error("[Formats] Failed to load {}, using defaults: {}", file, e.toString());
            }
        }
        if (formats == null) formats = defaultFormats(catalog.setCodes());
        return new FormatManager(formats, catalog, themeDecks, serverBanned, clockMs);
    }

    static List<GameFormat> parse(JsonNode root) {
        List<GameFormat> out = new ArrayList<>();
        for (JsonNode n : root.path("formats")) out.add(GameFormat.fromJson(n));
        if (out.isEmpty()) throw new IllegalArgumentException("formats.json contains no formats");
        return out;
    }

    /** Every loaded set is Expanded/Unlimited, the SWSH block is Standard, BW is Legacy. */
    public static List<GameFormat> defaultFormats(Set<String> loadedSets) {
        Set<String> loaded = new TreeSet<>(loadedSets);
        Set<String> standard = new TreeSet<>();
        Set<String> legacy = new TreeSet<>();
        for (String s : loaded) {
            if (s.startsWith("SWSH") || STANDARD_NON_SWSH_SETS.contains(s)) standard.add(s);
            if (LEGACY_SETS.contains(s) || s.equals("Free_Energy") || s.equals("CUSTOM")) legacy.add(s);
        }
        return List.of(
                GameFormat.of("Standard", STANDARD, "Modified", standard, false),
                GameFormat.of("Expanded", EXPANDED, "Expanded", loaded, false),
                GameFormat.of("Legacy", LEGACY, "Legacy", legacy, false),
                GameFormat.of("Unlimited", UNLIMITED, "Unlimited", Set.of(), true),
                GameFormat.of("Theme", THEME, "ThemeDeck", Set.of(), true),
                GameFormat.of("TrainerChallenge", TRAINER_CHALLENGE, "TrainerChallenge", Set.of(), true));
    }

    public List<GameFormat> formats() {
        return formats;
    }

    public GameFormat byGuid(String formatGuid) {
        return formatGuid == null ? null : byGuid.get(formatGuid.toLowerCase(Locale.ROOT));
    }

    /** Resolves a format guid, config key or wire name to its guid; null when unknown. */
    public String resolveFormatGuid(String value) {
        String v = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) return null;
        GameFormat direct = byGuid(v);
        if (direct != null) return direct.guid();
        for (GameFormat f : formats) {
            if (v.equals(f.key().toLowerCase(Locale.ROOT)) || v.equals(f.formatName().toLowerCase(Locale.ROOT))) return f.guid();
        }
        return null;
    }

    /** The name sent to the client for a format id; unknown ids read as "Modified" like the Python server. */
    public String formatName(String formatGuid) {
        String guid = formatGuid == null ? "" : formatGuid.toLowerCase(Locale.ROOT);
        String wire = WIRE_NAMES.get(guid);
        if (wire != null) return wire;
        GameFormat f = byGuid.get(guid);
        return f != null ? f.formatName() : "Modified";
    }

    public List<String> playFormatGuids() {
        return formats.stream().map(GameFormat::guid).toList();
    }

    public List<String> legalFormatGuidsForSet(String setCode) {
        return formats.stream().filter(f -> f.allowsSet(setCode)).map(GameFormat::guid).toList();
    }

    public static boolean isBasicEnergy(Card card) {
        return card.intAttribute(ATTR_CARD_TYPE, -1) == CARD_TYPE_ENERGY && !card.boolAttribute(ATTR_IS_SPECIAL_ENERGY);
    }

    /** Guids this format's metadata bans, minus the ones it exempts. */
    public Set<String> bannedGuids(String formatGuid) {
        GameFormat f = byGuid(formatGuid);
        if (f == null) return Set.of();
        Refs refs = refs(f);
        Set<String> out = new HashSet<>(refs.banned());
        out.removeAll(refs.extra());
        return out;
    }

    /** Legality ignoring any legalFrom time gate (the formatLegality bool slot). */
    public boolean isCardEventuallyLegal(String formatGuid, Card card) {
        GameFormat f = byGuid(formatGuid);
        if (f == null) return false;
        String guid = card.guid().toLowerCase(Locale.ROOT);
        if (serverBanned.test(guid)) return false;
        if (f.guid().equals(THEME)) return themeDecks.isLegalCard(guid);
        if (isBasicEnergy(card)) return true;
        Refs refs = refs(f);
        if (refs.banned().contains(guid)) return false;
        if (refs.extra().contains(guid)) return true;
        return f.allowsSet(setKey(card));
    }

    public boolean isCardLegal(String formatGuid, Card card) {
        return isCardEventuallyLegal(formatGuid, card) && legalTimeMs(formatGuid, card) <= clockMs.getAsLong();
    }

    /** Epoch ms from which the card's set is legal in the format (0 = always). */
    public long legalTimeMs(String formatGuid, Card card) {
        GameFormat f = byGuid(formatGuid);
        return f == null ? 0 : f.legalFrom().getOrDefault(setKey(card), 0L);
    }

    private static String setKey(Card card) {
        String s = card.stringAttribute(ATTR_SET_KEY);
        return s == null || s.isEmpty() ? card.key() : s;
    }

    private Refs refs(GameFormat f) {
        return refCache.computeIfAbsent(f.guid(), g -> new Refs(resolve(f.bannedCards()), resolve(f.extraLegalCards())));
    }

    private Set<String> resolve(List<String> refs) {
        Set<String> out = new HashSet<>();
        for (String ref : refs) {
            String guid = resolveCardRef(ref);
            if (guid != null) out.add(guid);
        }
        return out;
    }

    private String resolveCardRef(String ref) {
        int slash = ref.indexOf('/');
        if (slash < 0) return ref.toLowerCase(Locale.ROOT);
        String guid = guidsBySetAndNumber().get(ref.substring(0, slash).toUpperCase(Locale.ROOT) + "/" + ref.substring(slash + 1).strip());
        if (guid == null) LOG.warn("[Formats] Card ref '{}' matched no loaded card", ref);
        return guid;
    }

    /** "KEY/number" to the first card printed with them. */
    private Map<String, String> guidsBySetAndNumber() {
        Map<String, String> index = guidByRef;
        if (index == null) {
            index = new HashMap<>();
            for (Card c : catalog.cards()) {
                index.putIfAbsent(c.key().toUpperCase(Locale.ROOT) + "/" + c.collectorNumber(), c.guid());
            }
            guidByRef = index;
        }
        return index;
    }
}
