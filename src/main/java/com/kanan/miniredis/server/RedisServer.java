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

public class RedisServer {

    private final int port;

    public RedisServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        // One store shared by everything, one handler that uses it.
        CommandHandler handler = new CommandHandler(new Store());

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("mini-redis listening on port " + port);
            Socket client = serverSocket.accept();
            System.out.println("Client connected");
            handleClient(client, handler);
        }
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
                    break; // after garbage we cannot trust the stream, so close it
                }

                if (command == null) {
                    break; // client disconnected cleanly
                }

                System.out.println("Parsed: " + command); // debug log (we will remove it before benchmarking)
                write(out, handler.execute(command));
            }
        } catch (IOException e) {
            System.out.println("Connection error: " + e.getMessage());
        }
        System.out.println("Client disconnected");
    }

    private void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}