package com.kanan.miniredis;

import com.kanan.miniredis.server.RedisServer;

public class Main {
    public static void main(String[] args) throws Exception {
        int port = 6379;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port: " + args[0]);
                System.exit(1);
            }
        }
        new RedisServer(port).start();
    }
}