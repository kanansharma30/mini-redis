package com.kanan.miniredis.command;

import com.kanan.miniredis.store.Store;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandHandlerTtlTest {
    private final AtomicLong now = new AtomicLong(0);
    private final CommandHandler handler = new CommandHandler(new Store(now::get));

    private String run(String... parts) { return handler.execute(List.of(parts)); }

    @Test
    void setWithExThenExpires() {
        assertEquals("+OK\r\n", run("SET", "k", "v", "EX", "10"));
        assertEquals(":10\r\n", run("TTL", "k"));
        now.addAndGet(11_000);
        assertEquals("$-1\r\n", run("GET", "k"));
        assertEquals(":-2\r\n", run("TTL", "k"));
    }

    @Test
    void setWithPxAndLowercaseOption() {
        run("SET", "k", "v", "px", "1500");
        now.addAndGet(1499);
        assertEquals("$1\r\nv\r\n", run("GET", "k"));
        now.addAndGet(1);
        assertEquals("$-1\r\n", run("GET", "k"));
    }

    @Test
    void ttlOfKeyWithoutExpiryIsMinusOne() {
        run("SET", "k", "v");
        assertEquals(":-1\r\n", run("TTL", "k"));
    }

    @Test
    void expireCommand() {
        run("SET", "k", "v");
        assertEquals(":1\r\n", run("EXPIRE", "k", "100"));
        assertEquals(":100\r\n", run("TTL", "k"));
        assertEquals(":0\r\n", run("EXPIRE", "nothing", "10"));
    }

    @Test
    void invalidInputGetsCleanErrors() {
        assertEquals("-ERR invalid expire time in 'set' command\r\n", run("SET", "k", "v", "EX", "0"));
        assertEquals("-ERR value is not an integer or out of range\r\n", run("SET", "k", "v", "EX", "abc"));
        assertEquals("-ERR syntax error\r\n", run("SET", "k", "v", "XX", "5"));
        assertEquals("-ERR wrong number of arguments for 'ttl' command\r\n", run("TTL"));
    }
}