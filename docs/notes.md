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