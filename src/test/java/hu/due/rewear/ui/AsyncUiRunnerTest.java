package hu.due.rewear.ui;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link AsyncUiRunner} off-EDT execution, EDT callback marshaling, error propagation,
 * and keyed latest-wins cancellation.
 */
class AsyncUiRunnerTest {

    @Test
    void runExecutesOffEdtAndDeliversResultOnEdt() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean bgRanOnEdt = new AtomicBoolean(true);
        AtomicBoolean cbRanOnEdt = new AtomicBoolean(false);
        AtomicReference<String> received = new AtomicReference<>();

        AsyncUiRunner.run(null,
                () -> {
                    bgRanOnEdt.set(SwingUtilities.isEventDispatchThread());
                    return "ok-payload";
                },
                value -> {
                    cbRanOnEdt.set(SwingUtilities.isEventDispatchThread());
                    received.set(value);
                    latch.countDown();
                },
                err -> latch.countDown());

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Callback should complete within 5s");
        assertFalse(bgRanOnEdt.get(), "Background task must run off the Swing EDT");
        assertTrue(cbRanOnEdt.get(), "Success callback must run on the Swing EDT");
        assertEquals("ok-payload", received.get());
    }

    @Test
    void runForwardsBackgroundExceptionToErrorHandlerOnEdt() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean errOnEdt = new AtomicBoolean(false);
        AtomicReference<Throwable> caught = new AtomicReference<>();

        AsyncUiRunner.run(null,
                () -> {
                    throw new IllegalStateException("disk full");
                },
                val -> latch.countDown(),
                err -> {
                    errOnEdt.set(SwingUtilities.isEventDispatchThread());
                    caught.set(err);
                    latch.countDown();
                });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Error callback should complete within 5s");
        assertTrue(errOnEdt.get(), "Error callback must run on the Swing EDT");
        assertInstanceOf(IllegalStateException.class, caught.get());
        assertEquals("disk full", caught.get().getMessage());
    }

    @Test
    void runLatestDropsStaleEarlierResultWhenSuperseded() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondDelivered = new CountDownLatch(1);
        CountDownLatch firstBgFinished = new CountDownLatch(1);
        List<String> delivered = Collections.synchronizedList(new ArrayList<>());

        AsyncUiRunner.runLatest("test.inventory.search", null,
                () -> {
                    firstStarted.countDown();
                    releaseFirst.await(5, TimeUnit.SECONDS);
                    firstBgFinished.countDown();
                    return "stale-result";
                },
                delivered::add,
                err -> {});

        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));

        AsyncUiRunner.runLatest("test.inventory.search", null,
                () -> "fresh-result",
                val -> {
                    delivered.add(val);
                    secondDelivered.countDown();
                },
                err -> {});

        assertTrue(secondDelivered.await(5, TimeUnit.SECONDS));
        releaseFirst.countDown();
        assertTrue(firstBgFinished.await(5, TimeUnit.SECONDS));

        // Drain the Swing EDT queue so the stale worker's done() has definitely executed
        SwingUtilities.invokeAndWait(() -> {});

        assertEquals(List.of("fresh-result"), delivered, "Stale superseded result must not be delivered");
    }
}
