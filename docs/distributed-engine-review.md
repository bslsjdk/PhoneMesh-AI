# Distributed inference engine evaluation (2026-10-12)

This document evaluates existing projects for PhoneMesh-AI. It is a source-based screening, not a claim that any engine has been built or tested on the two target phones.

## Target constraints

- Devices: realme GT Neo6 (primary, 16 GB+ RAM) and realme X50 5G (Snapdragon 765G, 8 GB+ RAM).
- Hard limit: runtime RAM must remain at or below 4 GiB per phone.
- Offline operation; do not require internet during inference.
- Avoid requiring a traditional hotspot to remain enabled.
- Prefer genuine model-layer sharding, not duplicating the full model on every device.
- Measure end-to-end token latency/tokens per second, peak memory, link traffic, battery and thermal behavior.

## Candidate assessment

| Project | Potential fit | Main caveat | Decision |
|---|---|---|---|
| prima.cpp | Explicitly documents Android/HarmonyOS through Termux, heterogeneous clusters, layer assignment and automatic device selection | Build has multiple native dependencies; published latency tables are from heterogeneous clusters and are not a prediction for two Android phones; a weak node may be excluded automatically | First candidate for a controlled two-phone test |
| exo | P2P discovery and model partitioning are real project goals | Current official quick-start emphasizes macOS/Linux; direct Android-on-Android deployment is not established by that documentation | Secondary research candidate, not the first implementation target |
| Silica Cluster | Android app architecture, llama.cpp integration, cluster UI and layer-oriented distributed inference are relevant | Small/early project; its README says RPC traffic is currently unencrypted; real-device correctness, security and performance need independent validation | Inspect for Android integration patterns, do not adopt blindly |
| LinguaLinked | Peer-reviewed research on mobile distributed LLM inference and runtime load balancing | Research system/paper is not itself a drop-in Android app for these exact devices | Use as a design reference |

## Correct interpretation of prima.cpp performance claims

The project's README reports a table with 569 ms for Llama 1-65B and 674 ms for Llama 3-70B. These are project-reported token-latency measurements from a heterogeneous setup, including desktop-class hardware and a mobile device. They must not be described as expected performance on a two-phone cluster.

The statement that memory pressure is below 10% is not equivalent to a guarantee that every Android process uses less than 4 GiB of RAM. PhoneMesh acceptance requires measuring actual per-process peak RSS/PSS and system memory pressure on each target phone.

## Proposed evaluation sequence

1. Keep PhoneMesh's existing Wi-Fi Direct/TCP benchmark as an independent transport baseline.
2. Build prima.cpp in Termux on the primary phone first. Record build time, failures, compiler/dependency versions, CPU temperature, and peak memory. Do not download a huge model yet.
3. Build on the X50 only if the dependency/build footprint is practical. Confirm ARM64 support and available storage.
4. Run the project's own small self-test or smallest supported model before attempting multi-device inference.
5. Confirm both devices join one cluster and verify the layer assignment. If the engine removes the X50 as too slow, record that as a valid result, not a setup success.
6. Start with a small quantized model and short context. Measure cold start, prompt processing, generation tokens/s, per-token latency, communication volume, per-phone peak memory, and thermal throttling.
7. Repeat with each device alone and with both devices. Keep the same model, quantization, context, output length, thread settings where possible.
8. Only consider larger models after both phones remain under 4 GiB runtime RAM. Do not assume model weights alone determine peak memory; KV cache, scratch buffers, runtime and Android overhead also count.

## Architecture implication for PhoneMesh

Do not rewrite the transport layer around a guessed winner. Keep a transport adapter and a distributed-inference adapter separate. PhoneMesh can remain responsible for device pairing, offline connectivity, measurement, user interface, and safety limits, while prima.cpp is evaluated as an optional inference backend. If the backend proves reliable, integrate it behind an explicit engine interface instead of reimplementing model sharding prematurely.

## Security note

Before sending prompts, activations, or model data across devices, use trusted pairing and authenticated/encrypted transport. Do not expose an unauthenticated development RPC server to other networks.
