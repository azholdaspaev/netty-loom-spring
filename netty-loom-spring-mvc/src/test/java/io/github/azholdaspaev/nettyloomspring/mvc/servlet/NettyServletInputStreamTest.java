package io.github.azholdaspaev.nettyloomspring.mvc.servlet;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyServletInputStreamTest {

    private static final byte[] FIVE_BYTES = {'h', 'e', 'l', 'l', 'o'};

    private static NettyServletInputStream stream(byte[] body, long declaredLength) {
        return new NettyServletInputStream(new ByteArrayInputStream(body), declaredLength);
    }

    @Test
    void shouldReportFinishedOnceLastByteIsConsumed() throws Exception {
        var stream = stream(FIVE_BYTES, 5);

        assertEquals(5, stream.read(new byte[5]));

        assertTrue(stream.isFinished(),
            "every declared byte has been handed out; the servlet spec has isFinished() true once "
                + "all the data has been read, without a further read that returns -1");
    }

    @Test
    void shouldReportFinishedOnEmptyBodyBeforeAnyRead() {
        var stream = stream(new byte[0], 0);

        assertTrue(stream.isFinished(), "a body declared empty has nothing left to read");
    }

    @Test
    void shouldNotReportFinishedWhileDeclaredBytesRemain() throws Exception {
        var stream = stream(FIVE_BYTES, 5);

        assertEquals(3, stream.read(new byte[3]));

        assertFalse(stream.isFinished(), "two of five declared bytes are still unread");
    }

    @Test
    void shouldCountSingleByteReadsAgainstDeclaredLength() throws Exception {
        /*
         * 0x00 and 0xFF: read() returns the byte's value, not a count, so a stream that added the
         * return value would count 0 for the first byte and 255 for the second.
         */
        var stream = stream(new byte[] {0x00, (byte) 0xFF}, 2);

        assertEquals(0x00, stream.read());
        assertFalse(stream.isFinished(), "one of two declared bytes is still unread");
        assertEquals(0xFF, stream.read());

        assertTrue(stream.isFinished(), "both declared bytes have been handed out one at a time");
    }

    @Test
    void shouldReportFinishedOnlyAtEofWithoutDeclaredLength() throws Exception {
        var stream = stream(FIVE_BYTES, -1);

        assertEquals(5, stream.read(new byte[5]));
        assertFalse(stream.isFinished(),
            "with no declared length the end of a chunked body is known only once a read returns -1");

        assertEquals(-1, stream.read());
        assertTrue(stream.isFinished(), "a read returned -1");
    }

    @Test
    void shouldReportFinishedAtEofBeforeDeclaredLengthIsReached() throws Exception {
        InputStream truncated = new ByteArrayInputStream(FIVE_BYTES, 0, 3);
        var stream = new NettyServletInputStream(truncated, 5);

        assertEquals(3, stream.read(new byte[5]));
        assertFalse(stream.isFinished(), "two declared bytes are still owed");

        assertEquals(-1, stream.read());
        assertTrue(stream.isFinished(), "a read returned -1, whatever the declared length said");
    }
}
