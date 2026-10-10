# ygo-discord-bot

A Discord bot for Yu-Gi-Oh! players, written in Java 17 with [JDA 6](https://github.com/discord-jda/JDA).
It is built to run on a free hosting plan with about 300 MB of RAM.

- **Banlists:** `/banlist` sends the current TCG, OCG and Genesys lists, plus the frozen Goat and Edison
  format lists, as plain-text messages that Discord's search can find.
- **Decklists:** `/deck` stores each user's decks as [YDKE URIs](#4-ydke-the-deck-format) in a MySQL database and
  shows them with real card names.
- **Leaderboard:** `/leaderboard` shows tournament points, ranked highest first; organizers manage points with
  `/points` and `/leaderboard add|update`, and post the top 20 into a channel with `/leaderboard-admin share`.
- **Tournaments:** organizers start Swiss tournaments for 2–32 players with `/tournament start`; the bot pairs every
  round without rematches, players report results with `/match finish` (or `/match doubleloss` when time runs out),
  and the winner and match wins earn leaderboard points.
- **Hosting:** runs on [Waifly](https://waifly.com)'s free tier, a [Pterodactyl](https://pterodactyl.io) panel with
  ~300 MB of RAM and a Java image (start command `java -jar ygo-discord-bot.jar`). The database is the
  panel's MySQL, reached from inside the container at `172.18.0.1:3306`, not at the public `db.waifly.com`.
  `./deploy.sh` builds the jar and uploads it via SFTP (settings in `deploy.env`); `bot.properties` is
  uploaded once by hand.

Contents:
[Commands](#commands) ·
[Quick start](#quick-start-local) ·
[External APIs and URLs](#external-apis-and-urls) ·
[How it works](#how-it-works-implementation-tutorial) ·
[Testing](#testing) ·
[Troubleshooting](#troubleshooting)

---

## Commands

| Command | What it does |
|---|---|
| `/banlist format:<TCG\|OCG\|Genesys\|Goat\|Edison>` | Sends the list as messages with monospace tables |
| `/deck save name:<name> ydke:<ydke://...>` | Saves a new deck and shows it |
| `/deck get name:<name>` | Shows a deck |
| `/deck update name:<name> ydke:<ydke://...>` | Replaces a saved deck and shows the new version |
| `/deck delete name:<name>` | Deletes a deck and shows what was deleted |
| `/deck list` | Lists your deck names |
| `/help` | Lists the commands you can use (only visible to you) |
| `/leaderboard page [page:<n>]` | Sends a leaderboard page (20 players, highest points first) to your DMs |
| `/leaderboard get [player:<@user>]` | Shows a player's rank and points as a one-row table (default: you) |
| `/leaderboard add player:<@user>` | Adds a player with 0 points (Manage Server) |
| `/leaderboard update player:<@user> points:<n>` | Sets a player's points (Manage Server) |
| `/points add\|remove player:<@user> amount:<1-99>` | Adds or removes points (Manage Server, hidden from others) |
| `/leaderboard-admin share [channel:<#channel>]` | Posts the top 20 into a channel (default: this one) |
| `/tournament list [page:<n>] [date:<YY-MM-dd>]` | Lists this server's tournaments (ID, date, winner; 20 per page, newest first), only visible to you |
| `/tournament start players:<@mentions>` | Starts a Swiss tournament with 2–32 players in this channel (Manage Server) |
| `/tournament continue id:<tournament ID>` | Starts the next round once all results are in (Manage Server) |
| `/tournament standings id:<tournament ID>` | Shows standings and open matches (Manage Server) |
| `/tournament cancel id:<tournament ID>` | Ends a tournament without a winner (Manage Server) |
| `/tournament drop id:<tournament ID> player:<@user>` | Removes a player from the remaining rounds; their open match is lost (Manage Server) |
| `/match finish id:<match ID> winner:<@user>` | Reports the winner of your own match |
| `/match doubleloss id:<match ID>` | Time ran out without a winner in your match: both players get a loss |
| `/match-admin finish id:<match ID> winner:<@user>`, `/match-admin doubleloss id:<match ID>` | Sets or corrects any result of the open round (Manage Server) |
| `/ping` | Checks that the bot is alive |

**`/banlist`** keeps server channels clean. Used in a server, it sends the list to the user's DMs and replies
with a link that only they can see. Used in the bot's DM, it posts the list right there. The lists are plain
text instead of embeds, because Discord's search can find plain text but not embeds. If a user blocks DMs
from server members, the bot asks them to run the command in its DM instead.

**`/leaderboard page`** works like `/banlist`: in a server the page goes to your DMs, in the bot's DM it is posted
there. Each page is a table with the columns Rank, Player and Points. Points are a running total per Discord
user (table `players`: `id` = Discord user ID, `points` default 0); the rank is computed when querying, and
players with equal points share a rank (1, 2, 2, 4).

Points stay between 0 and 999,999; `/points remove` stops at 0, `/points add` at 999,999, and the reply says
how many points were really applied. `/points add` adds a player who isn't on the board yet. `/leaderboard add` and
`update` check **Manage Server** in the bot itself (Discord can't hide single subcommands of a public command),
so role grants under *Integrations* don't apply to them; `/points` is hidden by Discord like `/leaderboard-admin`.
All of these replies are only visible to you. Resetting all points to 0 is only possible in code
(`PlayerRepository.resetAllPoints`), not as a command.

**`/leaderboard-admin`** is hidden from members without **Manage Server**. Server owners can hand it to other
roles (or limit it to channels) under *Server Settings → Integrations → YGO DC Bot*; the bot does not check
Manage Server itself, so those settings work. `share` only posts if both you and the bot can send messages
in the target channel. Needs `DB_URL`, like `/deck`.

**Tournaments** (tables `tournament`, `tournament_player`, `tournament_match`): Swiss system. Players are entered
as @-mentions in one text option, since a command can have at most 25 options. Round 1 is random, later rounds pair
players with the same win-loss record who haven't met yet (backtracking; a rematch only if no other pairing exists).
With an odd number of players one gets a free win: random in round 1, then the player with the most losses. If time
runs out without a winner, `/match doubleloss` scores a loss for both players. When the last match of a round is
reported, the bot posts the results, a standings table and either the winner or the next pairings, which an organizer
starts with `/tournament continue`. The winner is the only player with the fewest losses (after a double loss or a
drop only once ⌈log₂ players⌉ rounds are played); leaders still tied after ⌈log₂ players⌉ rounds are decided by the
tie-breakers, so no tournament runs longer. Tie-breakers, as in Magic tournaments: OMW% (average match-win rate of a
player's opponents; every rate counts at least 33 %, free wins are no opponents, dropped players still count), then
OOMW% (average OMW% of the opponents), then head-to-head (only between exactly two equal players), then a lot that is
fixed per tournament. The winner post names the tie-breaker that decided. Points on finish: 1 per match win (free wins excluded) + the number of rounds for
the winner. Tournaments still running 48 h after their start are abandoned (no points).

Tournament IDs look like `k7m2x9qp4-26-10-10` (9-character key + start day in Vienna time, stored as `code` and
`played_on`; old tables are migrated automatically). They are in every post and reply, and every command takes them
as `id:`. The channel only gets the start, one post per completed round, the continue post (matchups with match IDs +
standings) and the end. Match results (also organizer corrections), drops and the new match IDs after a restart go to
the players by DM, because a channel message can't be shown to only two people. Standings are code-block tables
(Rank, Player, W-L, OMW%; only players the lot separates share a rank). `/tournament list` is for everyone; the other `/tournament` subcommands need **Manage Server**,
which the bot checks itself. Integrations overrides can still hide or restrict the whole `/tournament` command
(including `list`), but can't grant the organizer subcommands to members without Manage Server. `/match-admin` is hidden from members without **Manage Server**; `/match` is for everyone, but
only the two players of a match can report it.

**`/help`** replies where you used it, visible only to you. The list is built from the registered commands, so
it shows exactly what this bot instance offers (no `/deck` or `/leaderboard` without `DB_URL`). In a server it
only lists commands you can use there, so `/leaderboard-admin` is hidden without **Manage Server**; in the bot's
DM it lists everything and marks server-only commands. Overrides under *Integrations* are not read, so a role
that was granted `/leaderboard-admin` there still won't see it in `/help` (the command itself works).

**`/deck`** replies are only visible to the user who ran the command.
- **Names:** deck names are per user and ignore case, so "Snake-Eye" and "snake-eye" are the same deck.
- **Limits:** up to 50 decks per user, and at most 60 Main, 15 Extra and 15 Side Deck cards per deck.
- **Replies:** every reply that contains a deck starts with what happened, for example
  "You saved the following deck **Dragons**:". Then come the card counts, the cards by name per zone, and the
  YDKE URI to copy:

```
You saved the following deck **Dragons**:
Main 5 · Extra 1 · Side 1 · updated 2 seconds ago
⚠️ 1 card is not recognized (very new, or not a real passcode).

**Main Deck** · 5
3x Blue-Eyes White Dragon
1x Dark Magician
1x Pot of Greed
...
**YDKE**
ydke://o6lXBaOpVwWjqVcFrvTMAkpwSQM=!0iNuAQ==!TmG8AA==!
```

- **Invalid input:** if the YDKE URI is invalid, the reply explains what is wrong and shows the expected
  format, `ydke://<main>!<extra>!<side>!`.
- **Unknown cards:** cards released after the bot's last card list download show as
  `Unknown card (<passcode>)`. Saving such decks still works.

---

## Quick start (local)

### 1. Create the bot in Discord (one-time)

1. Open the [Discord Developer Portal](https://discord.com/developers/applications) and click **New Application**.
2. In the **Bot** tab, click **Reset Token** and copy the token. The token is the bot's password, so never
   commit it or paste it anywhere public. The bot needs no privileged intents.
3. Create an invite link. In the **Installation** tab (or **OAuth2 → URL Generator**):
   1. Select the scopes `bot` **and** `applications.commands`. The portal's default leaves out `bot`.
   2. Select the permissions `View Channels` and `Send Messages`, which equal the permission integer 3072.
   3. Open the generated link to add the bot to your server.
4. Optional, for development: in Discord, enable **Settings → Advanced → Developer Mode**. Then right-click
   your server and choose **Copy Server ID**. That ID goes into `DEV_GUILD_ID`.

### 2. Configure

```sh
cp bot.properties.example bot.properties
```

| Key | Required | Meaning |
|---|---|---|
| `DISCORD_TOKEN` | yes | Bot token from step 1 |
| `DEV_GUILD_ID` | no | Registers commands on one server only. They update instantly there, but don't work in DMs. Leave it empty in production. |
| `DB_URL` | no | `jdbc:mysql://<host>:<port>/<database>`. Without it, `/deck` is not registered and the rest of the bot works normally. |
| `DB_USER`, `DB_PASSWORD` | with `DB_URL` | Database credentials. Keep them here, not inside the URL. |

The bot reads `bot.properties` from its working directory. Environment variables with the same names take
precedence. `bot.properties` is git-ignored; never commit it.

### 3. Build and run

```sh
mvn package                          # compiles, runs the tests, builds target/ygo-discord-bot.jar
java -jar target/ygo-discord-bot.jar
```

Then type `/ping` in your server. To debug in an IDE, start the bot with
`-Dygodiscordbot.supervise=false`; this runs the bot directly instead of in a child process
(see [Supervisor](#1-startup-and-the-supervisor)).

---

## External APIs and URLs

Everything the bot contacts at runtime. All HTTP requests send the `User-Agent` header
`ygo-discord-bot/1.0 (Discord bot)`.

| What | URL | Used for | How often | Size |
|---|---|---|---|---|
| YGOProDeck banlist | `https://db.ygoprodeck.com/api/v7/cardinfo.php?banlist=tcg` | TCG Forbidden & Limited list | daily, 03:00 Europe/Vienna (hourly retry on failure) | ~0.5 MB |
| YGOProDeck banlist | `https://db.ygoprodeck.com/api/v7/cardinfo.php?banlist=ocg` | OCG Forbidden & Limited list | daily, as above | ~0.5 MB |
| YGOProDeck version | `https://db.ygoprodeck.com/api/v7/checkDBVer.php` | Detects whether any card changed | every 3 days | < 100 bytes |
| YGOProDeck card list | `https://db.ygoprodeck.com/api/v7/cardinfo.php` | Card names for `/deck` (passcode → name, including alternate artworks) | only when the version changed, at most every 3 days | 21 MB, 2.9 MB gzipped |
| Konami Genesys page | `https://www.yugioh-card.com/en/genesys/` | TCG Genesys points (HTML table, parsed with jsoup) | daily, 03:00 Europe/Vienna | one HTML page |
| Discord gateway and REST API | `wss://gateway.discord.gg`, `https://discord.com/api` (through JDA) | Login, receiving slash commands, sending replies, registering commands | permanent connection | n/a |
| MySQL / MariaDB | `jdbc:mysql://172.18.0.1:3306/<db>` (on Waifly) | `decklist`, `players` and the three tournament tables | per `/deck`, `/leaderboard`, `/points`, `/tournament` and `/match` command | tiny |

Not contacted at runtime:
- **[Format Library](https://www.formatlibrary.com):** source of the Goat (April 2005) and Edison (March 2010)
  lists. These formats are frozen, so the lists are bundled in `src/main/resources/banlists/`.
- **`https://discord.com/channels/@me/<channel>`:** only a link the bot puts into its `/banlist` reply.

**YGOProDeck API rules** ([API guide](https://ygoprodeck.com/api-guide/)) and how the bot follows them:

| Rule | How the bot follows it |
|---|---|
| At most 20 requests per second; going over means a 1-hour block | The bot makes a handful of requests per day |
| Store the data locally instead of fetching it repeatedly | Banlists go to `data/banlists.json` and card names to `data/cards.json`, so restarts don't download again |
| Card data is cached on their side for 2 days | `checkDBVer.php` is not cached, so the version check sees changes right away |
| Don't hotlink images | The bot doesn't use images |

References:
- [YGOProDeck API guide](https://ygoprodeck.com/api-guide/)
- [Konami Genesys page](https://www.yugioh-card.com/en/genesys/)
- [Discord API documentation](https://discord.com/developers/docs/intro)
- [Discord message content limit (2000 characters)](https://discord.com/developers/docs/resources/message#create-message)
- [JDA](https://github.com/discord-jda/JDA)
- [HikariCP](https://github.com/brettwooldridge/HikariCP)
- [MySQL Connector/J](https://dev.mysql.com/doc/connector-j/en/)
- [Jackson streaming API](https://github.com/FasterXML/jackson-core)
- [jsoup](https://jsoup.org)

---

## How it works (implementation tutorial)

```
                  ┌──────────────── Supervisor (parent JVM: restarts, memory flags) ───────────────┐
 Discord ◄──JDA──►│ CommandRegistry ─► BanlistCommand ─► BanlistRepository ◄─ BanlistRefresher ◄──── YGOProDeck, Konami
                  │                 ─► DeckCommand ──┬─► DecklistRepository (JDBC) ─────────────────── MySQL
                  │                 ─► PingCommand   └─► CardRepository ◄─ CardRefresher ◄──────────── YGOProDeck
                  │  data/banlists.json, data/cards.json (AtomicFiles)                              │
                  └─────────────────────────────────────────────────────────────────────────────────┘
```

One rule runs through the project: each piece of data has **exactly one writer thread**. Refreshers build a
complete new immutable object and swap it in through a `volatile` field; command handlers only read, without
locks. Database work runs on one dedicated thread.

### 1. Startup and the Supervisor

The hosting panel starts the jar without JVM flags. So `main` first calls `Supervisor.superviseUnlessChild`:
the first JVM becomes a small **parent** that starts the real bot as a **child JVM** with flags that fit the
container:

| Flag | Why |
|---|---|
| `-Xmx` = 30% of container memory (~100 MB) | Live heap is only ~12 MB; the rest of the RAM goes to non-heap memory and the parent |
| `-XX:+UseSerialGC`, `-XX:TieredStopAtLevel=1` | Smallest GC and JIT footprint; fine for a single-CPU, I/O-bound bot |
| `-XX:MaxMetaspaceSize=128m`, `-XX:ReservedCodeCacheSize=48m`, `-Xss512k` | Cap non-heap memory |
| `-XX:+ExitOnOutOfMemoryError` | Exit instead of running on in a broken state |

The parent restarts a crashed child after 5 s, 10 s, 20 s, ... up to 5 min. Exit code 78 marks a
configuration error, such as a missing token, and is not restarted.

`main` then wires everything by hand: config → banlist refresher → (if `DB_URL` is set) database pool and
card refresher → commands → Discord login. One shutdown hook, registered first, closes everything in reverse
order on any exit.

### 2. Commands

Every command implements `impl/command/SlashCommand`, and `CommandRegistry` routes interactions to it by name.
To add one:

```java
public final class HelloCommand implements SlashCommand {
    @Override
    public SlashCommandData data() {
        return Commands.slash("hello", "Say hello")
                .setContexts(InteractionContextType.GUILD, InteractionContextType.BOT_DM);
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {   // runs on JDA's event thread
        event.reply("Hello " + event.getUser().getName()).setEphemeral(true).queue();
    }
}
// in YgoDiscordBot.main:
commands.register(new HelloCommand());
```

Never block JDA's event thread with network or database work. Hand it to an executor and answer with
`deferReply()` plus `getHook().editOriginal(...)`, as `DeckCommand` does.

Output longer than Discord's 2000-character limit is split by `utils/discord/DcMessageUtils` (code-block tables with
repeated headers, or plain lines) and sent in order by `utils/discord/MessageSender` (channel, interaction reply
with follow-ups, or DMs).

### 3. Banlists

- `impl/banlist/YgoProDeckSource` (TCG, OCG) and `impl/banlist/GenesysSource` (Konami's HTML, parsed with jsoup) fetch
  the lists. Suspiciously small results are rejected, so a broken response never replaces a good list.
- `impl/banlist/BanlistRefresher` fetches daily at 03:00 Europe/Vienna, swaps a new immutable `BanlistSnapshot`
  into `BanlistRepository`, and stores it in `data/banlists.json`. A failed source keeps its previous list
  and is retried hourly; a run missed while the bot was offline is caught up at startup.
- `impl/banlist/ListMessages` and `DcMessageUtils` render headings and code-block tables, split at Discord's
  **2000-character limit**.

### 4. YDKE, the deck format

A YDKE URI has the form `ydke://<main>!<extra>!<side>!`. Each section is Base64; decoded, it is a sequence of
**4-byte little-endian unsigned integers**, one per card copy:

```
ydke://o6lXBaOpVwWjqVcF!!!  →  main: o6lXBaOpVwWjqVcF  →  bytes A3 A9 57 05 | A3 A9 57 05 | A3 A9 57 05
                                                      →  0x0557A9A3 = 89631139 (Blue-Eyes White Dragon) ×3
```

**The numbers are artwork IDs, not card IDs.** YDKE stores the passcode of the *artwork* the player picked,
and every alternate artwork has its own passcode:

| Card | Artwork passcodes (YGOProDeck, October 2026) |
|---|---|
| Blue-Eyes White Dragon | 89631139 (original), 89631140 … 89631146 |
| Dark Magician | 46986414 (original), 46986415 … 46986421, 36996508 |
| Ash Blossom & Joyous Spring | 14558127 (original), 14558128 |

So the same card can appear in a deck under different numbers. The bot maps every artwork passcode to its
card's name (see [Card names](#6-card-names)). Copies are grouped per passcode, so 2 original and 1 alternate
Blue-Eyes show as two lines, `2x` and `1x`.

`entity/deck/Ydke` parses and encodes the URIs. Before anything is stored, `DeckCommand` checks that a URI starts
with `ydke://`, decodes completely, and has 1 to 60/15/15 cards.

### 5. Decklist storage (plain JDBC)

The table is created on first use (`impl/deck/DecklistRepository.SCHEMA`, MySQL 8+ / MariaDB 10.2+):

```sql
CREATE TABLE IF NOT EXISTS decklist (
    id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT        NOT NULL,                       -- Discord user ID (snowflake)
    name        VARCHAR(50)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,  -- case-insensitive
    ydke        VARCHAR(4000) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at  BIGINT        NOT NULL,                       -- epoch millis
    updated_at  BIGINT        NOT NULL,
    CONSTRAINT uq_decklist_user_name UNIQUE (user_id, name),  -- one name per user; also the lookup index
    CONSTRAINT chk_decklist_name CHECK (CHAR_LENGTH(name) BETWEEN 1 AND 50),
    CONSTRAINT chk_decklist_ydke CHECK (ydke LIKE 'ydke://%')
) ENGINE = InnoDB
```

- `impl/deck/DecklistRepository` uses plain JDBC with prepared statements, pooled by HikariCP
  (`impl/database/DatabasePool`, at most 2 connections, shared with the leaderboard). The pool starts without a database, so the bot runs even while the database is down.
- A duplicate name is rejected by the unique key (MySQL error 1062) and reported as "name taken".
- All database work (/deck, /leaderboard, /points, /tournament, /match) runs on one `db` thread with a queue of 50; when it is full,
  users get a "busy" reply.
- `impl/command/DatabaseReplies` starts that work only after Discord accepted the deferred reply, so a command that
  timed out (and may be retried) never writes in the background. Its log lines name the user who ran it.

Hibernate was tried and dropped: for one table it cost ~63 MB of extra memory and 2.4 s of startup CPU,
plain JDBC ~12 MB and 0.5 s.

### 6. Card names

YDKE only contains artwork passcodes, so `impl/card/CardRefresher` keeps a passcode → name list of every card:

1. **Every 3 days** it calls `checkDBVer.php` and downloads the full card list only if the version changed.
2. `impl/card/CardSource` reads the 21 MB response token by token with Jackson's streaming parser, straight from
   the gzip stream. It keeps only `id`, `name` and `card_images[].id`, the artwork passcodes, which all map
   to the card's name. The whole document is never in memory; this works within a 16 MB heap.
3. `entity/card/CardNames` stores the ~14,800 passcodes as a sorted `int[]` with a parallel `String[]` (about 3 MB),
   looked up by binary search.
4. The list is saved to `data/cards.json` (~0.5 MB), so restarts don't download again.

A failed check keeps the current names and is retried hourly, up to 3 times.

### 7. Files, shutdown and memory

- `utils/io/AtomicFiles` writes to a temporary file and then renames it, so a crash never leaves a half-written
  file; leftovers of a killed process are deleted at the next start.
- On shutdown, both refreshers interrupt a running download and wait up to 10 s, so a file write in progress
  always completes.
- Measured on Waifly at startup: heap 27 of 100 MB used (live data ~12 MB), metaspace 27 MB, non-heap 36 MB,
  6,155 classes. Worst case with a full heap: ~180 MB for the bot plus ~50 MB for the parent JVM, of 345 MB.
  The bot logs these numbers after every login as `Memory after startup`.

### Project structure

```
src/main/java/at/magi/ygodiscordbot/
  YgoDiscordBot.java        entry point: wiring, Discord login, command registration, shutdown steps
  entity/                   data types and pure logic, no I/O; never depends on impl or utils
    banlist/                Banlist and its records (TCG, OCG, Goat, Edison, Genesys), BanStatus, BanlistSnapshot
    card/                   CardNames (passcode → name), CardCatalog
    deck/                   Decklist, YdkeDeck, Ydke (YDKE parser/encoder)
    leaderboard/            RankedPlayer, LeaderboardPage, Points (0 … 999,999), PointChange
    tournament/             MatchRecord, Standings (incl. tie-breakers), SwissPairer (backtracking), WinnerRule, TournamentPoints
  impl/                     the bot, one package per feature
    command/                SlashCommand, CommandRegistry, PingCommand, DatabaseReplies (DB work after the defer)
    banlist/                BanlistCommand, ListMessages, BanlistRepository, BanlistRefresher, SnapshotFileStore,
                            StaticBanlists, YgoProDeckSource, GenesysSource
    card/                   CardRepository, CardRefresher, CardFileStore, CardSource
    deck/                   DeckCommand, DeckMessages, DecklistRepository (JDBC + schema)
    leaderboard/            LeaderboardCommand, LeaderboardAdminCommand, PointsCommand, LeaderboardMessages,
                            PlayerRepository (JDBC, RANK() per query), PlayerNames (cached names)
    tournament/             TournamentCommand, MatchCommand, TournamentService (all state, db thread only),
                            TournamentRepository (JDBC), TournamentMessages, JdaTournamentAnnouncer, TournamentTimer
    help/                   HelpCommand, HelpMessages
    database/               DatabasePool (HikariCP, shared by deck and leaderboard)
    config/                 BotConfig, DatabaseConfig (bot.properties / environment variables)
    runtime/                Supervisor (child JVM, memory flags, restarts), RestartPolicy
  utils/                    feature-agnostic helpers; never depend on entity or impl
    discord/                DcMessageUtils (2000-character split), MessageSender (ordered sends, DMs)
    http/                   HttpDownloader, ListFetcher
    io/                     AtomicFiles
    json/                   JsonUtils (shared Jackson mapper)
    text/                   LenientDecoder
src/main/resources/banlists/  goat.json, edison.json (frozen lists)
deploy.sh                     build + SFTP upload of the jar
```

---

## Testing

```sh
mvn test
```

- The tests use TestNG and need no network. Sources are tested with stored fixtures, and HTTP with a local server.
- The `DecklistRepository` and `PlayerRepository` tests need a real MySQL or MariaDB database. They are skipped unless these
  variables are set:

  ```sh
  TEST_DB_URL=jdbc:mysql://127.0.0.1:3306/test TEST_DB_USER=... TEST_DB_PASSWORD=... mvn test
  ```

  **The `decklist`, `players` and tournament tables in that database are dropped before each test.** Never point these variables at
  the production database. A throwaway MariaDB for this:

  ```sh
  docker run -d --rm --name ygo-test-db -p 3307:3306 -e MARIADB_ROOT_PASSWORD=test -e MARIADB_DATABASE=ygotest mariadb:11
  TEST_DB_URL=jdbc:mysql://127.0.0.1:3307/ygotest TEST_DB_USER=root TEST_DB_PASSWORD=test mvn test
  ```
- CI (`.github/workflows/build.yml`) starts a MariaDB 11 service container and sets these variables, so the DB tests
  run there too instead of being skipped.
- `PlayerRepositoryConcurrencyTest` (same database) is a race smoke test: 8 threads with their own connections change
  the same players at once. It caught a deadlock when several writers added a new player at the same time.

---

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `Communications link failure … Connection refused` | Wrong database host. On Waifly, use `172.18.0.1:3306`: `db.waifly.com` is the container's own host, and connections from a container to its host's public IP are refused |
| `Access denied for user '…'@'172.18.0.x'` | The database only accepts certain IPs. Recreate it with **Connections from** left empty (the bot connects from Docker's internal network) |
| `DB_URL is not set, /deck is disabled` | Add `DB_URL`, `DB_USER` and `DB_PASSWORD` to the server's `bot.properties` |
| Who changed a player's points? | The console log: every command call is logged once (command, options with user IDs, user, server or DM), every leaderboard write as `Points of player <id> …: before → after [/points add by <user> (<id>)]`, and refused calls with the reason |
| A command doesn't show up in Discord | Reload the Discord app (Ctrl+R). Also check **Server Settings → Integrations → the bot** for hidden commands |
| Commands don't work in DMs | `DEV_GUILD_ID` is set; leave it empty to register commands globally |
| `Bot stopped because of a configuration error` | Missing or invalid `DISCORD_TOKEN` (exit code 78, not restarted) |
| The server is suddenly empty | Waifly reset it after a suspension. Upload `bot.properties` again and run `./deploy.sh` |
| `ssh: connect to host … port 2022: Connection refused` during deploy | The node is restarting; retry after about 20 s |
| `Unknown card (…)` in a deck | The card is newer than the last card list download; the next check within 3 days picks it up |
