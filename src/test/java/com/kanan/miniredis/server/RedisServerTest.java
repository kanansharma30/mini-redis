package com.kanan.miniredis.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class RedisServerTest {
    private static final int PORT = 6390;   // not 6379, so tests never clash with a running server
    private RedisServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = new RedisServer(PORT);
        Thread t = new Thread(() -> {
            try { server.start(); } catch (Exception e) { e.printStackTrace(); }
        });
        t.setDaemon(true);
        t.start();
        waitUntilListening();
    }

    @AfterEach
    void stopServer() throws Exception { server.stop(); }

    private void waitUntilListening() throws Exception {
        for (int i = 0; i < 50; i++) {
            try (Socket s = new Socket("localhost", PORT)) { return; }
            catch (Exception e) { Thread.sleep(100); }
        }
        fail("server did not start");
    }

    /** Sends one command and returns the first reply line. */
    private String send(Socket socket, BufferedReader reader, String... parts) throws Exception {
        StringBuilder sb = new StringBuilder("*" + parts.length + "\r\n");
        for (String p : parts) {
            sb.append("$").append(p.getBytes(StandardCharsets.UTF_8).length).append("\r\n")
                    .append(p).append("\r\n");
        }
        OutputStream out = socket.getOutputStream();
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
        String line = reader.readLine();
        if (line.startsWith("$") && !line.equals("$-1")) return reader.readLine(); // bulk: return the data line
        return line;
    }

    @Test
    void twoClientsAreServedAtTheSameTime() throws Exception {
        try (Socket a = new Socket("localhost", PORT);
             Socket b = new Socket("localhost", PORT)) {
            BufferedReader ra = new BufferedReader(new InputStreamReader(a.getInputStream()));
            BufferedReader rb = new BufferedReader(new InputStreamReader(b.getInputStream()));
            assertEquals("+OK", send(a, ra, "SET", "k", "v"));
            assertEquals("v", send(b, rb, "GET", "k"));   // b sees what a wrote, while a is still connected
        }
    }

    @Test
    void manyClientsHammeringTheStoreStayConsistent() throws Exception {
        int clients = 20, opsPerClient = 500;
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int c = 0; c < clients; c++) {
            final int id = c;

            results.add(pool.submit(() -> {
                try (Socket s = new Socket("localhost", PORT)) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream()));
                    for (int i = 0; i < opsPerClient; i++) {
                        String key = "key-" + id + "-" + i;
                        if (!"+OK".equals(send(s, r, "SET", key, "val-" + i))) return false;
                        if (!("val-" + i).equals(send(s, r, "GET", key))) return false;
                    }
                    return true;
                }
            }));
        }
        for (Future<Boolean> f : results) assertTrue(f.get(30, TimeUnit.SECONDS));
        pool.shutdown();
    }
}