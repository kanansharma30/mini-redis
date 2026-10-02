package com.kanan.miniredis.persistence;

import com.kanan.miniredis.command.CommandHandler;
import com.kanan.miniredis.protocol.RespWriter;
import com.kanan.miniredis.store.Store;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AofPersistenceTest {
    @TempDir Path dir;                                   // JUnit gives each test a fresh temporary folder
    private final AtomicLong now = new AtomicLong(1_000); // fake clock

    private Path aofFile() { return dir.resolve("test.aof"); }
    private Store newStore() { return new Store(now::get); }
    private static String run(CommandHandler h, String... parts) { return h.execute(List.of(parts)); }

    @Test
    void writesAreReplayedIntoAFreshStore() throws Exception {
        try (AofWriter aof = new AofWriter(aofFile(), FsyncPolicy.ALWAYS)) {
            CommandHandler h = new CommandHandler(newStore(), aof);
            run(h, "SET", "a", "1");
            run(h, "SET", "b", "2");
            run(h, "DEL", "a");
        }
        CommandHandler restarted = new CommandHandler(newStore());   // brand-new empty store
        assertEquals(3, AofReplayer.replay(aofFile(), restarted));
        assertEquals("$1\r\n2\r\n", run(restarted, "GET", "b"));
        assertEquals("$-1\r\n", run(restarted, "GET", "a"));
    }

    @Test
    void ttlIsRestoredAsAnAbsoluteDeadline() throws Exception {
        try (AofWriter aof = new AofWriter(aofFile(), FsyncPolicy.NO)) {
            run(new CommandHandler(newStore(), aof), "SET", "k", "v", "EX", "10");   // deadline = 11_000
        }
        now.set(5_000);                                       // the server was "down" for 4 seconds
        CommandHandler restarted = new CommandHandler(newStore());
        AofReplayer.replay(aofFile(), restarted);
        assertEquals(":6\r\n", run(restarted, "TTL", "k"));   // 6 seconds left, not 10
        now.set(11_000);
        assertEquals("$-1\r\n", run(restarted, "GET", "k"));
    }

    @Test
    void keyWhoseDeadlinePassedWhileDownIsGone() throws Exception {
        try (AofWriter aof = new AofWriter(aofFile(), FsyncPolicy.NO)) {
            run(new CommandHandler(newStore(), aof), "SET", "k", "v", "EX", "10");
        }
        now.set(20_000);
        CommandHandler restarted = new CommandHandler(newStore());
        AofReplayer.replay(aofFile(), restarted);
        assertEquals("$-1\r\n", run(restarted, "GET", "k"));
    }

    @Test
    void damagedTailIsIgnoredAndTheFileIsRepaired() throws Exception {
        String good = RespWriter.commandArray(List.of("SET", "a", "1"))
                + RespWriter.commandArray(List.of("SET", "b", "2"));
        String broken = "*3\r\n$3\r\nSET\r\n$1\r\nc";        // cut off mid-command, like a crash
        Files.writeString(aofFile(), good + broken);

        assertEquals(2, AofReplayer.replay(aofFile(), new CommandHandler(newStore())));
        assertEquals(good.getBytes(StandardCharsets.UTF_8).length, Files.size(aofFile()));   // junk removed

        try (AofWriter aof = new AofWriter(aofFile(), FsyncPolicy.NO)) {       // new writes go after clean data
            run(new CommandHandler(newStore(), aof), "SET", "c", "3");
        }
        CommandHandler restarted = new CommandHandler(newStore());
        assertEquals(3, AofReplayer.replay(aofFile(), restarted));
        assertEquals("$1\r\n3\r\n", run(restarted, "GET", "c"));
    }

    @Test
    void readsAndRejectedCommandsAreNotLogged() throws Exception {
        try (AofWriter aof = new AofWriter(aofFile(), FsyncPolicy.NO)) {
            CommandHandler h = new CommandHandler(newStore(), aof);
            run(h, "PING");
            run(h, "GET", "x");
            run(h, "TTL", "x");
            run(h, "SET", "k", "v", "EX", "0");               // rejected
            run(h, "DEL", "nothing");                         // removed nothing
            run(h, "EXPIRE", "nothing", "10");                // key missing
        }
        assertEquals(0, Files.size(aofFile()));
    }

    @Test
    void missingFileMeansAnEmptyStart() throws Exception {
        assertEquals(0, AofReplayer.replay(dir.resolve("nope.aof"), new CommandHandler(newStore())));
    }
}