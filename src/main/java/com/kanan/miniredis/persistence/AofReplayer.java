package com.kanan.miniredis.persistence;

import com.kanan.miniredis.command.CommandHandler;
import com.kanan.miniredis.protocol.ProtocolException;
import com.kanan.miniredis.protocol.RespParser;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** Rebuilds the store by re-running every command saved in the AOF file. */
public final class AofReplayer {
    private AofReplayer() {}

    /** Returns how many commands were replayed. Cuts off a damaged tail of the file. */
    public static int replay(Path path, CommandHandler handler) throws IOException {
        if (!Files.exists(path)) return 0;

        int replayed = 0;
        long validBytes = 0;        // file offset just after the last complete command
        boolean damaged = false;

        CountingInputStream in = new CountingInputStream(new BufferedInputStream(new FileInputStream(path.toFile())));
        try (in) {
            RespParser parser = new RespParser(in);
            while (true) {
                List<String> command;
                try {
                    command = parser.readCommand();
                } catch (EOFException | ProtocolException e) {
                    damaged = true;
                    System.err.println("AOF: damaged data after byte " + validBytes + " (" + e.getMessage() + "), ignoring the rest");
                    break;
                }
                if (command == null) break;           // clean end of file
                handler.execute(command);
                replayed++;
                validBytes = in.getCount();
            }
        }
        if (damaged) {
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
                channel.truncate(validBytes);          // remove the broken tail
            }
        }
        return replayed;
    }

    /** Counts the bytes the parser actually consumed (not what the buffer pre-read). */
    private static final class CountingInputStream extends FilterInputStream {
        private long count;

        CountingInputStream(InputStream in) { super(in); }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) count++;
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            if (n > 0) count += n;
            return n;
        }

        long getCount() { return count; }
    }
}