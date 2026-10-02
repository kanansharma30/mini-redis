package com.kanan.miniredis.server;

import com.kanan.miniredis.command.CommandHandler;
import com.kanan.miniredis.persistence.AofReplayer;
import com.kanan.miniredis.persistence.AofWriter;
import com.kanan.miniredis.persistence.FsyncPolicy;
import com.kanan.miniredis.protocol.ProtocolException;
import com.kanan.miniredis.protocol.RespParser;
import com.kanan.miniredis.protocol.RespWriter;
import com.kanan.miniredis.store.Store;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class RedisServer {
    private static final int WORKER_THREADS = 50;

    private final int port;
    private final Path aofPath;                 // null = persistence disabled
    private final FsyncPolicy fsyncPolicy;
    private final ExecutorService workers = Executors.newFixedThreadPool(WORKER_THREADS);
    private final Store store = new Store();
    private volatile ServerSocket serverSocket;
    private AofWriter aof;

    /** Server without persistence (used by the tests). */
    public RedisServer(int port) { this(port, null, FsyncPolicy.EVERYSEC); }

    public RedisServer(int port, Path aofPath, FsyncPolicy fsyncPolicy) {
        this.port = port;
        this.aofPath = aofPath;
        this.fsyncPolicy = fsyncPolicy;
    }

    public void start() throws IOException {
        try {
            serverSocket = new ServerSocket(port);        // fail fast if the port is taken

            if (aofPath != null) {
                // 1) replay with a handler that has NO writer, so replaying does not log again
                int replayed = AofReplayer.replay(aofPath, new CommandHandler(store));
                System.out.println("Loaded " + replayed + " commands from " + aofPath);
                // 2) only now start logging new commands
                aof = new AofWriter(aofPath, fsyncPolicy);
            }
            CommandHandler handler = new CommandHandler(store, aof);
            store.startSweeper(100);                      // scan for expired keys every 100 ms

            System.out.println("mini-redis listening on port " + port);
            while (!serverSocket.isClosed()) {
                Socket client = serverSocket.accept();
                workers.submit(() -> handleClient(client, handler));
            }
        } catch (IOException e) {
            if (serverSocket == null || !serverSocket.isClosed()) throw e;   // real error
            // otherwise: stop() closed the socket on purpose, exit quietly
        } finally {
            if (serverSocket != null) serverSocket.close();
            workers.shutdown();
            try { workers.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            store.stopSweeper();
            if (aof != null) aof.close();                 // flushes pending data to disk
        }
    }

    /** Stops accepting new clients; start() then cleans up (sweeper, AOF file). */
    public void stop() throws IOException, InterruptedException {
        if (serverSocket != null) serverSocket.close();   // makes accept() throw
        workers.shutdown();
        workers.awaitTermination(5, TimeUnit.SECONDS);
    }

    private void handleClient(Socket client, CommandHandler handler) {
        try (client) {
            RespParser parser = new RespParser(new BufferedInputStream(client.getInputStream()));
            OutputStream out = client.getOutputStream();
            while (true) {
                List<String> command;
                try {
                    command = parser.readCommand();
                } catch (ProtocolException e) {
                    write(out, RespWriter.error("ERR Protocol error: " + e.getMessage()));
                    break;
                }
                if (command == null) break;
                write(out, handler.execute(command));
            }
        } catch (IOException e) {
            // client vanished or reset the connection: normal in networking
        }
    }

    private void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}