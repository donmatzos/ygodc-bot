package at.magi.ygodiscordbot.source;

import java.io.IOException;

/** Downloads one list from its source. */
@FunctionalInterface
public interface ListFetcher<T> {

    T fetch() throws IOException, InterruptedException;
}
