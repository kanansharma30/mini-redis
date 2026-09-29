package com.kanan.miniredis.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RespParserTest {

    private RespParser parserFor(String input) {
        return new RespParser(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parsesPing() throws IOException {
        RespParser parser = parserFor("*1\r\n$4\r\nPING\r\n");
        assertEquals(List.of("PING"), parser.readCommand());
    }

    @Test
    void parsesSetWithThreeArguments() throws IOException {
        RespParser parser = parserFor("*3\r\n$3\r\nSET\r\n$4\r\nname\r\n$4\r\narun\r\n");
        assertEquals(List.of("SET", "name", "arun"), parser.readCommand());
    }

    @Test
    void valueMayContainCrLf() throws IOException {
        // 4 bytes: a \r \n b. A line-based parser would break here.
        RespParser parser = parserFor("*2\r\n$3\r\nGET\r\n$4\r\na\r\nb\r\n");
        assertEquals(List.of("GET", "a\r\nb"), parser.readCommand());
    }

    @Test
    void parsesTwoCommandsBackToBack() throws IOException {
        RespParser parser = parserFor("*1\r\n$4\r\nPING\r\n*2\r\n$3\r\nGET\r\n$1\r\nk\r\n");
        assertEquals(List.of("PING"), parser.readCommand());
        assertEquals(List.of("GET", "k"), parser.readCommand());
        assertNull(parser.readCommand()); // stream ended cleanly
    }

    @Test
    void rejectsInputNotStartingWithStar() {
        RespParser parser = parserFor("PING\r\n");
        assertThrows(ProtocolException.class, parser::readCommand);
    }

    @Test
    void rejectsNegativeOrHugeLengths() {
        assertThrows(ProtocolException.class, () -> parserFor("*-1\r\n").readCommand());
        assertThrows(ProtocolException.class, () -> parserFor("*1\r\n$999999999\r\n").readCommand());
    }

    @Test
    void rejectsNonNumericLength() {
        assertThrows(ProtocolException.class, () -> parserFor("*abc\r\n").readCommand());
    }

    @Test
    void connectionClosedMidCommandIsAnError() {
        RespParser parser = parserFor("*2\r\n$3\r\nGET\r\n"); // second element missing
        assertThrows(EOFException.class, parser::readCommand);
    }
}