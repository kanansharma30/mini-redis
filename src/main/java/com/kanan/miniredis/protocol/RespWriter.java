package com.kanan.miniredis.protocol;

import java.nio.charset.StandardCharsets;
import java.util.List;
/** Builds RESP-formatted replies as text. */
public final class RespWriter {

    /** Encodes a command as a RESP array of bulk strings (the format clients send). */
    public static String commandArray(List<String> parts) {
        StringBuilder sb = new StringBuilder("*").append(parts.size()).append("\r\n");
        for (String part : parts) sb.append(bulkString(part));
        return sb.toString();
    }

    private RespWriter() {
        // utility class: static methods only, never instantiated
    }

    public static String simpleString(String text) {
        return "+" + text + "\r\n";
    }

    public static String error(String message) {
        return "-" + message + "\r\n";
    }

    public static String integer(long value) {
        return ":" + value + "\r\n";
    }

    /** Pass null to get the "null bulk string" (what GET returns for a missing key). */
    public static String bulkString(String text) {
        if (text == null) {
            return "$-1\r\n";
        }
        int byteLength = text.getBytes(StandardCharsets.UTF_8).length;
        return "$" + byteLength + "\r\n" + text + "\r\n";
    }
}