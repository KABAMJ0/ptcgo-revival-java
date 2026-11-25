package com.ptcgo.revival.game.engine;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/** The built-in bot. It receives no animations; the engine asks the AI policy for its decisions directly. */
public final class AiPlayer extends GamePlayer {
    public static final List<String> BOT_NAMES = List.of(
            "Ash_K", "MistyWaves", "BrockSolid", "RedHot99", "BlueOak", "LeafGreen", "Cynthia_fan", "PikaPower",
            "Snorlax_Zzz", "TeamRocketGrunt", "EeveeLover", "CharizardKid", "MewtwoStrikes", "GymLeaderJay",
            "ProfBirch", "NightShade", "Lucario_X", "GengarGhost", "SunnyDay", "StarlightTrainer", "Dragonite_Dan",
            "WaterTypeWill", "FrostByte", "ThunderBolt7", "RoseGarden", "VoltTackle", "CasualCarl", "DeckBuilderDee",
            "BenchWarmer", "CoinFlipKing", "PrizeHunter", "LostZoneLou", "BasicEnergy", "TrainerKyle", "RareCandyRay",
            "MagikarpFan", "Wobbuffet_W", "SilverWing", "ShinyHunter", "Bidoof_Brigade", "AlolaAmy", "GalarGreg",
            "PaldeaPete", "KantoKate", "justmatt", "itsjordan", "alexonline", "tommy_04", "noahhere", "maxxwell",
            "user2481", "dailyben", "jakeoffline", "simplyryan", "morganx", "ethan_22", "lukewasthere", "randomdylan",
            "notalex", "chrisonline", "sammyhere", "unknownuser", "itsmason", "heyoliver", "JohtoJoe", "HoennHal",
            "SinnohSam", "UnovaUma", "KalosKai", "TrickRoomTim");

    private final List<String> avatar;
    /** Policy name: "smart" (default), "lite" or "basic" (PTCGO_AI_POLICY). */
    public String policy;

    public AiPlayer(String botId, String botName, JsonNode deckData, List<String> avatarItems) {
        super(botId, botName, deckData);
        this.avatar = avatarItems;
    }

    public static String randomName(RandomGenerator rng) {
        return BOT_NAMES.get(rng.nextInt(BOT_NAMES.size()));
    }

    @Override
    public String screenName() {
        return username;
    }

    @Override
    public List<String> avatarItems() {
        return avatar;
    }

    @Override
    public void send(String name, Map<String, Object> value, int flags) {
        // The AI ignores visual animations.
    }

    @Override
    public boolean isAi() {
        return true;
    }
}
