package com.kanan.miniredis.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads RESP commands (arrays of bulk strings) from a byte stream.
 * Example: *2\r\n$3\r\nGET\r\n$3\r\nfoo\r\n  ->  ["GET", "foo"]
 */
public class RespParser {

    // Safety limits so a bad client cannot make us allocate huge memory.
    private static final int MAX_ARRAY_LENGTH = 1024 * 1024;
    private static final int MAX_BULK_LENGTH = 1024 * 1024; // 1 MB (real Redis allows 512 MB)

    private final InputStream in;

    public RespParser(InputStream in) {
        this.in = in;
    }

    /**
     * Reads the next full command.
     * @return the command as a list of strings, or null if the client closed
     *         the connection cleanly (before starting a new command).
     * @throws ProtocolException if the bytes are not valid RESP
     * @throws IOException on network errors or if the connection closes mid-command
     */
    public List<String> readCommand() throws IOException {
        int first = in.read();
        if (first == -1) {
            return null; // clean disconnect between commands
        }
        if (first != '*') {
            throw new ProtocolException("expected '*' but got '" + (char) first + "'");
        }

        int count = parseLength(readLine(), MAX_ARRAY_LENGTH);
        List<String> parts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            parts.add(readBulkString());
        }
        return parts;
    }

    private String readBulkString() throws IOException {
        int marker = in.read();
        if (marker == -1) {
            throw new EOFException("connection closed in the middle of a command");
        }
        if (marker != '$') {
            throw new ProtocolException("expected '$' but got '" + (char) marker + "'");
        }

        int length = parseLength(readLine(), MAX_BULK_LENGTH);
        byte[] data = in.readNBytes(length);
        if (data.length < length) {
            throw new EOFException("connection closed in the middle of a bulk string");
        }

        // After the data there must be \r\n
        if (in.read() != '\r' || in.read() != '\n') {
            throw new ProtocolException("bulk string not terminated by \\r\\n");
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    /** Reads bytes until \r\n and returns them (without the \r\n) as text. */
    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b == -1) {
                throw new EOFException("connection closed in the middle of a command");
            }
            if (b == '\r') {
                if (in.read() != '\n') {
                    throw new ProtocolException("expected \\n after \\r");
                }
                return sb.toString();
            }
            sb.append((char) b);
        }
    }

    private int parseLength(String text, int max) throws ProtocolException {
        int value;
        try {
            value = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new ProtocolException("invalid length '" + text + "'");
        }
        if (value < 0 || value > max) {
            throw new ProtocolException("length out of range: " + value);
        }
        return value;
    }
}