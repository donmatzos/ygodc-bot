package at.magi.ygodiscordbot.source;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

final class Fixtures {

    private Fixtures() {
    }

    static byte[] read(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
