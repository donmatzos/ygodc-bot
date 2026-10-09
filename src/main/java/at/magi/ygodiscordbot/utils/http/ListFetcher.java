package at.magi.ygodiscordbot.utils.http;

import java.io.IOException;

/** Downloads one list from its source. */
@FunctionalInterface
public interface ListFetcher<T> {

    T fetch() throws IOException, InterruptedException;
}
