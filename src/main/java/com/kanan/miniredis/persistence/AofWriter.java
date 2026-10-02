package com.kanan.miniredis.persistence;

import com.kanan.miniredis.protocol.RespWriter;

import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Appends write commands to the AOF file. Thread-safe. */
public class AofWriter implements Closeable {
    private final FileOutputStream out;
    private final FsyncPolicy policy;
    private final ScheduledExecutorService syncer;   // only used for EVERYSEC
    private boolean dirty;                           // written but not yet fsynced
    private boolean closed;

    public AofWriter(Path path, FsyncPolicy policy) throws IOException {
        this.out = new FileOutputStream(path.toFile(), true);   // true = append, never overwrite
        this.policy = policy;
        if (policy == FsyncPolicy.EVERYSEC) {
            syncer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "aof-fsync");
                t.setDaemon(true);
                return t;
            });
            syncer.scheduleAtFixedRate(this::syncIfDirty, 1, 1, TimeUnit.SECONDS);
        } else {
            syncer = null;
        }
    }

    /** Appends one command. One write call per command keeps each record contiguous. */
    public synchronized void append(List<String> command) {
        try {
            out.write(RespWriter.commandArray(command).getBytes(StandardCharsets.UTF_8));
            dirty = true;
            if (policy == FsyncPolicy.ALWAYS) {
                out.getFD().sync();
                dirty = false;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private synchronized void syncIfDirty() {
        if (!dirty || closed) return;
        try {
            out.getFD().sync();
            dirty = false;
        } catch (IOException e) {
            System.err.println("AOF fsync failed: " + e.getMessage());
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        if (syncer != null) syncer.shutdownNow();
        out.getFD().sync();      // flush whatever is still pending
        out.close();
    }
}