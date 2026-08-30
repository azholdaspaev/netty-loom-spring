package io.github.azholdaspaev.nettyloomspring.core.handler;

import io.github.azholdaspaev.nettyloomspring.core.support.ReleaseFailingContent;
import io.github.azholdaspaev.nettyloomspring.core.support.SpinWait;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpRequestBodyStreamTest {

    private static final Duration LIMIT = Duration.ofSeconds(5);

    @Test
    void shouldReadWhatWasOfferedBeforeTheReadBegan() throws Exception {
        HttpRequestBodyStream body = new HttpRequestBodyStream(() -> { });
        body.offer(content("hello "));
        body.offer(last("world"));

        assertEquals("hello world", new String(body.readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void shouldReportEndOfBodyOnceTheLastContentIsDrained() throws Exception {
        HttpRequestBodyStream body = new HttpRequestBodyStream(() -> { });
        body.offer(last("ab"));

        assertEquals('a', body.read());
        assertEquals('b', body.read());
        assertEquals(-1, body.read(), "the last content ends the body");
    }

    @Test
    void shouldReleaseEveryChunkItHandsOut() throws Exception {
        HttpRequestBodyStream body = new HttpRequestBodyStream(() -> { });
        HttpContent first = content("one");
        HttpContent second = last("two");
        body.offer(first);
        body.offer(second);

        body.readAllBytes();

        assertEquals(0, first.refCnt(), "a drained chunk must be released exactly once");
        assertEquals(0, second.refCnt());
    }

    @Test
    void shouldBlockTheReaderUntilContentArrives() throws Exception {
        HttpRequestBodyStream body = new HttpRequestBodyStream(() -> { });
        AtomicInteger read = new AtomicInteger(Integer.MIN_VALUE);
        Thread reader = Thread.ofVirtual().start(() -> read.set(readOne(body)));

        SpinWait.until(() -> reader.getState() == Thread.State.WAITING, LIMIT,
            "the reader must wait for content rather than report an empty body");
        body.offer(last("z"));
        reader.join();

        assertEquals('z', read.get());
    }

    @Test
    void shouldReportQueuedBytesAsAvailable() throws Exception {
        HttpRequestBodyStream body = new HttpRequestBodyStream(() -> { });
        assertEquals(0, body.available(), "nothing has arrived yet");

        body.offer(content("abcd"));
        assertEquals(4, body.available());

        body.read();
        assertEquals(3, body.available(), "available must follow the bytes still readable");
    }

    @Test
    void shouldReleaseQueuedContentWhenTheClientDisappears() {
        HttpRequestBodyStream body = new HttpRequestBodyStream(() -> { });
        HttpContent queued = content("unread");
        body.offer(queued);

        body.fail(new ClosedChannelException());

        assertEquals(0, queued.refCnt(), "content nobody will read must not outlive the connection");
    }

    @Test
    void shouldRaiseTheFailureItWasGivenRatherThanReportACleanEnd() {
        HttpRequestBodyStream body = new HttpRequestBodyStream(() -> { });
        body.fail(new ClosedChannelException());

        IOException raised = assertThrows(IOException.class, body::read);
        assertInstanceOf(ClosedChannelException.class, raised,
            "a truncated upload must not read as an ordinary end of body");
    }

    @Test
    void shouldWakeABlockedReaderWhenItFails() throws Exception {
        HttpRequestBodyStream body = new HttpRequestBodyStream(() -> { });
        AtomicReference<Throwable> raised = new AtomicReference<>();
        Thread reader = Thread.ofVirtual().start(() -> {
            try {
                body.read();
            } catch (Throwable failure) {
                raised.set(failure);
            }
        });

        SpinWait.until(() -> reader.getState() == Thread.State.WAITING, LIMIT, "the reader must be waiting");
        body.fail(new ClosedChannelException());
        reader.join();

        assertInstanceOf(ClosedChannelException.class, raised.get());
    }

    @Test
    void shouldReleaseContentOfferedAfterTheDispatchClosedIt() throws Exception {
        HttpRequestBodyStream body = new HttpRequestBodyStream(() -> { });
        body.close();

        HttpContent late = content("ignored");
        body.offer(late);

        assertEquals(0, late.refCnt(), "a body nobody is reading any more must not accumulate");
    }

    @Test
    void shouldReleaseTheChunkItWasPartWayThroughWhenClosed() throws Exception {
        HttpRequestBodyStream body = new HttpRequestBodyStream(() -> { });
        HttpContent partlyRead = content("abcd");
        body.offer(partlyRead);
        body.read();

        body.close();

        assertEquals(0, partlyRead.refCnt(), "the chunk being read is released by the reader that owns it");
    }

    @Test
    void shouldReleaseTheRestOfTheQueueWhenOneChunkFailsItsRelease() {
        HttpRequestBodyStream body = new HttpRequestBodyStream(() -> { });
        body.offer(new ReleaseFailingContent());
        HttpContent behindIt = content("still queued");
        body.offer(behindIt);

        assertThrows(IllegalStateException.class, body::close);

        assertEquals(0, behindIt.refCnt(),
            "close() is one-shot, so a chunk skipped by a failing release can never be freed by anyone");
    }

    @Test
    void shouldReleaseTheChunkItWasPartWayThroughWhenTheQueueFailsItsRelease() throws Exception {
        HttpRequestBodyStream body = new HttpRequestBodyStream(() -> { });
        HttpContent partlyRead = content("abcd");
        body.offer(partlyRead);
        body.read();
        body.offer(new ReleaseFailingContent());

        assertThrows(IllegalStateException.class, body::close);

        assertEquals(0, partlyRead.refCnt(),
            "the chunk being read is the reader's to free, whatever the queue's cleanup did");
    }

    @Test
    void shouldNotQueueAnEmptyLastContentButStillEndTheBody() throws Exception {
        HttpRequestBodyStream body = new HttpRequestBodyStream(() -> { });
        body.offer(LastHttpContent.EMPTY_LAST_CONTENT);

        assertEquals(0, body.available());
        assertEquals(-1, body.read(), "a bodyless request ends where it starts");
    }

    @Test
    void shouldWithholdRoomOnceTheQueuePassesItsHighWatermark() {
        HttpRequestBodyStream body = new HttpRequestBodyStream(() -> { });
        assertTrue(body.hasRoom());

        body.offer(content("x".repeat(HttpRequestBodyStream.HIGH_WATERMARK_BYTES)));

        assertTrue(!body.hasRoom(), "reads must stop being issued once the consumer is behind");
    }

    @Test
    void shouldAskForMoreOnlyWhenTheQueueDrainsBelowTheLowWatermark() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpRequestBodyStream body = new HttpRequestBodyStream(requests::incrementAndGet);
        body.offer(content("x".repeat(HttpRequestBodyStream.HIGH_WATERMARK_BYTES)));

        body.read(new byte[HttpRequestBodyStream.HIGH_WATERMARK_BYTES
            - HttpRequestBodyStream.LOW_WATERMARK_BYTES - 1]);
        assertEquals(0, requests.get(), "still above the low watermark, so no read is owed");

        body.read(new byte[2]);
        assertEquals(1, requests.get(), "crossing the low watermark re-arms the read");

        body.read(new byte[8]);
        assertEquals(1, requests.get(), "one crossing must not re-arm the read once per chunk");
    }

    private static int readOne(HttpRequestBodyStream body) {
        try {
            return body.read();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static HttpContent content(String text) {
        return new DefaultHttpContent(buffer(text));
    }

    private static HttpContent last(String text) {
        return new DefaultLastHttpContent(buffer(text));
    }

    private static ByteBuf buffer(String text) {
        return Unpooled.copiedBuffer(text, StandardCharsets.UTF_8);
    }
}
