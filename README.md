# PhoneMesh AI

**PhoneMesh AI** is an Android-first research project for connecting multiple phones into a local, offline-capable distributed inference cluster.

The first milestone is deliberately modest: prove that Android phones can discover one another without internet or a traditional hotspot, establish a reliable peer-to-peer link, and benchmark large binary transfers. Model sharding comes only after transport is measured.

## Design goals

- Work with no internet connection.
- Avoid requiring a phone to run a traditional Wi-Fi hotspot.
- Prefer high-throughput, low-latency links for tensor and file transfer.
- Support multiple nodes; do not hard-code a two-phone cluster.
- Keep **runtime RAM at or below 4 GiB per phone**. This is a hard acceptance limit, not a promise that every model will fit.
- Measure real throughput, latency, power/thermal behavior, and end-to-end tokens/s before claiming a speedup.
- Keep model weights on their assigned node. Never resend multi-gigabyte model files for every inference request.

## Planned transport strategy

1. **Wi-Fi Direct (Wi-Fi P2P)** — first choice for nearby phones with no router, internet, or traditional hotspot. Android provides peer discovery and direct connections; the app then uses TCP sockets for reliable bulk transfer.
2. **Existing local Wi-Fi LAN** — use when a trusted router is already available. No internet access is required if the LAN itself works.
3. **USB networking** — investigate later as an optional wired mode; Android device support and user setup vary.
4. **Bluetooth** — discovery/control fallback only, not the preferred path for multi-gigabyte model transfers.
5. **Wi-Fi Aware / Nearby Connections** — optional future adapters after checking device support and Google Play services availability. They must not be mandatory for offline operation.

Wi-Fi Direct still uses the Wi-Fi radio and can consume power or generate heat during sustained transfers. It avoids the need for a traditional hotspot; it does **not** guarantee low temperature or a specific transfer rate.

## Current implementation

The initial Android app is a transport prototype:
- discover nearby Wi-Fi Direct peers;
- request a connection to a selected peer;
- show group-owner / peer connection state;
- run a TCP binary-transfer benchmark over the Wi-Fi Direct group;
- keep the benchmark payload in memory only, and report bytes, duration, and throughput.

This prototype does **not** yet run a language model or split GGUF weights. That is a separate milestone.

## Model distribution decision

**Provision model data locally, then compute locally across the cluster.** The initial source phone should download a model only once and transfer it over the local peer link, not make the other phone download it again from the internet. The final target is to transfer only verified weight shards assigned to each phone. Until the chosen engine proves that it can load per-node shards, a full local copy on both phones is an acceptable temporary compatibility baseline. Do not stream model weights over the internet for every inference request.

See [the model distribution decision](docs/model-distribution-decision.md) for the trade-offs, shard-support caveats, transfer integrity requirements, and acceptance gates. For the terminal-first **one → two → three phone** inference path, see the [Termux distributed inference test guide](docs/two-phone-inference-test.md). The repository also includes a [Termux launcher with a best-effort 4 GiB RSS watchdog](scripts/termux-run.sh); it launches an already-built prima.cpp binary and does not build the engine for you. The Termux guide is optimized for a slow 200 KB/s internet link: shallow source clones, no unnecessary full package upgrade, and local reuse of the tested source/build instead of recloning on every phone.

## Build

Open the project in Android Studio with JDK 17 and Android SDK 35, or use the included GitHub Actions workflow to compile it. The project intentionally starts without a checked-in Gradle wrapper; CI installs a compatible Gradle version.

## Hybrid integration plan

The project includes a typed node telemetry model and a replaceable inference-backend contract. See [the integration architecture](docs/engine-integration-architecture.md) for the plan combining prima.cpp's heterogeneous layer scheduling, exo's topology-aware placement concepts, and Silica Cluster's Android service/telemetry ideas without copying AGPL-licensed source.

The Kotlin scaffold is not yet connected to a native inference engine. CI compilation and real-device tests must pass before claiming it works. For the CPU-first plan with optional vendor NPU acceleration, see [the hybrid CPU/NPU plan](docs/hybrid-cpu-npu-plan.md); the current prima.cpp route is not verified to access Qualcomm mobile NPUs.

## Existing distributed-inference engines

Before implementing model sharding from scratch, see [the candidate review](docs/distributed-engine-review.md). The first candidate to validate is prima.cpp via Termux; this is a source-based recommendation, not yet a successful run on the target phones.

## APK transport test

1. Install the debug APK on both phones.
2. Enable Wi-Fi and grant the requested nearby-device permission. On some Android versions, Wi-Fi Direct discovery also requires Location to be enabled.
3. On both phones, open PhoneMesh and scan for peers.
4. Select the other phone and connect. Android may show a system confirmation dialog.
5. On the group-owner phone, start the benchmark server.
6. On the other phone, send a benchmark payload.

This only tests connectivity and transfer; it does not run model inference. Use the Termux guide for inference itself.

## Safety and security

Use only on devices you control and a trusted local link. The initial benchmark server is a development tool and should not be exposed to untrusted networks. Before model tensors or user prompts are transmitted, add authenticated peer pairing, encrypted application-level sessions, message limits, timeouts, and protocol version negotiation.

## Roadmap

- [x] Establish transport choice and constraints.
- [x] Android Wi-Fi Direct discovery/connection prototype.
- [x] TCP bulk-transfer benchmark prototype.
- [x] Compile debug APK in GitHub Actions (artifact is available in the workflow run).
- [x] Document a terminal-first staged inference path from one phone to two and three phones.
- [ ] Validate Termux build and model compatibility on the actual devices.
- [ ] Complete and measure two-phone inference.
- [ ] Complete and measure three-phone inference.
- [ ] Implement resumable local model/shard transfer with checksum verification.
- [ ] Implement model-layer placement and distributed forward pass in the Android app.
- [ ] Benchmark matched single-/multi-phone runs; only claim speedups supported by measurements.
- [ ] Enforce the measured 4 GiB runtime-RAM ceiling on every node.
