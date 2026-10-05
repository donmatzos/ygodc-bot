package at.magi.ygodiscordbot.source;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.zip.GZIPInputStream;

/** Thin wrapper around the JDK HTTP client. Thread-safe. */
public final class HttpDownloader {

    private static final String USER_AGENT = "ygo-discord-bot/1.0 (Discord bot)";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Reads a response body as it arrives. */
    @FunctionalInterface
    public interface BodyReader<T> {
        T read(InputStream body) throws IOException;
    }

    public byte[] get(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("GET " + uri + " returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    /**
     * Streams the response body to {@code reader}, so a large response is never held in memory as a whole.
     * Asks for gzip, which shrinks JSON responses several times.
     */
    public <T> T stream(URI uri, BodyReader<T> reader) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Encoding", "gzip")
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("GET " + uri + " returned HTTP " + response.statusCode());
            }
            boolean gzip = response.headers().firstValue("Content-Encoding").filter("gzip"::equalsIgnoreCase).isPresent();
            return reader.read(gzip ? new GZIPInputStream(body, 64 * 1024) : body);
        }
    }
}
