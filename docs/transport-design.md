# PhoneMesh transport design

## Requirements

PhoneMesh must work when one or more phones have no internet access. Users do not want to keep a phone's traditional hotspot enabled because it can increase heat and battery use. Large transfers (model shards and intermediate tensors) need reliable throughput; small control messages need low latency.

## Candidate comparison

| Transport | Internet required | Traditional hotspot required | Bulk-transfer suitability | Limitations |
|---|---:|---:|---|---|
| Wi-Fi Direct / Wi-Fi P2P | No | No | First choice; Wi-Fi radio and TCP sockets support bulk data | OEM support and Android behavior vary; group-owner device still uses Wi-Fi and may warm up |
| Existing Wi-Fi LAN | No internet required | No, if router already exists | Often good; TCP works well for large files | Requires an existing access point/router |
| USB networking | No | No | Potentially excellent and stable | Phone-to-phone USB networking is not universally plug-and-play; cables, role negotiation, and OS support vary |
| Bluetooth | No | No | Not recommended for multi-GB payloads | Much lower practical throughput than Wi-Fi in many configurations |
| Wi-Fi Aware | No | No | Potentially suitable on supported devices | Hardware/API support varies; must probe at runtime |
| Nearby Connections API | No internet is inherently required | No traditional hotspot required by app | Can select suitable transports under the hood | Depends on Google Play services and device behavior; not a good sole transport for a fully independent/offline product |

## Decision

Implement Wi-Fi Direct first, with a future transport interface so other links can be added without changing the inference scheduler.

### Why Wi-Fi Direct

Android's Wi-Fi P2P APIs can discover and connect nearby devices without an access point or internet connection. Once connected, standard TCP sockets can transfer binary data reliably. PhoneMesh should use a long-lived connection rather than reconnecting for every tensor.

### Power and thermal behavior

Wi-Fi Direct avoids asking the user to manually enable a conventional hotspot, but it still activates the Wi-Fi radio. Long, high-throughput transfers can heat a phone. The app must measure sustained performance and thermal throttling rather than claim that the link is cool or free.

### Transfer protocol principles

- Reuse a connection for multiple messages.
- Use framed binary messages: magic, protocol version, message type, request ID, payload length, payload.
- Enforce payload-size limits before allocating memory.
- Stream large file/tensor payloads in bounded chunks; never allocate a multi-gigabyte payload as one byte array.
- Include checksums for persisted model-shard transfers.
- Use timeouts, cancellation, and explicit errors.
- Authenticate peers before allowing remote compute requests.
- Keep model shards local between requests.
- Do not compress tensor payloads by default: compression costs CPU and may increase latency. Benchmark optional compression only for payload types where it helps.

## Measurements

For each device pair and transport, record:
- discovery time and connection setup time;
- round-trip time (median and p95);
- sustained one-way and bidirectional throughput;
- packet/transfer failures and retry count;
- process peak RSS and Android total memory;
- battery drain and thermal status over at least 10 minutes;
- throughput after thermal stabilization.

The useful target is not a marketing speed. It is a repeatable measured result on the actual GT Neo6 and realme X50 5G.
