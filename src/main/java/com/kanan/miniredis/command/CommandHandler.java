package com.kanan.miniredis.command;

import com.kanan.miniredis.protocol.RespWriter;
import com.kanan.miniredis.store.Store;

import java.util.List;
import java.util.Locale;

/** Executes one parsed command against the store and returns the RESP reply. */
public class CommandHandler {

    private final Store store;

    public CommandHandler(Store store) {
        this.store = store;
    }

    public String execute(List<String> command) {
        if (command.isEmpty()) {
            return RespWriter.error("ERR empty command");
        }

        // Command names are case-insensitive (set = SET), keys and values are not.
        String name = command.get(0).toUpperCase(Locale.ROOT);
        List<String> args = command.subList(1, command.size());

        return switch (name) {
            case "PING" -> ping(args);
            case "SET" -> set(args);
            case "GET" -> get(args);
            case "DEL" -> del(args);
            case "EXPIRE" -> expire(args);
            case "TTL" -> ttl(args);
            default -> RespWriter.error("ERR unknown command '" + command.get(0) + "'");
        };
    }

    private String ping(List<String> args) {
        if (args.isEmpty()) {
            return RespWriter.simpleString("PONG");
        }
        if (args.size() == 1) {
            return RespWriter.bulkString(args.get(0)); // PING hello -> "hello"
        }
        return wrongArgs("ping");
    }

    private String set(List<String> args) {
        if (args.size() == 2) {
            store.set(args.get(0), args.get(1));
            return RespWriter.simpleString("OK");
        }
        if (args.size() != 4) return wrongArgs("set");

        String option = args.get(2).toUpperCase(Locale.ROOT);
        if (!option.equals("EX") && !option.equals("PX")) return RespWriter.error("ERR syntax error");
        Long amount = parseLong(args.get(3));
        if (amount == null) return RespWriter.error("ERR value is not an integer or out of range");
        Long ttlMillis = toMillis(amount, option.equals("EX"));
        if (ttlMillis == null) return RespWriter.error("ERR invalid expire time in 'set' command");

        store.set(args.get(0), args.get(1), ttlMillis);
        return RespWriter.simpleString("OK");
    }

    private String get(List<String> args) {
        if (args.size() != 1) {
            return wrongArgs("get");
        }
        return RespWriter.bulkString(store.get(args.get(0))); // null -> $-1
    }

    private String del(List<String> args) {
        if (args.isEmpty()) {
            return wrongArgs("del");
        }
        int removed = 0;
        for (String key : args) {
            if (store.delete(key)) {
                removed++;
            }
        }
        return RespWriter.integer(removed);
    }

    private String wrongArgs(String commandName) {
        return RespWriter.error("ERR wrong number of arguments for '" + commandName + "' command");
    }
    private String expire(List<String> args) {
        if (args.size() != 2) return wrongArgs("expire");
        Long seconds = parseLong(args.get(1));
        if (seconds == null) return RespWriter.error("ERR value is not an integer or out of range");
        Long ttlMillis = toMillis(seconds, true);
        if (ttlMillis == null) return RespWriter.error("ERR invalid expire time in 'expire' command");
        return RespWriter.integer(store.expire(args.get(0), ttlMillis) ? 1 : 0);
    }

    private String ttl(List<String> args) {
        if (args.size() != 1) return wrongArgs("ttl");
        long ms = store.ttlMillis(args.get(0));
        if (ms < 0) return RespWriter.integer(ms);          // -1 or -2
        return RespWriter.integer((ms + 500) / 1000);       // round to nearest second
    }

    /** Returns null if the text is not a valid integer. */
    private Long parseLong(String text) {
        try { return Long.parseLong(text); }
        catch (NumberFormatException e) { return null; }
    }

    /** Converts to milliseconds. Returns null if not positive or too large. */
    private Long toMillis(long amount, boolean isSeconds) {
        if (amount <= 0) return null;
        try { return isSeconds ? Math.multiplyExact(amount, 1000L) : amount; }
        catch (ArithmeticException e) { return null; }       // overflow
    }
}