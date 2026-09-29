package com.kanan.miniredis.command;

import com.kanan.miniredis.store.Store;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandHandlerTest {

    private CommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CommandHandler(new Store()); // fresh empty store for every test
    }

    @Test
    void pingReturnsPong() {
        assertEquals("+PONG\r\n", handler.execute(List.of("PING")));
    }

    @Test
    void pingWithMessageEchoesIt() {
        assertEquals("$5\r\nhello\r\n", handler.execute(List.of("PING", "hello")));
    }

    @Test
    void setThenGetReturnsValue() {
        assertEquals("+OK\r\n", handler.execute(List.of("SET", "name", "arun")));
        assertEquals("$4\r\narun\r\n", handler.execute(List.of("GET", "name")));
    }

    @Test
    void getMissingKeyReturnsNull() {
        assertEquals("$-1\r\n", handler.execute(List.of("GET", "nothing")));
    }

    @Test
    void setOverwritesExistingValue() {
        handler.execute(List.of("SET", "k", "old"));
        handler.execute(List.of("SET", "k", "new"));
        assertEquals("$3\r\nnew\r\n", handler.execute(List.of("GET", "k")));
    }

    @Test
    void delReturnsNumberOfKeysActuallyRemoved() {
        handler.execute(List.of("SET", "a", "1"));
        handler.execute(List.of("SET", "b", "2"));
        assertEquals(":2\r\n", handler.execute(List.of("DEL", "a", "b", "c")));
        assertEquals("$-1\r\n", handler.execute(List.of("GET", "a")));
    }

    @Test
    void commandNamesAreCaseInsensitive() {
        assertEquals("+OK\r\n", handler.execute(List.of("set", "k", "v")));
        assertEquals("$1\r\nv\r\n", handler.execute(List.of("Get", "k")));
    }

    @Test
    void keysAreCaseSensitive() {
        handler.execute(List.of("SET", "Name", "arun"));
        assertEquals("$-1\r\n", handler.execute(List.of("GET", "name")));
    }

    @Test
    void wrongArgumentCountsReturnErrors() {
        assertEquals("-ERR wrong number of arguments for 'get' command\r\n",
                handler.execute(List.of("GET")));
        assertEquals("-ERR wrong number of arguments for 'set' command\r\n",
                handler.execute(List.of("SET", "onlykey")));
        assertEquals("-ERR wrong number of arguments for 'del' command\r\n",
                handler.execute(List.of("DEL")));
    }

    @Test
    void unknownCommandReturnsError() {
        assertEquals("-ERR unknown command 'FOO'\r\n", handler.execute(List.of("FOO")));
    }

    @Test
    void multiByteValueUsesByteLengthInReply() {
        handler.execute(List.of("SET", "k", "é"));
        assertEquals("$2\r\né\r\n", handler.execute(List.of("GET", "k"))); // 1 char, 2 bytes
    }
}