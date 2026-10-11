package at.magi.ygodiscordbot.utils.http;

import org.slf4j.Logger;

import java.io.IOException;

/** Shared "fetch, log a failure, carry on" handling for the background refreshers. */
public final class Fetching {

    private Fetching() {
    }

    /**
     * Returns what the fetcher delivers, or null if it fails (logged as a warning, so the caller keeps its
     * previous data). Also returns null without fetching if the thread is interrupted, as the refresher is
     * shutting down; an interrupt during the fetch is passed on.
     */
    public static <T> T orNull(Logger log, String what, ListFetcher<T> fetcher) {
        if (Thread.currentThread().isInterrupted()) {
            return null;
        }
        try {
            return fetcher.fetch();
        } catch (IOException | RuntimeException e) {
            log.warn("Could not fetch {}, keeping the previous one", what, e);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
