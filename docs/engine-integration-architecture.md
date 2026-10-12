# PhoneMesh engine integration architecture

Date: 2026-10-12

This plan combines useful ideas from prima.cpp, exo, and Silica Cluster without assuming that their published performance transfers to two Android phones. It is a design and code scaffold, not a claim that distributed inference is already working.

## What we should reuse

### From prima.cpp

- Heterogeneous scheduling: assign different layer counts to nodes based on measured sustained compute, storage speed, network cost, available memory, and thermal state.
- Layer-level distribution and prefetching to overlap model-weight reads with compute where measurements show a benefit.
- Automatic node exclusion: a slower or thermally throttled worker must be removable when it makes the cluster slower.
- GGUF/llama.cpp ecosystem and a replaceable native engine backend.

Important caveats:
- The upstream README's example copies the model file to each node. That is not the same as distributing model weights only once or storing only each node's assigned shard.
- prima.cpp's README describes Android/HarmonyOS use through Termux, so the first integration experiment should be a Termux build, not an assumption that its binaries can immediately be embedded in our APK.
- The RPC example README labels the RPC backend proof-of-concept, fragile, and insecure. Never expose it on an untrusted network. PhoneMesh must not send prompts or tensors through an unauthenticated endpoint.
- mmap and OS page-cache reclamation do not prove that the Android process and total runtime remain under the 4 GiB acceptance ceiling.

Sources:
- https://github.com/OpenCPIL/prima.cpp
- https://github.com/OpenCPIL/prima.cpp/blob/main/examples/rpc/README.md

### From exo

- Treat the cluster as a topology, not just a flat list of IP addresses.
- Consider network latency/bandwidth alongside compute capacity when deciding whether another node is useful.
- Expose a stable inference API so a client app does not depend on engine internals.

Caveat: exo's current primary runtime stack is not an established drop-in Android-on-Android engine for our devices. Reuse architectural ideas, not an unverified installation claim.

Source: https://github.com/exo-explore/exo

### From Silica Cluster

- Android-first cluster UI and node telemetry (RAM, CPU, thermal state).
- A foreground service for long-running native engine work and readable logs.
- A native llama.cpp/RPC integration as a reference for the eventual Android packaging path.

Caveat: the project is AGPL-3.0. PhoneMesh currently has not adopted that license, so this project borrows architectural ideas only and does not copy its source. Its README also warns that RPC traffic is currently unencrypted.

Source: https://github.com/ShintoChakkiath/Silica-Cluster-Decentralized-Mobile-AI

## PhoneMesh's own responsibilities

PhoneMesh should remain responsible for:
1. Offline device discovery and pairing.
2. Link selection (Wi-Fi Direct first; existing LAN/USB as optional future transports).
3. Authenticated peer identity and encrypted session establishment before transmitting prompts/tensors.
4. Node telemetry, memory guardrails, thermal backoff, cancellation and reconnect.
5. Measurement and reporting of end-to-end quality/speed.
6. A backend interface, so the engine can be swapped without rewriting the UI or transport.

The inference engine should be responsible for:
1. Model parsing and tokenization.
2. Actual tensor/layer computation.
3. Assigning model layers or operations to remote workers.
4. KV cache and inference-state management.
5. Returning streamed generated tokens and engine diagnostics.

## New source scaffolding

- `ClusterNode.kt`: typed node telemetry and eligibility checks.
- `InferenceBackend.kt`: engine adapter interface, worker/coordinator configuration, layer assignment records, and a conservative plan validator.

The backend interface is intentionally not wired to a native binary yet. It prevents us from baking prima.cpp command-line assumptions into the Android UI before we know which deployment path works.

## Weight placement is a separate requirement

Do not confuse these three designs:

1. **Replicated model:** every phone downloads the full GGUF. Simple, but wastes storage/network and may pressure memory.
2. **Split computation, replicated file:** each node computes selected layers but still stores the whole GGUF. This can reduce compute responsibility, but it does not meet a strict no-duplicate-weight-storage goal.
3. **Layer-sharded storage:** each node downloads or receives only the weight chunks it owns, using a versioned manifest and checksums. This is the target, but only if the chosen engine truly supports it.

The first engine spike must explicitly report which of these modes is actually supported. If prima.cpp's upstream runtime requires the full file on every phone, we should not claim that it solves sharded storage. Evaluate GGUF split-file support separately and verify which exact upstream revision supports loading only the relevant pieces.

## Hard acceptance criteria

- Per-phone measured runtime peak must be <= 4 GiB. Unknown peak is a failed gate, not zero.
- A worker joins only after a health check and a bounded-memory test.
- A node is not assigned layers based on advertised RAM alone.
- Compare primary-only vs primary+worker with the same model, quantization, context length, prompt, and generated token count.
- Report prompt processing time, generation tokens/s, median/p95 per-token latency, total bytes sent, peak memory per process, battery and thermal behavior.
- If adding the X50 makes end-to-end performance worse, the scheduler must be able to reduce its layer count or exclude it.
- The cluster must function with no internet. A traditional hotspot must not be a mandatory setup step.
- Do not accept the prima.cpp demo RPC endpoint as production-secure without a separate authenticated and encrypted transport.

## Next implementation sequence

1. Compile the new Kotlin backend/telemetry scaffold in CI.
2. Run the existing Wi-Fi Direct test on both phones and record baseline link performance.
3. Build and self-test prima.cpp in Termux on the GT Neo6; measure build/runtime footprint.
4. Repeat on the X50 only if the build is practical.
5. Test a small quantized model with a short context. Keep each device below 4 GiB.
6. Determine whether the exact engine revision supports per-node weight shards. If not, prototype a shard manifest/distribution layer or select another backend before large-model testing.
7. Only after the backend passes these gates, connect it to the Android UI and expose an OpenAI-compatible local API if useful.
