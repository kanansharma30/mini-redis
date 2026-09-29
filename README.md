# mini-redis

A Redis-compatible in-memory data store built from scratch in Java.

Status: in progress

## Status

Work in progress. Built step by step, with each layer tested before the next one is added.

- [x] TCP server speaking the RESP protocol
- [x] RESP parser with input validation (size limits, malformed input rejected)
- [x] Commands: PING, SET, GET, DEL
- [x] Unit tests for the parser and the command handler
- [ ] Multiple concurrent clients
- [ ] Key expiry (TTL)
- [ ] Persistence (append-only file + recovery on restart)
- [ ] Benchmarks against real Redis
- [ ] Docker image

## Supported commands

| Command | Description | Reply |
|---|---|---|
| `PING [message]` | Health check. With a message, echoes it back | `PONG` or the message |
| `SET key value` | Stores a string value, overwriting any existing one | `OK` |
| `GET key` | Returns the value of a key | the value, or `(nil)` if missing |
| `DEL key [key ...]` | Deletes one or more keys | number of keys actually removed |

Command names are case-insensitive (`set` = `SET`). Keys and values are case-sensitive.
Wrong argument counts and unknown commands return a RESP error instead of crashing the connection.

## Architecture

```
client (redis-cli) --> RedisServer --> RespParser --> CommandHandler --> Store
                       (server)        (protocol)      (command)        (store)
                            ^                               |
                            +---------- RespWriter <--------+
                                        (protocol)
```

- `server`: accepts TCP connections and moves bytes in and out
- `protocol`: parses incoming RESP commands and builds RESP replies
- `command`: executes a parsed command against the store
- `store`: the in-memory key-value data (thread-safe `ConcurrentHashMap`)

## Requirements

- Java 21
- (Optional) Docker, to use `redis-cli` for testing

## Run it

1. Clone the repo and open it in IntelliJ IDEA (Maven project).
2. Run `com.kanan.miniredis.Main`. It listens on port 6379 by default.
   To use another port, pass it as a program argument, e.g. `6380`.
3. Talk to it with the official `redis-cli` (through Docker, no install needed):

```bash
docker run --rm -it redis redis-cli -h host.docker.internal -p 6379
```

Then try:

```
PING
SET name arun
GET name
DEL name
GET name
```

Note: the server currently handles one client at a time. Multi-client support is the next milestone.

## Run the tests

In IntelliJ: right-click `src/test/java` and choose **Run 'All Tests'**.

## Benchmarks

Coming soon. Results will be measured with `redis-benchmark` against a real Redis instance under identical conditions, and the numbers will be published here.