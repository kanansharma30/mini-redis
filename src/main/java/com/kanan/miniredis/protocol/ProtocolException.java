package com.kanan.miniredis.protocol;

import java.io.IOException;

/** Thrown when a client sends bytes that do not follow the RESP protocol. */
public class ProtocolException extends IOException {
    public ProtocolException(String message) {
        super(message);
    }
}