package com.kanan.miniredis.server;

import com.kanan.miniredis.persistence.FsyncPolicy;
import com.kanan.miniredis.protocol.RespWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class PersistenceEndToEndTest {
    private static final int PORT = 6391;      // different from RedisServerTest (6390) and the real server (6379)

    @TempDir Path dir;
    private RedisServer server;
    private Thread serverThread;

    /** Starts a real server (with an AOF file) in a background thread and waits until it accepts connections. */
    private void startServer(Path aofFile) throws Exception {
        RedisServer started = new RedisServer(PORT, aofFile, FsyncPolicy.EVERYSEC);
        server = started;
        serverThread = new Thread(() -> {
            try { started.start(); } catch (Exception e) { e.printStackTrace(); }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        for (int i = 0; i < 50; i++) {
            try (Socket probe = new Socket("localhost", PORT)) { return; }
            catch (IOException e) { Thread.sleep(100); }
        }
        fail("server did not start");
    }

    /** Stops the server and waits until it has fully shut down (AOF file flushed and closed). */
    private void stopServer() throws Exception {
        if (server == null) return;
        server.stop();
        serverThread.join(10_000);
        server = null;
    }

    @AfterEach
    void cleanUp() throws Exception { stopServer(); }     // never leave the port occupied, even if a test fails

    @Test
    void dataAndTtlSurviveARestart() throws Exception {
        Path aof = dir.resolve("restart.aof");

        startServer(aof);
        try (TestClient c = new TestClient(PORT)) {
            assertEquals("+OK", c.send("SET", "name", "arun"));
            assertEquals("+OK", c.send("SET", "temp", "x", "EX", "300"));
            assertEquals("+OK", c.send("SET", "gone", "y"));
            assertEquals(":1", c.send("DEL", "gone"));
        }
        stopServer();

        startServer(aof);                                  // a brand-new server process state, same file
        try (TestClient c = new TestClient(PORT)) {
            assertEquals("arun", c.send("GET", "name"));
            assertEquals("(nil)", c.send("GET", "gone"));
            assertEquals("x", c.send("GET", "temp"));
            long ttl = Long.parseLong(c.send("TTL", "temp").substring(1));   // strip the ':'
            assertTrue(ttl > 0 && ttl <= 300, "TTL should still be counting down, was " + ttl);
        }
    }

    @Test
    void serverStartsFromADamagedFileAndKeepsWorking() throws Exception {
        Path aof = dir.resolve("damaged.aof");
        String good = RespWriter.commandArray(List.of("SET", "a", "1"));
        String broken = "*3\r\n$3\r\nSET\r\n$1\r\nb";       // half a command, like a crash mid-write
        Files.writeString(aof, good + broken);

        startServer(aof);
        try (TestClient c = new TestClient(PORT)) {
            assertEquals("1", c.send("GET", "a"));
            assertEquals("(nil)", c.send("GET", "b"));
            assertEquals("+OK", c.send("SET", "c", "3"));
        }
        stopServer();

        startServer(aof);                                  // the repaired file must replay cleanly
        try (TestClient c = new TestClient(PORT)) {
            assertEquals("1", c.send("GET", "a"));
            assertEquals("3", c.send("GET", "c"));
        }
    }

    @Test
    void concurrentWritesToSameKeysReplayToTheSameFinalState() throws Exception {
        Path aof = dir.resolve("concurrent.aof");
        int clients = 16, rounds = 300, sharedKeys = 8;

        startServer(aof);
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        List<Future<?>> futures = new ArrayList<>();
        for (int c = 0; c < clients; c++) {
            final int id = c;
            futures.add(pool.submit(() -> {
                try (TestClient client = new TestClient(PORT)) {
                    for (int i = 0; i < rounds; i++) {
                        // everyone fights over the same 8 keys
                        String reply = client.send("SET", "shared-" + (i % sharedKeys), "client" + id + "-round" + i);
                        assertEquals("+OK", reply);
                    }
                }
                return null;
            }));
        }
        for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);   // rethrows any failure from a worker
        pool.shutdown();

        Map<String, String> before = readSharedKeys(sharedKeys);   // final state in the live server
        stopServer();

        startServer(aof);
        Map<String, String> after = readSharedKeys(sharedKeys);    // final state rebuilt from the log

        assertEquals(sharedKeys, before.size());
        assertEquals(before, after);
    }

    private Map<String, String> readSharedKeys(int count) throws IOException {
        Map<String, String> values = new HashMap<>();
        try (TestClient c = new TestClient(PORT)) {
            for (int i = 0; i < count; i++) values.put("shared-" + i, c.send("GET", "shared-" + i));
        }
        return values;
    }
}