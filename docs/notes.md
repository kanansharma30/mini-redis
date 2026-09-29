Socket – An address for a connection. It's like a phone number: it tells the network where to send data (your IP + port).

Port – A number (0–65535) that picks which app on your machine gets the data. E.g., port 80 = web browser, port 25 = email.

Blocking – The program stops and waits until the network call finishes (e.g., "wait until data arrives"). Nothing else runs on that thread in the meantime.


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