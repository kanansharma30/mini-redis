package com.kanan.miniredis.server;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Minimal RESP client for tests: sends one command and returns the reply as a short string. */
class TestClient implements Closeable {
    private final Socket socket;
    private final BufferedReader reader;
    private final OutputStream out;

    TestClient(int port) throws IOException {
        socket = new Socket("localhost", port);
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        out = socket.getOutputStream();
    }

    /** Returns "+OK", ":42", "-ERR ...", "(nil)", or the text of a bulk string. */
    String send(String... parts) throws IOException {
        StringBuilder sb = new StringBuilder("*" + parts.length + "\r\n");
        for (String part : parts) {
            sb.append('$').append(part.getBytes(StandardCharsets.UTF_8).length).append("\r\n")
                    .append(part).append("\r\n");
        }
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();

        String line = reader.readLine();
        if (line == null) throw new IOException("server closed the connection");
        if (line.equals("$-1")) return "(nil)";
        if (line.startsWith("$")) return reader.readLine();   // bulk string: the next line is the data
        return line;
    }

    @Override
    public void close() throws IOException { socket.close(); }
}