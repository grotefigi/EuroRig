package org.eurorig.app;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A map download runs on its own thread and can outlive the service that started it, so the shared
 * `running`/`status` state is owned by one run at a time. These checks drive the production helpers
 * the service uses (`beginRun`, `ownsRun`, `publish`, `finish`) through the ordering the review named:
 * the earlier thread finishing late, after a later run has taken over.
 */
public class DownloadRunOwnershipChecks {

    @Test
    public void aFinishedRunCannotClearALaterRunsFlag() {
        MapDownloadService.running = true;
        int first = MapDownloadService.beginRun();
        assertTrue(MapDownloadService.ownsRun(first));

        int second = MapDownloadService.beginRun(); // a later download takes over
        assertTrue(MapDownloadService.ownsRun(second));

        MapDownloadService.finish(first); // the earlier thread reaches its finally
        assertTrue("a finished run must not clear the live run's flag", MapDownloadService.running);

        MapDownloadService.finish(second);
        assertFalse("only the owning run clears the flag", MapDownloadService.running);
    }

    @Test
    public void aFinishedRunCannotPublishOverALaterRunsStatus() {
        int first = MapDownloadService.beginRun();
        MapDownloadService.publish(first, "downloading A");
        assertEquals("downloading A", MapDownloadService.status);

        int second = MapDownloadService.beginRun();
        MapDownloadService.publish(second, "downloading B");

        MapDownloadService.publish(first, "Map download: connection reset"); // late callback
        assertEquals("a late callback must not overwrite the live run's status", "downloading B", MapDownloadService.status);

        MapDownloadService.publish(second, "downloading B · 42%");
        assertEquals("downloading B · 42%", MapDownloadService.status);
        MapDownloadService.status = MapDownloadService.IDLE;
    }

    @Test
    public void aRunThatLostOwnershipCanNeverPublishOrClearAgain() throws Exception {
        MapDownloadService.running = true;
        int first = MapDownloadService.beginRun();
        MapDownloadService.publish(first, "run A");
        CountDownLatch stop = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        // The earlier download thread reaching its late update/catch/finally, over and over.
        Thread loser = new Thread(() -> {
            while (stop.getCount() > 0) {
                attempts.incrementAndGet();
                MapDownloadService.publish(first, "run A");
                MapDownloadService.finish(first);
            }
        }, "lost-ownership-run");
        loser.setDaemon(true);
        loser.start();

        int second = MapDownloadService.beginRun(); // a later download takes ownership
        MapDownloadService.publish(second, "run B");
        boolean everStale = false, everCleared = false;
        long deadline = System.nanoTime() + 300_000_000L;
        while (System.nanoTime() < deadline) {
            if ("run A".equals(MapDownloadService.status)) everStale = true;
            if (!MapDownloadService.running) everCleared = true;
        }
        stop.countDown();
        loser.join(5000);

        assertFalse("a run that lost ownership must never publish again", everStale);
        assertFalse("a run that lost ownership must never clear the live flag", everCleared);
        assertTrue("the contention was exercised", attempts.get() > 100);
        MapDownloadService.running = false;
        MapDownloadService.status = MapDownloadService.IDLE;
    }

    @Test
    public void anInstanceThatOwnsNoRunIsNeverTheOwner() {
        // Trace: instance A owns the running download thread; A is timed out and destroyed; a later
        // start creates instance B, which enters the foreground but owns no thread of its own. B must
        // see that it is not the owner (-1), or nothing would ever stop it. After A's thread ends, the
        // next start owns a fresh run again.
        MapDownloadService.running = true;
        int runA = MapDownloadService.beginRun();
        assertTrue("the instance that started the download owns it", MapDownloadService.ownsRun(runA));

        int ownedByNewInstance = -1; // the sentinel a freshly created instance carries
        assertFalse("an instance with no run must not be treated as the owner",
                MapDownloadService.ownsRun(ownedByNewInstance));

        MapDownloadService.finish(runA); // A's thread ends; the flag clears for the owner
        assertFalse(MapDownloadService.running);
        MapDownloadService.running = true;
        int runC = MapDownloadService.beginRun();
        assertTrue("the next start owns its own run", MapDownloadService.ownsRun(runC));
        MapDownloadService.finish(runC);
        assertFalse(MapDownloadService.running);
    }

    @Test
    public void aPauseOnAnInstanceWithoutAClientIsSafe() {
        // A PAUSE intent must not dereference a client that does not exist yet on this instance.
        MapDownloadService.cancelQuietly(null);
        MapDownloadService.cancelQuietly(new org.eurorig.maps.DownloadClient(true));
    }
}
