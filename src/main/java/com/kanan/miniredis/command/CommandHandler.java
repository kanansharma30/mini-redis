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
        if (args.size() != 2) {
            return wrongArgs("set");
        }
        store.set(args.get(0), args.get(1));
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
}