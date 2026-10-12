# PhoneMesh transport protocol: initial benchmark

The current prototype uses a deliberately small protocol for a controlled benchmark. It is **not yet the production tensor protocol**.

## Benchmark exchange

1. Client connects to the server's TCP port.
2. Client writes a 32-bit big-endian payload length.
3. Client streams that many bytes in bounded chunks.
4. Server counts received bytes without retaining the entire payload in memory.
5. Server returns a 64-bit big-endian received-byte count and elapsed nanoseconds.
6. Both sides close the benchmark socket.

The benchmark payload is generated in bounded chunks. It must not allocate a large byte array equal to the selected test size.

## Production protocol requirements

Before this protocol carries model data or prompts, replace/extend it with:
- fixed magic and protocol version;
- explicit message type and request ID;
- bounded length fields and rejection of oversized messages;
- streaming chunk framing;
- SHA-256 or equivalent checksum for stored model shards;
- authenticated pairing and encrypted sessions;
- read/write deadlines, cancellation, and reconnect semantics;
- capability negotiation and node health reporting.

## Important distinction

A successful bulk-transfer benchmark proves only that the transport can move bytes. It does not prove that the model is split correctly, that tensor serialization is efficient, or that distributed inference is faster than a single phone.
