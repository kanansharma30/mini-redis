package com.kanan.miniredis.persistence;

/** How often the append-only file is forced onto the physical disk. */
public enum FsyncPolicy {
    ALWAYS,     // after every command: safest, slowest
    EVERYSEC,   // once per second in the background: the usual compromise
    NO          // never; the operating system decides
}