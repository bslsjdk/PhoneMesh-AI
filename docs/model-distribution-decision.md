# PhoneMesh model distribution decision

Date: 2026-10-12

## Decision

**Use local phone-to-phone transfer for initial model provisioning. Do not stream model weights over the internet during inference.**

The GT Neo6 can be the initial model source: obtain a model once, then transfer it to the X50 over the local PhoneMesh link. The transfer should work without internet after both phones have the app and the model file is available on the source phone. Wi-Fi Direct is the first transport to test; an existing local Wi-Fi network is an optional alternative, not an internet dependency.

## Why this is the best first choice

There are three different designs, and they should not be conflated:

| Design | Initial download/storage | Per-token network traffic | Decision |
|---|---|---|---|
| One phone has all weights; other phone computes remotely over the internet | May avoid a second local copy, but requires an online inference service or remote weight access | Potentially large and unpredictable; adds internet latency and dependency | **Reject as the default** |
| Download once, then copy the full model locally to both phones | One internet download plus one local transfer; uses more storage | Mostly activations/control data between inference workers, not repeated model-file transfer | **Fallback for the first working engine** |
| Download once, split into verified model shards, and locally send each node only its assigned shard | One internet download plus local transfer of the needed shards; lower duplicated storage | Mostly activations/control data between workers | **Preferred final design, once the selected engine demonstrably supports it** |

If only one phone stores the weights while the other phone computes layers, the other phone still needs those layers' weights somehow. Sending gigabytes of weights repeatedly, or fetching them from an internet server during inference, is usually worse than provisioning them locally once. Model weights are relatively static; intermediate activations and control messages are what should cross the network during inference.

## Shard support must be proven, not assumed

The upstream [prima.cpp README](https://github.com/OpenCPIL/prima.cpp) shows a multi-node example that copies the same complete GGUF file to each node. Its distributed layer scheduling therefore does **not by itself prove** that each phone can store only its own weights.

A separate, lightly used [prima.cpp fork](https://github.com/electron-rare/prima-cpp) documents loading only relevant files from a multi-part GGUF split. This is a promising lead, not yet a selected production dependency: its exact revision, Android/Termux build, layer assignment, file requirements, and end-to-end behavior on our two phones have not been tested. We must not treat the fork's claims or published desktop cluster performance as measurements on our phones.

Implementation rule:
1. First validate the engine on-device with a small model and short context.
2. Measure per-process runtime peak RAM on **each** phone; an unknown peak fails the 4 GiB acceptance gate.
3. Check whether the exact engine revision can load only each node's assigned GGUF split without requiring the complete model on every node.
4. If yes, implement a versioned shard manifest with per-shard size, checksum, model/tokenizer identity, and layer range; transfer each missing shard locally and verify it before loading.
5. If not, use a full local copy as a temporary compatibility baseline, and keep researching a split-aware backend. Do not stream the full model across the network for every request.
6. Benchmark single-phone inference against two-phone inference. If the second phone increases end-to-end latency or violates the memory/thermal limits, the scheduler must reduce its assigned work or exclude it.

## Transfer protocol requirements

- Resumable chunked transfer; do not keep a whole multi-gigabyte file in RAM.
- Bounded buffers and backpressure.
- SHA-256 verification after transfer; never load an incomplete or mismatched shard.
- Temporary filename followed by atomic rename only after verification.
- Model ID, quantization, tokenizer metadata, shard index/count, byte length, and layer range must match the manifest.
- Peer authentication, encrypted sessions, timeouts, cancellation, and transfer-size limits before transmitting model data or user prompts.
- Persist transfer progress so a dropped connection does not force a restart from byte zero.
- Keep each phone's measured runtime peak at or below **4 GiB**. File size, mmap, and OS page-cache behavior are not substitutes for measuring the actual process peak.

## Current status

This is an architecture decision, not a claim that model transfer, shard loading, or distributed inference is already implemented. The current app has a Wi-Fi Direct/TCP transport prototype and a benchmark; the native inference backend and robust file-transfer protocol remain future implementation work.
