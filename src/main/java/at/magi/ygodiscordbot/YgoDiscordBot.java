package at.magi.ygodiscordbot;

import at.magi.ygodiscordbot.config.BotConfig;
import at.magi.ygodiscordbot.config.DatabaseConfig;
import at.magi.ygodiscordbot.impl.banlist.BanlistCommand;
import at.magi.ygodiscordbot.impl.banlist.BanlistRefresher;
import at.magi.ygodiscordbot.impl.banlist.BanlistRepository;
import at.magi.ygodiscordbot.impl.banlist.GenesysSource;
import at.magi.ygodiscordbot.impl.banlist.SnapshotFileStore;
import at.magi.ygodiscordbot.impl.banlist.StaticBanlists;
import at.magi.ygodiscordbot.impl.banlist.YgoProDeckSource;
import at.magi.ygodiscordbot.impl.card.CardFileStore;
import at.magi.ygodiscordbot.impl.card.CardRefresher;
import at.magi.ygodiscordbot.impl.card.CardRepository;
import at.magi.ygodiscordbot.impl.card.CardSource;
import at.magi.ygodiscordbot.impl.command.CommandRegistry;
import at.magi.ygodiscordbot.impl.command.PingCommand;
import at.magi.ygodiscordbot.impl.deck.DeckCommand;
import at.magi.ygodiscordbot.impl.deck.DeckDatabase;
import at.magi.ygodiscordbot.impl.deck.DecklistRepository;
import at.magi.ygodiscordbot.impl.help.HelpCommand;
import at.magi.ygodiscordbot.impl.leaderboard.LeaderboardAdminCommand;
import at.magi.ygodiscordbot.impl.leaderboard.LeaderboardCommand;
import at.magi.ygodiscordbot.impl.leaderboard.PlayerNames;
import at.magi.ygodiscordbot.impl.leaderboard.PlayerRepository;
import at.magi.ygodiscordbot.impl.leaderboard.PointsCommand;
import at.magi.ygodiscordbot.runtime.Supervisor;
import at.magi.ygodiscordbot.utils.http.HttpDownloader;
import com.zaxxer.hikari.HikariDataSource;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.exceptions.InvalidTokenException;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Deque;
import java.util.EnumSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class YgoDiscordBot {

    private static final Logger log = LoggerFactory.getLogger(YgoDiscordBot.class);

    /**
     * Cleanup steps, run newest first by one shutdown hook. The hook is registered before anything starts, so
     * every exit (stop signal, invalid token, crash in main) closes what was started so far.
     */
    private static final Deque<Runnable> ON_SHUTDOWN = new ConcurrentLinkedDeque<>();

    /** Pending /deck, /leaderboard and /points requests beyond this are rejected with a "busy" reply, which bounds memory. */
    private static final int DECK_QUEUE_SIZE = 50;

    private YgoDiscordBot() {
    }

    public static void main(String[] args) throws Exception {
        Supervisor.superviseUnlessChild(YgoDiscordBot.class);
        Runtime.getRuntime().addShutdownHook(new Thread(YgoDiscordBot::shutdown, "shutdown"));

        BotConfig config;
        try {
            config = BotConfig.load();
        } catch (IllegalStateException e) {
            exitWithConfigError(e.getMessage());
            return;
        }

        Clock clock = Clock.systemUTC();
        HttpDownloader http = new HttpDownloader();
        YgoProDeckSource ygoProDeck = new YgoProDeckSource(http, clock);
        GenesysSource genesys = new GenesysSource(http, clock);
        BanlistRepository banlists = new BanlistRepository(StaticBanlists.goat(), StaticBanlists.edison());
        BanlistRefresher refresher = new BanlistRefresher(banlists, new SnapshotFileStore(SnapshotFileStore.DEFAULT_FILE),
                ygoProDeck::fetchTcg, ygoProDeck::fetchOcg, genesys::fetch, clock);
        refresher.start();
        ON_SHUTDOWN.push(refresher::close);

        CommandRegistry commands = new CommandRegistry();
        commands.register(new PingCommand());
        // Reads the registry on every call, so it also lists /deck and /leaderboard registered below
        commands.register(new HelpCommand(commands::commandData));
        commands.register(new BanlistCommand(banlists));

        HikariDataSource deckDatabase = null;
        ExecutorService deckExecutor = null;
        if (config.database() != null) {
            deckDatabase = openDeckDatabase(config.database());
        } else {
            log.warn("DB_URL is not set, /deck, /leaderboard and /points are disabled");
        }
        if (deckDatabase != null) {
            deckExecutor = deckExecutor();
            ExecutorService executor = deckExecutor;
            HikariDataSource database = deckDatabase;
            ON_SHUTDOWN.push(() -> closeDeckDatabase(executor, database));
            DecklistRepository decks = new DecklistRepository(deckDatabase, clock);
            deckExecutor.execute(() -> createSchema(decks, config.database()));

            // Card names are only needed for /deck, so they are only downloaded when it is enabled
            CardRepository cards = new CardRepository();
            CardSource cardSource = new CardSource(http);
            CardRefresher cardRefresher = new CardRefresher(cards, new CardFileStore(CardFileStore.DEFAULT_FILE),
                    cardSource::fetchVersion, cardSource::fetchCards, clock);
            cardRefresher.start();
            ON_SHUTDOWN.push(cardRefresher::close);

            commands.register(new DeckCommand(decks, cards, deckExecutor));

            PlayerRepository players = new PlayerRepository(deckDatabase);
            deckExecutor.execute(() -> createPlayersSchema(players, config.database()));
            PlayerNames playerNames = new PlayerNames(clock);
            commands.register(new LeaderboardCommand(players, playerNames, deckExecutor));
            commands.register(new LeaderboardAdminCommand(players, playerNames, deckExecutor));
            commands.register(new PointsCommand(players, deckExecutor));
        }

        // Slash commands need no privileged intents, so the default (empty) set is enough.
        JDA jda;
        try {
            jda = JDABuilder.createLight(config.token(), EnumSet.noneOf(GatewayIntent.class))
                    // Per-member channel overrides, so /leaderboard-admin share checks the organizer's real permissions
                    .enableCache(CacheFlag.MEMBER_OVERRIDES)
                    .addEventListeners(commands)
                    .build()
                    .awaitReady();
        } catch (InvalidTokenException e) {
            exitWithConfigError("Discord rejected the token: " + e.getMessage());
            return;
        }
        ON_SHUTDOWN.push(jda::shutdown);

        // Guild commands update instantly; global ones can take up to an hour to show up.
        if (config.devGuildId() != null) {
            var guild = jda.getGuildById(config.devGuildId());
            if (guild == null) {
                throw new IllegalStateException("Bot is not a member of guild " + config.devGuildId());
            }
            guild.updateCommands().addCommands(commands.commandData()).queue();
            log.info("Registered {} command(s) in guild {}", commands.size(), guild.getName());
            log.warn("Commands registered for one server only cannot be used in DMs; unset DEV_GUILD_ID to register globally");
        } else {
            jda.updateCommands().addCommands(commands.commandData()).queue();
            log.info("Registered {} global command(s)", commands.size());
        }

        log.info("Logged in as {}", jda.getSelfUser().getAsTag());
        logMemory();
    }

    private static void shutdown() {
        Runnable step;
        while ((step = ON_SHUTDOWN.poll()) != null) {
            try {
                step.run();
            } catch (RuntimeException e) {
                log.warn("Shutdown step failed", e);
            }
        }
    }

    /** Lets running /deck requests finish before the pool closes. */
    private static void closeDeckDatabase(ExecutorService executor, HikariDataSource database) {
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        database.close();
    }

    /** A broken database setting disables /deck instead of stopping the whole bot. */
    private static HikariDataSource openDeckDatabase(DatabaseConfig database) {
        try {
            return DeckDatabase.open(database);
        } catch (RuntimeException e) {
            log.error("Invalid decklist database settings ({}), /deck, /leaderboard and /points are disabled: {}",
                    database, e.getMessage());
            return null;
        }
    }

    /** One thread: queries are tiny, and it keeps at most one connection busy. */
    private static ExecutorService deckExecutor() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(DECK_QUEUE_SIZE),
                runnable -> {
                    Thread thread = new Thread(runnable, "deck-db");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    private static void createSchema(DecklistRepository decks, DatabaseConfig database) {
        try {
            decks.ensureSchema();
        } catch (SQLException | RuntimeException e) {
            // Retried on the first /deck call
            log.error("Could not reach the decklist database {}: {}", database.safeUrl(), e.getMessage());
        }
    }

    private static void createPlayersSchema(PlayerRepository players, DatabaseConfig database) {
        try {
            players.ensureSchema();
        } catch (SQLException | RuntimeException e) {
            // Retried on the first /leaderboard call
            log.error("Could not create the players table in {}: {}", database.safeUrl(), e.getMessage());
        }
    }

    /** Memory after startup, to check the bot stays within the container limit. */
    private static void logMemory() {
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long nonHeap = ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.NON_HEAP)
                .mapToLong(pool -> pool.getUsage().getUsed())
                .sum();
        long metaspace = ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getName().equals("Metaspace"))
                .mapToLong(pool -> pool.getUsage().getUsed())
                .sum();
        log.info("Memory after startup: heap {} of {} MB, metaspace {} MB, non-heap total {} MB, {} classes loaded",
                heap.getUsed() >> 20, heap.getMax() >> 20, metaspace >> 20, nonHeap >> 20,
                ManagementFactory.getClassLoadingMXBean().getLoadedClassCount());
    }

    /** A restart cannot fix a configuration error, so tell the supervisor not to try. */
    private static void exitWithConfigError(String message) {
        log.error(message);
        System.exit(Supervisor.EXIT_CONFIG_ERROR);
    }
}
