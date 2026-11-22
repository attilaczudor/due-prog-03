package hu.due.rewear.ui;

import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.Component;
import java.awt.Cursor;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Lightweight off-EDT {@link SwingWorker} helper keeping the Swing UI responsive during
 * heavy SQLite queries, CSV exports, and 250 DPI OpenPDF renders.
 * Supports keyed generation tracking ({@link #runLatest}) so rapid filter or refresh clicks
 * silently discard stale responses instead of overwriting newer UI state.
 */
public final class AsyncUiRunner {

    private static final Map<String, AtomicLong> GENERATIONS = new ConcurrentHashMap<>();

    private AsyncUiRunner() {
    }

    /** Executes {@code backgroundWork} off the EDT, toggles wait cursor on {@code owner}, and delivers result on the EDT. */
    public static <T> void run(Component owner,
                               Callable<T> backgroundWork,
                               Consumer<T> onSuccess,
                               Consumer<Throwable> onError) {
        Objects.requireNonNull(backgroundWork, "backgroundWork");
        setWaitCursor(owner, true);
        SwingWorker<T, Void> worker = new SwingWorker<>() {
            @Override
            protected T doInBackground() throws Exception {
                return backgroundWork.call();
            }

            @Override
            protected void done() {
                setWaitCursor(owner, false);
                try {
                    T result = get();
                    if (onSuccess != null) {
                        onSuccess.accept(result);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ee) {
                    Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                    if (onError != null) {
                        onError.accept(cause);
                    }
                }
            }
        };
        worker.execute();
    }

    /**
     * Keyed latest-wins variant: if another task with the same {@code key} starts before this one
     * completes, the older result is silently dropped so rapid UI refreshes never race out-of-order.
     */
    public static <T> void runLatest(String key,
                                     Component owner,
                                     Callable<T> backgroundWork,
                                     Consumer<T> onSuccess,
                                     Consumer<Throwable> onError) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(backgroundWork, "backgroundWork");
        AtomicLong counter = GENERATIONS.computeIfAbsent(key, k -> new AtomicLong(0L));
        long myGen = counter.incrementAndGet();

        setWaitCursor(owner, true);
        SwingWorker<T, Void> worker = new SwingWorker<>() {
            @Override
            protected T doInBackground() throws Exception {
                return backgroundWork.call();
            }

            @Override
            protected void done() {
                if (counter.get() != myGen) {
                    return; // Superseded by a newer request for the same key
                }
                setWaitCursor(owner, false);
                try {
                    T result = get();
                    if (onSuccess != null) {
                        onSuccess.accept(result);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ee) {
                    Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                    if (onError != null) {
                        onError.accept(cause);
                    }
                }
            }
        };
        worker.execute();
    }

    private static void setWaitCursor(Component owner, boolean waiting) {
        if (owner == null) {
            return;
        }
        Runnable task = () -> owner.setCursor(Cursor.getPredefinedCursor(
                waiting ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR));
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }
}
