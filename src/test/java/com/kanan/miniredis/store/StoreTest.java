package com.kanan.miniredis.store;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class StoreTest {
    private final AtomicLong now = new AtomicLong(1_000);          // fake clock we control
    private final Store store = new Store(now::get);

    @Test
    void keyDisappearsExactlyAtItsDeadline() {
        store.set("k", "v", 1000);
        now.set(1_999);
        assertEquals("v", store.get("k"));
        now.set(2_000);
        assertNull(store.get("k"));
    }

    @Test
    void ttlReportsMissingNoExpiryAndRemaining() {
        assertEquals(-2, store.ttlMillis("missing"));
        store.set("plain", "v");
        assertEquals(-1, store.ttlMillis("plain"));
        store.set("temp", "v", 5000);
        now.addAndGet(2000);
        assertEquals(3000, store.ttlMillis("temp"));
    }

    @Test
    void plainSetClearsAnOldTtl() {
        store.set("k", "v", 1000);
        store.set("k", "v2");
        now.addAndGet(5000);
        assertEquals("v2", store.get("k"));
        assertEquals(-1, store.ttlMillis("k"));
    }

    @Test
    void expireWorksOnlyOnExistingKeys() {
        assertFalse(store.expire("missing", 1000));
        store.set("k", "v");
        assertTrue(store.expire("k", 1000));
        now.addAndGet(1000);
        assertNull(store.get("k"));
    }

    @Test
    void deletingAnExpiredKeyCountsAsNotThere() {
        store.set("k", "v", 100);
        now.addAndGet(100);
        assertFalse(store.delete("k"));
    }

    @Test
    void sweepRemovesOnlyExpiredKeys() {
        store.set("a", "1", 100);
        store.set("b", "2");
        now.addAndGet(100);
        assertEquals(1, store.removeExpired());
        assertEquals("2", store.get("b"));
    }

    @Test
    void backgroundSweeperCleansUpWithoutAnyAccess() throws Exception {
        Store real = new Store();                                   // real clock this time
        real.startSweeper(20);
        try {
            real.set("k", "v", 50);
            Thread.sleep(300);
            assertEquals(0, real.removeExpired());                 // nothing left: the sweeper already did it
        } finally {
            real.stopSweeper();
        }
    }
}