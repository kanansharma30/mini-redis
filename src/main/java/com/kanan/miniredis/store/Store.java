package com.kanan.miniredis.store;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

public class Store {
    private static final long NO_EXPIRY = -1;

    /** An immutable value plus its absolute deadline in epoch milliseconds. */
    private record Entry(String value, long expiresAtMillis) {
        boolean isExpired(long now) {
            return expiresAtMillis != NO_EXPIRY && now >= expiresAtMillis;
        }
    }

    private final ConcurrentHashMap<String, Entry> data = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private ScheduledExecutorService sweeper;

    public Store() { this(System::currentTimeMillis); }          // real clock
    public Store(LongSupplier clock) { this.clock = clock; }     // fake clock for tests

    /** Plain SET: also removes any old TTL, because the entry is replaced. */
    public void set(String key, String value) {
        data.put(key, new Entry(value, NO_EXPIRY));
    }

    public void set(String key, String value, long ttlMillis) {
        data.put(key, new Entry(value, clock.getAsLong() + ttlMillis));
    }

    public String get(String key) {
        Entry e = data.get(key);
        if (e == null) return null;
        if (e.isExpired(clock.getAsLong())) {
            data.remove(key, e);              // lazy expiry; only if still the same entry
            return null;
        }
        return e.value();
    }

    public boolean delete(String key) {
        Entry e = data.remove(key);
        return e != null && !e.isExpired(clock.getAsLong());   // deleting an expired key counts as "not there"
    }

    /** Sets a TTL on an existing key. Returns false if the key is missing/expired. */
    public boolean expire(String key, long ttlMillis) {
        long now = clock.getAsLong();
        boolean[] updated = {false};
        data.computeIfPresent(key, (k, e) -> {        // runs atomically for this key
            if (e.isExpired(now)) return null;        // returning null removes the entry
            updated[0] = true;
            return new Entry(e.value(), now + ttlMillis);
        });
        return updated[0];
    }

    /** Milliseconds left; -1 = no expiry; -2 = key does not exist. */
    public long ttlMillis(String key) {
        Entry e = data.get(key);
        if (e == null) return -2;
        long now = clock.getAsLong();
        if (e.isExpired(now)) {
            data.remove(key, e);
            return -2;
        }
        if (e.expiresAtMillis() == NO_EXPIRY) return -1;
        return e.expiresAtMillis() - now;
    }

    /** One sweep: deletes every expired entry, returns how many were removed. */
    public int removeExpired() {
        long now = clock.getAsLong();
        int before = data.size();
        data.entrySet().removeIf(en -> en.getValue().isExpired(now));
        return before - data.size();
    }

    public void startSweeper(long periodMillis) {
        sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "expiry-sweeper");
            t.setDaemon(true);                        // never blocks JVM shutdown
            return t;
        });
        sweeper.scheduleAtFixedRate(this::removeExpired, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    public void stopSweeper() {
        if (sweeper != null) sweeper.shutdownNow();
    }
}