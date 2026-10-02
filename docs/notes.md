Socket – An address for a connection. It's like a phone number: it tells the network where to send data (your IP + port).

Port – A number (0–65535) that picks which app on your machine gets the data. E.g., port 80 = web browser, port 25 = email.

Blocking – The program stops and waits until the network call finishes (e.g., "wait until data arrives"). Nothing else runs on that thread in the meantime.

STEP 1:
Q: What does accept() do, and why does the program sit there?
A: It's the server saying "I'm listening, who's next?" and sleeping until a client knocks. Not stuck — just waiting.

Q: What did PING look like on the wire?
A:

*1\r\n$4\r\nPING\r\n

Server replies +PONG\r\n.

Q: What do *1 and $4 mean?
A:

*1 → "one thing coming"
$4 → "the next 4 bytes are a string"
Q: What did SET name arun look like, and why is it longer?
A:

*3\r\n$3\r\nSET\r\n$4\r\nname\r\n$4\r\narun\r\n

Three strings instead of one (command + key + value). Server replies +OK\r\n.

Q: Why did the server stop after one client disconnected?
A: It accepted one connection, then read from it synchronously. Client leaves → readLine() returns null → loop ends → main() ends. It never gets back to accept().

Q: How to fix it?
A: Loop around accept(), spawn a thread per client. Main thread goes back to listening immediately.

Step 2: RESP parser
Q: Why can't we just read once and call it a command?
A: TCP is a byte stream, not a message protocol.  A single read() might give you half a command, or two commands glued together. You have to loop-read until your framing rules (the *N / $N lengths) say "I have a complete message."

Q: Why read bulk-string content by length instead of scanning for \r\n?
A: Because the content can contain \r\n — it's binary-safe.  If you searched for a delimiter, a value like "hello\r\nworld" would look like two strings. The length prefix ($11) tells you exactly how many bytes to consume, no ambiguity.

Q: What's the difference between readCommand() returning null and throwing EOFException?
A:

* null → the client closed the connection cleanly (sent FIN). You got a complete "no more data" signal between commands. Time to move on.
* EOFException → the connection died mid-command. You were in the middle of parsing (e.g., expected 4 bytes, got 2, then EOF). That's an error — you can't recover a partial command, so you throw it up to the handler to clean up.
Q: Why add MAX_BULK_LENGTH? What does it protect against?
A: Two things:

1. Malicious/buggy client sends $999999999999\r\n — without a cap you'd try to allocate a gigabyte (or more) and OOM the server. A limit says "that's not a real command, reject it."
2. Protocol bugs — if your parser misreads the length (off-by-one, integer overflow), a huge allocation is the first symptom. The cap turns a silent memory leak into a loud, early error.

Step 2b: Store and commands
Q: Why separate Store, CommandHandler, RespWriter, and RedisServer into different classes?
A: Each one has one job. Store holds data, RespWriter formats bytes, CommandHandler decides what to do, RedisServer wires them together. The benefit you actually saw: in tests you can swap Store for a fake or test RespWriter in isolation without spinning up a socket. One class doing everything means every test needs the whole machine.

Q: Why ConcurrentHashMap and not HashMap?
A: Multiple client threads read/write the map at the same time. A plain HashMap under concurrent writes can silently lose entries or even infinite-loop during rehash. ConcurrentHashMap locks at the bucket level — reads are lock-free, writes only block the one bucket being touched.

Q: What does GET return for a missing key, and how is that different from an empty string?
A:
1. Missing key → $-1\r\n (null bulk string) → client sees nil
2. Empty string → $0\r\n\r\n → client sees ""
They're different on purpose: "I don't have that key" ≠ "that key exists and its value is nothing." Without the distinction you couldn't tell a missing key from a key you explicitly set to "".

Q: Why validate argument count in every command?
A: Because the client is a peer, not a friend. If someone sends SET with zero args or GET with three, and you don't check, you'll either ArrayIndexOutOfBoundsException or silently do the wrong thing. A quick if (args.length != 3) return error turns a crash into a clean -ERR wrong number of arguments reply.

STEP 3:
Q: Why can't one thread both accept() and serve a client?
A: accept() blocks until a connection arrives. Once you start reading/writing for that client, you're stuck in I/O and can't call accept() again. No new clients get in.

Q: What blocks?
A: The thread blocks on accept() waiting for a new connection, or on read()/write() waiting for data from the current client. Either way, it can't do both at once.

