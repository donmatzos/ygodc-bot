package at.magi.ygodiscordbot.source;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Decodes UTF-8, falling back to Windows-1252 for bytes that are not valid UTF-8. */
final class LenientDecoder {

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private LenientDecoder() {
    }

    static String decode(byte[] bytes) {
        CharsetDecoder utf8 = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(bytes);
        CharBuffer out = CharBuffer.allocate(bytes.length);
        while (true) {
            CoderResult result = utf8.decode(in, out, true);
            if (result.isUnderflow()) {
                utf8.flush(out);
                break;
            }
            // Invalid UTF-8: decode the offending bytes one by one as Windows-1252 and continue.
            for (int i = 0; i < result.length(); i++) {
                out.put(new String(new byte[] {in.get()}, WINDOWS_1252));
            }
        }
        return out.flip().toString();
    }
}
