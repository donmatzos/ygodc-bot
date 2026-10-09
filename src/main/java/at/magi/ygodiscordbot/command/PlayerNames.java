package at.magi.ygodiscordbot.command;

import at.magi.ygodiscordbot.leaderboard.RankedPlayer;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.utils.Result;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Display names for leaderboard rows. The bot caches no users (light JDA), so names are fetched from Discord;
 * a small LRU cache keeps repeated requests for the same pages from costing up to 20 lookups each. A deleted or
 * unknown user is left out instead of failing the whole page.
 */
public final class PlayerNames {

    /** A few KB at most: two full pages of top players plus some browsing. */
    static final int CAPACITY = 256;
    /** Renamed users show their new name after at most this long. */
    static final Duration TTL = Duration.ofHours(1);

    private record CachedName(String name, long expiresAtMillis) {
    }

    private final Clock clock;
    private final Map<Long, CachedName> cache = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, CachedName> eldest) {
            return size() > CAPACITY;
        }
    };

    public PlayerNames(Clock clock) {
        this.clock = clock;
    }

    /** Looks up the names of all rows, from the cache where possible, and passes them to {@code onDone}. */
    void resolve(JDA jda, List<RankedPlayer> rows, Consumer<Map<Long, String>> onDone, Consumer<Throwable> onError) {
        List<Long> ids = rows.stream().map(RankedPlayer::userId).toList();
        Map<Long, String> names = new HashMap<>(cached(ids));
        List<RestAction<Result<User>>> lookups = new ArrayList<>();
        for (long id : ids) {
            if (!names.containsKey(id)) {
                lookups.add(jda.retrieveUserById(id).mapToResult());
            }
        }
        if (lookups.isEmpty()) {
            onDone.accept(names);
            return;
        }
        RestAction.allOf(lookups).queue(results -> {
            for (Result<User> result : results) {
                if (result.isSuccess()) {
                    User user = result.get();
                    remember(user.getIdLong(), user.getEffectiveName());
                    names.put(user.getIdLong(), user.getEffectiveName());
                }
            }
            onDone.accept(names);
        }, onError);
    }

    /** Cached, not yet expired names of the given users. */
    synchronized Map<Long, String> cached(List<Long> ids) {
        long now = clock.millis();
        Map<Long, String> names = new HashMap<>();
        for (long id : ids) {
            CachedName entry = cache.get(id);
            if (entry == null) {
                continue;
            }
            if (entry.expiresAtMillis() < now) {
                cache.remove(id);
            } else {
                names.put(id, entry.name());
            }
        }
        return names;
    }

    synchronized void remember(long id, String name) {
        cache.put(id, new CachedName(name, clock.millis() + TTL.toMillis()));
    }
}