Q: What goes wrong with thread-per-client at 10,000 clients?
A: ~10 GB of stack memory, CPU drowns in context switches, OS runs out of file descriptors. The C10K problem.

Q: How does a fixed pool fix that?
A: Caps the number of live threads (e.g., 20), bounding memory and context-switch cost.

Q: What's the new weakness of a fixed pool?
A: If all workers are blocked on slow/long-lived connections, new clients wait indefinitely even though the CPU is idle. Pool starvation.

Q: Which objects are shared vs. per-connection?
A: Shared: ServerSocket, the thread pool, the client map. Per-connection: each client's socket, its streams, its session state.

Q: Why ConcurrentHashMap?
A: Multiple workers insert/remove entries simultaneously. A plain HashMap under concurrent writes corrupts. ConcurrentHashMap is safe without locking the whole map.

Q: How does stop() wake a thread blocked in accept()?
A: Call serverSocket.close() from another thread. The blocked accept() immediately throws SocketException, the catch fires, the loop exits.

STEP 4:
Q: Why store an absolute deadline instead of a "seconds left" counter?
A: A countdown is fragile — if the process gets paused (GC, sleep, clock drift), the number is stale. An absolute deadline is computed once at write time (now + ttl) and compared against now() at read time. It's immune to pauses and survives restarts. Redis does exactly this: stores an absolute Unix timestamp in milliseconds, not a ticking counter.

Q: What's the difference between lazy and active expiry, and why does Redis need both?
A: Lazy = check expiry only when a key is accessed; if expired, delete it and return nil.  Active = a background cycle (10×/sec) samples 20 random keys and deletes any that are expired.  Redis needs both because lazy alone means a never-accessed key holds memory forever; active alone would require scanning every key, which is too expensive. Together: lazy guarantees correctness on read, active reclaims memory for keys nobody touches.

Q: Why data.remove(key, entry) rather than data.remove(key)?
A: The two-arg form atomically removes the entry only if the value is still the one you checked.  The race: between your "is this entry expired?" check and your remove(key), another thread could have written a fresh, non-expired value for the same key. remove(key) would nuke that new value too. remove(key, entry) says "only delete it if it's still the old expired one I was looking at."

Q: Why does the Store take a Clock as a constructor argument?
A: Dependency injection. In production you pass Clock.systemUTC(). In tests you inject a fake clock you control — so you can "advance time" by a few seconds with one method call instead of actually sleeping. Tests become fast, deterministic, and you can test the exact boundary (e.g., "does it expire at exactly 30 s or 30.001 s?") without any real waiting.


STEP 6:
Q: Why log commands to a file and replay them, and why is the file "append-only"?
A: The AOF (Append-Only File) is a write-ahead log: every mutating command is written to disk before it's applied in memory. On restart, you replay the file to rebuild state. Append-only means you never rewrite or truncate the middle — you just keep adding. That makes it crash-safe (no torn pages), simple to implement (one write() + fsync() per command), and lets you stream it to replicas.

Q: What's the difference between write and fsync? Which policy survives a process kill, and which survives a power cut?
A: write() hands bytes to the OS page cache — they're in RAM, not yet on the disk platter. fsync() forces the OS to flush them to physical storage.

NOFSYNC (write only): fast, but a power cut loses everything in the page cache. A process kill is fine — the OS still owns the cache.
EVERYSEC (fsync once per second): survives a process kill; a power cut loses at most ~1 s of writes.
ALWAYS (fsync every command): survives a power cut. Slowest.
So: process kill → any policy survives (OS flushes on exit). Power cut → only ALWAYS (or EVERYSEC with ≤1 s loss) survives.

Q: Why log SET k v PXAT <deadline> instead of SET k v EX 10?
A: EX 10 is relative — it means "expire 10 s from now." If you replay that command 5 minutes later, the key gets a fresh 10 s TTL instead of expiring in the past. PXAT <absolute-millis> is absolute — it's the same deadline regardless of when you replay it. The command is idempotent under replay.

Q: What happens if the server crashes mid-write? How does the replayer handle it, and why must it cut the broken tail?
A: A crash can leave a partial line at the end of the file — e.g., SET k v PXAT 1 where the full line was supposed to be SET k v PXAT 1727900000000. The replayer reads line by line; it hits a malformed/incomplete line, truncates the file at the last valid command, and replays everything before that. You must cut the tail because leaving the partial bytes means the next append will concatenate onto garbage, corrupting the log permanently. Truncation is the only safe recovery: you lose at most one command (the one that was mid-write), but the rest of the log stays intact.