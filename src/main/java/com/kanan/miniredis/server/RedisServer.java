package com.kanan.miniredis.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class RedisServer {
    private final int port;

    public RedisServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("mini-redis listening on port " + port);
            try (Socket client = serverSocket.accept()) {
                System.out.println("Client connected");
                handleClient(client);
            }
        }
    }

    private void handleClient(Socket client) throws IOException {
        InputStream in = client.getInputStream();
        OutputStream out = client.getOutputStream();
        byte[] buffer = new byte[1024];
        int n;
        while ((n = in.read(buffer)) != -1) {
            String received = new String(buffer, 0, n);
            System.out.println("Received: "
                    + received.replace("\r", "\\r").replace("\n", "\\n"));
            out.write("+PONG\r\n".getBytes());
            out.flush();
        }
        System.out.println("Client disconnected");
    }
}