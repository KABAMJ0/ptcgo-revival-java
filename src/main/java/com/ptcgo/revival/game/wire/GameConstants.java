package com.ptcgo.revival.game.wire;

/** Gameplay/session wire constants (ptcgo/game/session/constants.py). Strings are exact values the client compares. */
public final class GameConstants {
    private GameConstants() {}

    public static final int STARTING_HAND_SIZE = 7;
    public static final int MOVE_ANIM_DURATION_MS = 300;
    public static final int BENCH_SLOT_COUNT = 5;
    public static final int BENCH_CAPACITY = BENCH_SLOT_COUNT;
    public static final int PRIZE_COUNT = 6;
    public static final int MAX_SELECTION_RETRIES = 50;
    public static final int MAX_TURNS = 500;
    public static final int MAX_ACTIONS_PER_TURN = 100;
    public static final int ACTION_INACTIVITY_DURATION_MS = 15_000;
    public static final int ACTION_COUNTDOWN_DURATION_MS = 15_000;
    public static final int ACTION_TIMEOUT_MS = ACTION_INACTIVITY_DURATION_MS + ACTION_COUNTDOWN_DURATION_MS;
    public static final int FOLLOW_UP_TIMEOUT_MS = 60_000;
    public static final int RECONNECT_GRACE_SECONDS = 30;

    /** SequenceID for standalone (unbracketed) SequenceMessages; the client only accepts those with the empty GUID. */
    public static final String EMPTY_SEQUENCE_ID = "00000000-0000-0000-0000-000000000000";

    public static final String ZONE_NAME_ACTIVE = "active";
    public static final String ZONE_NAME_BENCH = "bench";

    public static final String TARGET_TYPE_ACTIVE = SelectionKind.KNOCKOUT_POKEMON.wire();
    public static final String TARGET_TYPE_BENCH = SelectionKind.INITIAL_BENCHED.wire();
    public static final String TARGET_TYPE_MAIN_TURN = SelectionKind.ABILITY.wire();

    public static final String GAME_OPTION_TOKENS_KEY = "Tokens";
    public static final String TOKEN_GX = "GXToken";
    public static final String TOKEN_VSTAR = "VSTARToken";
    public static final String GAME_OPTION_RECONNECTING_KEY = "Reconnecting";
    public static final String GAME_OPTION_RECONNECTING_VALUE = "true";

    public static final String PROMPT_SORT_COIN = SelectionKind.COIN_FLIP_CHOICE.wire();
    public static final String PROMPT_SORT_GO_FIRST = SelectionKind.GO_FIRST_CHOICE.wire();
    public static final String PROMPT_SORT_MULLIGAN = "MulliganExtraDraw";

    public static final String PROMPT_HEADS = "Heads";
    public static final String PROMPT_TAILS = "Tails";
    public static final String PROMPT_YES = "Yes";
    public static final String PROMPT_NO = "No";
    public static final String PROMPT_YES_COUNT = "Yes ({0})";

    public static final String TEXT_COIN_TOSS_RESULT = "Coin Toss Result";
    public static final String TEXT_HEADS_RESULT = "Heads!";
    public static final String TEXT_TAILS_RESULT = "Tails!";

    public static final String PROMPT_RETREAT_NEW_ACTIVE = "Choose a Pokémon to be your new Active Pokémon";
    public static final String PROMPT_RETREAT_COST = "Choose Energy to discard for the Retreat Cost";
    public static final String PROMPT_CHOOSE_ACTIVE = "Choose a Basic Pokémon to be your Active Pokémon";
    public static final String PROMPT_CHOOSE_BENCH = "You may put Basic Pokémon on your Bench";
    public static final String PROMPT_WAIT_OPPONENT_SETUP = "Please wait while your opponent finishes setting up.";
    public static final String PROMPT_WAIT_OPPONENT_DECISION = "Please wait while your opponent makes a decision.";
    public static final String PROMPT_MULLIGAN_EXTRA_DRAW =
            "Your opponent took {0} mulligan(s). Would you like to draw extra cards?";
    public static final String TEXT_MULLIGAN_REVEAL_PROMPT = "Hands revealed: {0}";
    public static final String PROMPT_TAKE_PRIZE = "Choose a Prize card to take";
    public static final String PROMPT_CHOOSE_NEW_ACTIVE = "Choose a Pokémon to be your new Active Pokémon";

    public static final String TEXT_SLEEP_CHECK = "Asleep Check";
    public static final String TEXT_WOKE_UP = "Woke up!";
    public static final String TEXT_STILL_ASLEEP = "Still Asleep!";
    public static final String TEXT_BURN_CHECK = "Burn Check";
    public static final String TEXT_BURN_CURED = "Cured!";
    public static final String TEXT_STILL_BURNED = "Still Burned!";
    public static final String TEXT_CONFUSION_CHECK = "Confusion Check";
    public static final String TEXT_CONFUSION_PROCEEDS = "Attack proceeds!";
    public static final String TEXT_CONFUSION_HURT = "Hurt itself in confusion!";
    public static final String ENDGAME_REWARD_TEXT = "Game Reward";

    public static final String ACTION_PLAY_POKEMON = "DefaultPokemonPlayAbility";
    public static final String ACTION_EVOLVE = "EvolvePokemonPlayAbility";
    public static final String ACTION_PLAY_ENERGY = "DefaultEnergyPlayAbility";
    public static final String ACTION_USE_TRAINER = "UseTrainerCard";
    public static final String ACTION_PLAY_STADIUM = "DefaultStadiumPlayAbility";
    public static final String ACTION_USE_ABILITY = "UsePokemonAbility";
    public static final String ACTION_USE_ATTACK = "UsePokemonAttack";
    public static final String ACTION_RETREAT = "BaseRetreat";
    public static final String ACTION_ATTACH_TOOL = "DefaultToolPlayAbility";
    public static final String SELECTION_TYPE_PLAY = SelectionKind.ABILITY.wire();
    public static final String SELECTION_TYPE_PANEL = SelectionKind.ABILITY_SELECTION.wire();

    /** Suffixes of the client's playmat.endgame.stat.* rows on the EOG summary page. */
    public static final String[] EOG_STAT_KEYS = {"biggestattack", "damagedealt", "prizecardstaken", "damagehealed",
            "energyplayed", "trainersplayed", "cardsdrawn", "headsflipped", "tailsflipped"};

    public static final int COINS_PER_WIN = 200;
    public static final int COINS_PER_LOSS = 0;
}
