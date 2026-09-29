package com.kanan.miniredis.store;

import java.util.concurrent.ConcurrentHashMap;

/** The in-memory key-value storage. Safe to use from many threads. */
public class Store {

    private final ConcurrentHashMap<String, String> data = new ConcurrentHashMap<>();

    public void set(String key, String value) {
        data.put(key, value);
    }

    /** @return the value, or null if the key does not exist */
    public String get(String key) {
        return data.get(key);
    }

    /** @return true if the key existed and was removed */
    public boolean delete(String key) {
        return data.remove(key) != null;
    }
}