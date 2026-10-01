package com.kanan.miniredis.server;

import com.kanan.miniredis.command.CommandHandler;
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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class RedisServer {
    private static final int WORKER_THREADS = 50;

    private final int port;
    private final ExecutorService workers = Executors.newFixedThreadPool(WORKER_THREADS);
    private volatile ServerSocket serverSocket;   // volatile: written by one thread, read by another
    private final Store store = new Store();
    public RedisServer(int port) { this.port = port; }

    public void start() throws IOException {
        CommandHandler handler = new CommandHandler(store);
        serverSocket = new ServerSocket(port);
        store.startSweeper(100);                        // scan for expired keys every 100 ms
        System.out.println("mini-redis listening on port " + port);
        try {
            while (!serverSocket.isClosed()) {
                Socket client = serverSocket.accept();
                workers.submit(() -> handleClient(client, handler));
            }
        } catch (IOException e) {
            if (!serverSocket.isClosed()) throw e;   // real error
            // otherwise: stop() closed the socket on purpose, exit quietly
        } finally {
            workers.shutdown();
            store.stopSweeper();

        }
    }

    /** Stops accepting new clients and lets running ones finish (up to 5 seconds). */
    public void stop() throws IOException, InterruptedException {
        store.stopSweeper();
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
                // (debug println removed on purpose: console output is slow and would ruin benchmarks)
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