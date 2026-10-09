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
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

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
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterClass(alwaysRun = true)
    public void stopServer() {
        server.stop(0);
    }

    @Test
    public void streamsAndUnzipsGzipResponses() throws Exception {
        String body = new HttpDownloader().stream(URI.create(base + "/gzip"),
                in -> new String(in.readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(body, BODY);
    }

    @Test
    public void streamsUncompressedResponses() throws Exception {
        String body = new HttpDownloader().stream(URI.create(base + "/plain"),
                in -> new String(in.readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(body, BODY);
    }

    @Test
    public void errorStatusFails() {
        assertThrows(IOException.class, () -> new HttpDownloader().stream(URI.create(base + "/limited"), in -> null));
    }

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }
}
