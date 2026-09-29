package com.kanan.miniredis.server;

import com.kanan.miniredis.protocol.ProtocolException;
import com.kanan.miniredis.protocol.RespParser;

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
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("mini-redis listening on port " + port);
            Socket client = serverSocket.accept();
            System.out.println("Client connected");
            handleClient(client);
        }
    }

    private void handleClient(Socket client) {
        try (client) {
            // BufferedInputStream: reads big chunks from the network and hands out
            // bytes from memory, instead of one slow system call per byte.
            RespParser parser = new RespParser(new BufferedInputStream(client.getInputStream()));
            OutputStream out = client.getOutputStream();

            while (true) {
                List<String> command;
                try {
                    command = parser.readCommand();
                } catch (ProtocolException e) {
                    write(out, "-ERR Protocol error: " + e.getMessage() + "\r\n");
                    break; // after garbage we cannot trust the stream, so close it
                }

                if (command == null) {
                    break; // client disconnected cleanly
                }
                if (command.isEmpty()) {
                    continue;
                }

                System.out.println("Parsed: " + command);

                // TEMPORARY: real command handling comes in the next step.
                String name = command.get(0).toUpperCase();
                if (name.equals("PING")) {
                    write(out, "+PONG\r\n");
                } else {
                    write(out, "+OK\r\n");
                }
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