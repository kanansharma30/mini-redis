package com.kanan.miniredis.command;

import com.kanan.miniredis.persistence.AofWriter;
import com.kanan.miniredis.protocol.RespWriter;
import com.kanan.miniredis.store.Store;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

public class CommandHandler {
    private static final String NOT_INTEGER = "ERR value is not an integer or out of range";

    private final Store store;
    private final AofWriter aof;                    // null = persistence off
    private final Object writeLock = new Object();  // makes "apply + log" one unit, so file order = apply order

    public CommandHandler(Store store) { this(store, null); }

    public CommandHandler(Store store, AofWriter aof) {
        this.store = store;
        this.aof = aof;
    }

    public String execute(List<String> command) {
        if (command.isEmpty()) return RespWriter.error("ERR empty command");
        String name = command.get(0).toUpperCase(Locale.ROOT);
        List<String> args = command.subList(1, command.size());
        try {
            return switch (name) {
                case "PING" -> ping(args);
                case "GET" -> get(args);
                case "TTL" -> ttl(args);
                case "SET" -> locked(() -> set(args));
                case "DEL" -> locked(() -> del(args));
                case "EXPIRE" -> locked(() -> expire(args));
                case "PEXPIREAT" -> locked(() -> pexpireat(args));
                default -> RespWriter.error("ERR unknown command '" + command.get(0) + "'");
            };
        } catch (UncheckedIOException e) {
            return RespWriter.error("ERR could not write to the append-only file");
        }
    }

    private String locked(Supplier<String> action) {
        synchronized (writeLock) { return action.get(); }
    }

    private void log(List<String> command) {
        if (aof != null) aof.append(command);
    }

    private String ping(List<String> args) {
        if (args.isEmpty()) return RespWriter.simpleString("PONG");
        if (args.size() == 1) return RespWriter.bulkString(args.get(0));
        return wrongArgs("ping");
    }

    private String set(List<String> args) {
        if (args.size() == 2) {
            store.set(args.get(0), args.get(1));
            log(List.of("SET", args.get(0), args.get(1)));
            return RespWriter.simpleString("OK");
        }
        if (args.size() != 4) return wrongArgs("set");

        String key = args.get(0);
        String value = args.get(1);
        String option = args.get(2).toUpperCase(Locale.ROOT);
        if (!option.equals("EX") && !option.equals("PX") && !option.equals("PXAT")) {
            return RespWriter.error("ERR syntax error");
        }
        Long amount = parseLong(args.get(3));
        if (amount == null) return RespWriter.error(NOT_INTEGER);

        Long deadline;
        if (option.equals("PXAT")) {
            deadline = amount > 0 ? amount : null;                 // already absolute
        } else {
            deadline = deadlineFrom(amount, option.equals("EX"));  // relative -> absolute
        }
        if (deadline == null) return RespWriter.error("ERR invalid expire time in 'set' command");

        store.setAt(key, value, deadline);
        log(List.of("SET", key, value, "PXAT", Long.toString(deadline)));   // always logged as an absolute time
        return RespWriter.simpleString("OK");
    }

    private String get(List<String> args) {
        if (args.size() != 1) return wrongArgs("get");
        return RespWriter.bulkString(store.get(args.get(0)));
    }

    private String del(List<String> args) {
        if (args.isEmpty()) return wrongArgs("del");
        int removed = 0;
        for (String key : args) if (store.delete(key)) removed++;
        if (removed > 0) {
            List<String> logged = new ArrayList<>();
            logged.add("DEL");
            logged.addAll(args);
            log(logged);
        }
        return RespWriter.integer(removed);
    }

    private String expire(List<String> args) {
        if (args.size() != 2) return wrongArgs("expire");
        Long seconds = parseLong(args.get(1));
        if (seconds == null) return RespWriter.error(NOT_INTEGER);
        Long deadline = deadlineFrom(seconds, true);
        if (deadline == null) return RespWriter.error("ERR invalid expire time in 'expire' command");

        if (!store.expireAt(args.get(0), deadline)) return RespWriter.integer(0);
        log(List.of("PEXPIREAT", args.get(0), Long.toString(deadline)));
        return RespWriter.integer(1);
    }

    /** PEXPIREAT key epochMillis: used by the AOF, but also a real Redis command. */
    private String pexpireat(List<String> args) {
        if (args.size() != 2) return wrongArgs("pexpireat");
        Long deadline = parseLong(args.get(1));
        if (deadline == null) return RespWriter.error(NOT_INTEGER);
        if (deadline <= 0) return RespWriter.error("ERR invalid expire time in 'pexpireat' command");

        if (!store.expireAt(args.get(0), deadline)) return RespWriter.integer(0);
        log(List.of("PEXPIREAT", args.get(0), Long.toString(deadline)));
        return RespWriter.integer(1);
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

    /** Absolute deadline for a relative EX/PX amount. Null if not positive or too large. */
    private Long deadlineFrom(long amount, boolean isSeconds) {
        if (amount <= 0) return null;
        try {
            long ttlMillis = isSeconds ? Math.multiplyExact(amount, 1000L) : amount;
            return Math.addExact(store.nowMillis(), ttlMillis);
        } catch (ArithmeticException e) {
            return null;                                    // overflow
        }
    }

    private String wrongArgs(String commandName) {
        return RespWriter.error("ERR wrong number of arguments for '" + commandName + "' command");
    }
}