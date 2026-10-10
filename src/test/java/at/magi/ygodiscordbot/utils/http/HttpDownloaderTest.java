package at.magi.ygodiscordbot.utils.http;

import com.sun.net.httpserver.HttpServer;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.zip.GZIPOutputStream;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class HttpDownloaderTest {

    private static final String BODY = "{\"data\":[]}".repeat(1000);

    private HttpServer server;
    private String base;

    @BeforeClass
    public void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/gzip", exchange -> {
            boolean wanted = "gzip".equals(exchange.getRequestHeaders().getFirst("Accept-Encoding"));
            byte[] body = wanted ? gzip(BODY) : BODY.getBytes(StandardCharsets.UTF_8);
            if (wanted) {
                exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            }
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/plain", exchange -> {
            byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/limited", exchange -> {
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        server.createContext("/big", exchange -> {
            byte[] body = "x".repeat(5000).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/bomb", exchange -> {
            byte[] body = gzip("x".repeat(100_000));
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/stall", exchange -> {
            exchange.sendResponseHeaders(200, 10_000);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(new byte[100]);
                out.flush();
                Thread.sleep(5_000);
            } catch (Exception ignored) {
                // client went away
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterClass(alwaysRun = true)
    public void stopServer() {
        server.stop(0);
    }

    @Test
    public void streamsAndUnzipsGzipResponses() throws Exception {
        String body = new HttpDownloader().stream(URI.create(base + "/gzip"), 1 << 20,
                in -> new String(in.readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(body, BODY);
    }

    @Test
    public void streamsUncompressedResponses() throws Exception {
        String body = new HttpDownloader().stream(URI.create(base + "/plain"), 1 << 20,
                in -> new String(in.readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(body, BODY);
    }

    @Test
    public void errorStatusFails() {
        assertThrows(IOException.class, () -> new HttpDownloader().stream(URI.create(base + "/limited"), 1 << 20, in -> null));
    }

    @Test
    public void getReturnsBodyOf200() throws Exception {
        assertEquals(new String(new HttpDownloader().get(URI.create(base + "/plain")), StandardCharsets.UTF_8), BODY);
        assertEquals(new String(new HttpDownloader().get(URI.create(base + "/gzip")), StandardCharsets.UTF_8), BODY);
    }

    @Test
    public void getFailsOnErrorStatus() {
        assertThrows(IOException.class, () -> new HttpDownloader().get(URI.create(base + "/limited")));
    }

    @Test
    public void getFailsOnOversizedBody() {
        HttpDownloader http = new HttpDownloader(Duration.ofSeconds(10), 1000);
        IOException e = expectThrows(IOException.class, () -> http.get(URI.create(base + "/big")));
        assertTrue(e.getMessage().contains("1000"), e.getMessage());
    }

    @Test
    public void getAcceptsBodyExactlyAtTheLimit() throws Exception {
        assertEquals(new HttpDownloader(Duration.ofSeconds(10), 5000).get(URI.create(base + "/big")).length, 5000);
    }

    @Test
    public void streamFailsWhenDecompressedBodyExceedsLimit() {
        assertThrows(IOException.class, () -> new HttpDownloader().stream(URI.create(base + "/bomb"), 10_000,
                in -> in.readAllBytes()));
        assertThrows(IOException.class, () -> new HttpDownloader().stream(URI.create(base + "/bomb"), 10_000,
                in -> {
                    in.transferTo(java.io.OutputStream.nullOutputStream());
                    return null;
                }));
    }

    @Test
    public void streamPassesBodyWithinLimit() throws Exception {
        byte[] body = new HttpDownloader().stream(URI.create(base + "/bomb"), 100_000, InputStream::readAllBytes);
        assertEquals(body.length, 100_000);
    }

    @Test(timeOut = 10_000)
    public void stalledBodyFailsAtDeadline() {
        HttpDownloader http = new HttpDownloader(Duration.ofMillis(500), 1000);
        long start = System.nanoTime();
        assertThrows(IOException.class, () -> http.get(URI.create(base + "/stall")));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 3000);
    }

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }
}
