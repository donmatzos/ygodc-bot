package at.magi.ygodiscordbot.utils.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
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
            send(exchange, 200, wanted ? gzip(BODY) : BODY.getBytes(StandardCharsets.UTF_8), wanted);
        });
        respond("/plain", 200, BODY.getBytes(StandardCharsets.UTF_8), false);
        respond("/limited", 429, new byte[0], false);
        respond("/big", 200, "x".repeat(5000).getBytes(StandardCharsets.UTF_8), false);
        respond("/bomb", 200, gzip("x".repeat(100_000)), true);
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

    /** Serves the same response on {@code path}; an empty body is sent as no content. */
    private void respond(String path, int status, byte[] body, boolean gzipped) throws IOException {
        server.createContext(path, exchange -> send(exchange, status, body, gzipped));
    }

    private static void send(HttpExchange exchange, int status, byte[] body, boolean gzipped) throws IOException {
        if (gzipped) {
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
        }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @AfterClass(alwaysRun = true)
    public void stopServer() {
        server.stop(0);
    }

    @DataProvider
    public static Object[][] bodyPaths() {
        return new Object[][]{{"gzip-encoded", "/gzip"}, {"uncompressed", "/plain"}};
    }

    @Test(dataProvider = "bodyPaths")
    public void streamsResponses(String name, String path) throws Exception {
        String body = new HttpDownloader().stream(URI.create(base + path), 1 << 20,
                in -> new String(in.readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(body, BODY, name);
    }

    @Test
    public void errorStatusFails() {
        assertThrows(IOException.class, () -> new HttpDownloader().stream(URI.create(base + "/limited"), 1 << 20, in -> null));
    }

    @Test(dataProvider = "bodyPaths")
    public void getReturnsBodyOf200(String name, String path) throws Exception {
        assertEquals(new String(new HttpDownloader().get(URI.create(base + path)), StandardCharsets.UTF_8), BODY, name);
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
        IOException e = expectThrows(IOException.class, () -> http.get(URI.create(base + "/stall")));
        assertTrue(e.getMessage().contains("did not finish within 500 ms"), e.getMessage());
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
