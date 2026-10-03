# mini-redis

![CI](https://github.com/kanansharma30/mini-redis/actions/workflows/ci.yml/badge.svg)

A Redis-compatible in-memory data store built from scratch in Java: TCP server, RESP protocol, concurrent clients, key expiry and crash-safe persistence. Works with the official `redis-cli`.

**Status: in progress** (benchmarks and Docker packaging are next)

## Features

- [x] TCP server speaking the RESP protocol
- [x] RESP parser with input validation (size limits, malformed input rejected)
- [x] Commands: `PING`, `SET`, `GET`, `DEL`, `EXPIRE`, `PEXPIREAT`, `TTL`
- [x] Multiple concurrent clients (fixed thread pool)
- [x] Key expiry (TTL): lazy expiry plus a background sweeper
- [x] Persistence: append-only file (AOF) with recovery on restart and repair of a damaged file tail
- [x] Configurable fsync policy (`ALWAYS`, `EVERYSEC`, `NO`)
- [x] Unit, end-to-end and concurrency tests, run automatically by GitHub Actions
- [ ] Benchmarks against real Redis
- [ ] Docker image

## Supported commands

| Command | Description | Reply |
|---|---|---|
| `PING [message]` | Health check. With a message, echoes it back | `PONG` or the message |
| `SET key value [EX seconds \| PX ms \| PXAT unix-ms]` | Stores a string value, overwriting any existing one (and any old expiry). Optional expiry | `OK` |
| `GET key` | Returns the value of a key | the value, or `(nil)` if missing or expired |
| `DEL key [key ...]` | Deletes one or more keys | number of keys actually removed |
| `EXPIRE key seconds` | Sets a time-to-live on an existing key | `1` if set, `0` if the key does not exist |
| `PEXPIREAT key unix-ms` | Sets an absolute expiry time (Unix time in milliseconds) | `1` if set, `0` if the key does not exist |
| `TTL key` | Remaining time to live in seconds | seconds left, `-1` = no expiry, `-2` = key does not exist |

Command names are case-insensitive (`set` = `SET`). Keys and values are case-sensitive. Wrong argument counts, invalid numbers and unknown commands return a RESP error instead of closing the connection.

## Architecture

```
client (redis-cli) --> RedisServer --> RespParser --> CommandHandler --> Store
                       (server)        (protocol)      (command)        (store)
                            ^                               |             |
                            +---------- RespWriter <--------+             +-- background expiry sweeper
                                        (protocol)                |
                                                                  +--> AofWriter --> appendonly.aof
                                                                       (persistence)

on startup:  appendonly.aof --> AofReplayer --> CommandHandler --> Store
```

- **server**: accepts TCP connections and hands each one to a fixed pool of 50 worker threads
- **protocol**: parses incoming RESP commands and builds RESP replies
- **command**: executes a parsed command against the store and logs successful writes
- **store**: the in-memory key-value data (thread-safe `ConcurrentHashMap`), expiry logic and the background sweeper
- **persistence**: writes the append-only file and replays it on startup

### Design notes

- **Expiry:** each key stores an absolute deadline. Expired keys are removed lazily when accessed, and a background thread sweeps the rest every 100 ms.
- **Persistence:** every successful write is appended to `appendonly.aof` in RESP format, the same format clients send, so the file is read back with the same parser. On startup the file is replayed to rebuild memory.
- **TTL and restarts:** relative expiries (`EX`, `PX`, `EXPIRE`) are logged as absolute deadlines (`PXAT`, `PEXPIREAT`). A key set to live 10 minutes still expires at the right moment after a restart.
- **Write ordering:** applying a write to memory and appending it to the log happen under one lock, so the log order always matches the order applied in memory. A concurrency test checks this.
- **Damaged file:** if the server crashed in the middle of writing a command, the incomplete tail is detected on startup and cut off. Everything before it is kept.
- **fsync policy:** `ALWAYS` (fsync after every write, safest), `EVERYSEC` (background fsync once per second, the default), `NO` (the OS decides). Surviving a process crash is the same for all three. Surviving a power cut is not.

## Requirements

- Java 21
- (Optional) Docker, to use `redis-cli` for testing

## Run it

1. Clone the repo and open it in IntelliJ IDEA (Maven project).
2. Run `com.kanan.miniredis.Main`. It listens on port 6379 by default. To use another port, pass it as a program argument, e.g. `6380`.
3. Data is saved to `appendonly.aof` in the working directory, with fsync policy `EVERYSEC`. (Not configurable from the command line yet.)
4. Talk to it with the official `redis-cli` (through Docker, no install needed):

```
docker run --rm -it redis redis-cli -h host.docker.internal -p 6379
```

Then try:

```
PING
SET name arun
GET name
SET session abc EX 60
TTL session
DEL name
GET name
```

### See persistence work

1. Start the server, run `SET name arun` in `redis-cli`, then stop the server abruptly (the red stop button in IntelliJ).
2. Start the server again. The console prints `Loaded N commands from appendonly.aof`.
3. Run `GET name` in `redis-cli`. The value is still there.

## Run the tests

In IntelliJ: right-click `src/test/java` and choose **Run 'All Tests'**. With Maven installed you can also run `mvn test`.

The tests cover:

- the RESP parser (valid and malformed input)
- the command handler and the store (including expiry, using a fake clock)
- the append-only file (replay, TTL across restarts, repair of a damaged file)
- end-to-end tests with a real server and real sockets: multiple clients, restart recovery, and 16 concurrent clients writing the same keys with the final state checked after a restart

The same tests run on every push through GitHub Actions.

## Known limitations

- The append-only file only grows; there is no compaction (real Redis rewrites it periodically).
- Only string values are supported.
- Each connected client occupies one of the 50 worker threads, even while idle. An event-loop design (Java NIO) would remove that limit.
- No authentication yet.

## Benchmarks

Coming soon. Results will be measured with `redis-benchmark` against a real Redis instance under identical conditions, and the numbers will be published here.