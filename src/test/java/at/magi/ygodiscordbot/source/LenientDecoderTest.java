package at.magi.ygodiscordbot.source;

import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.testng.Assert.assertEquals;

public class LenientDecoderTest {

    @Test
    public void decodesValidUtf8() {
        assertEquals(LenientDecoder.decode("Ash Blossom – Ø".getBytes(StandardCharsets.UTF_8)), "Ash Blossom – Ø");
    }

    @Test
    public void fallsBackToWindows1252ForInvalidBytes() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes("é K9-".getBytes(StandardCharsets.UTF_8));
        bytes.write(0xD8);
        bytes.write(0xD8);
        bytes.writeBytes(" Lupis".getBytes(StandardCharsets.UTF_8));

        assertEquals(LenientDecoder.decode(bytes.toByteArray()), "é K9-ØØ Lupis");
    }
}
