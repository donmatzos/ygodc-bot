package at.magi.ygodiscordbot.utils.http;

import at.magi.ygodiscordbot.utils.concurrent.DaemonThreads;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

/**
 * Thin wrapper around the JDK HTTP client. Thread-safe.
 *
 * <p>Every download has an overall deadline (headers and body together) and a limit for the decompressed body
 * size: a stalled server or a gzip bomb ends in an {@link IOException} instead of blocking a refresher thread
 * forever or exhausting the heap.
 */
public final class HttpDownloader {

    private static final String USER_AGENT = "ygo-discord-bot/1.0 (Discord bot)";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    /** Version checks and banlist pages are far smaller. */
    private static final long GET_LIMIT = 10L * 1024 * 1024;

    /** Closes response bodies whose deadline passed; daemon so it never keeps the JVM alive. */
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(DaemonThreads.named("http-body-deadline"));

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Duration timeout;
    private final long getLimit;

    public HttpDownloader() {
        this(REQUEST_TIMEOUT, GET_LIMIT);
    }

    HttpDownloader(Duration timeout, long getLimit) {
        this.timeout = timeout;
        this.getLimit = getLimit;
    }

    /** Reads a response body as it arrives. */
    @FunctionalInterface
    public interface BodyReader<T> {
        T read(InputStream body) throws IOException;
    }

    /** Downloads a small response (at most 10 MB after decompression) into memory. */
    public byte[] get(URI uri) throws IOException, InterruptedException {
        return stream(uri, getLimit, InputStream::readAllBytes);
    }

    /**
     * Streams the response body to {@code reader}, so a large response is never held in memory as a whole.
     * Asks for gzip, which shrinks JSON responses several times. Fails with an {@link IOException} if the
     * decompressed body exceeds {@code maxBytes} or the whole download takes longer than the deadline.
     */
    public <T> T stream(URI uri, long maxBytes, BodyReader<T> reader) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Encoding", "gzip")
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        AtomicBoolean expired = new AtomicBoolean();
        ScheduledFuture<?> watchdog = null;
        try (InputStream body = response.body()) {
            watchdog = WATCHDOG.schedule(() -> {
                expired.set(true);
                closeQuietly(body);
            }, Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (response.statusCode() != 200) {
                throw new IOException("GET " + uri + " returned HTTP " + response.statusCode());
            }
            boolean gzip = response.headers().firstValue("Content-Encoding").filter("gzip"::equalsIgnoreCase).isPresent();
            InputStream decoded = gzip ? new GZIPInputStream(body, 64 * 1024) : body;
            return reader.read(new LimitedInputStream(decoded, maxBytes, uri));
        } catch (IOException e) {
            if (expired.get() && !(e.getMessage() != null && e.getMessage().contains("exceeds"))) {
                throw new IOException("GET " + uri + " did not finish within " + timeout.toMillis() + " ms", e);
            }
            throw e;
        } finally {
            if (watchdog != null) {
                watchdog.cancel(false);
            }
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException | RuntimeException ignored) {
            // the reader sees the failure on its next read
        }
    }

    /** Fails instead of delivering more than {@code max} bytes. */
    private static final class LimitedInputStream extends FilterInputStream {
        private final long max;
        private final URI uri;
        private long total;

        LimitedInputStream(InputStream in, long max, URI uri) {
            super(in);
            this.max = max;
            this.uri = uri;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = in.read(buf, off, len);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = in.skip(n);
            if (skipped > 0) {
                count(skipped);
            }
            return skipped;
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        private void count(long n) throws IOException {
            total += n;
            if (total > max) {
                throw new IOException("Response from " + uri + " exceeds the limit of " + max + " bytes");
            }
        }
    }
}
