# Hybrid CPU + NPU plan for PhoneMesh-AI

Date: 2026-10-12

## Goal

Use each phone's strongest *verified* compute path rather than pretending all phones have the same accelerator:

- GT Neo6: begin with CPU inference; investigate its vendor NPU backend separately.
- X50 5G: begin with CPU inference; only use an accelerator if its exact SoC, Android vendor runtime, and supported operations are verified.
- A third phone is optional and must pass the same compatibility and memory checks.

The non-negotiable runtime target is at most 4 GiB per phone. A hybrid design is not allowed to exceed it just to make a benchmark look faster.

## Important current limitation

The current upstream prima.cpp README documents CPU execution and optional CUDA GPU offload, and explicitly says Vulkan/AMD GPU support is not available. It does **not** establish a working Qualcomm mobile NPU backend through Termux. Therefore, prima.cpp is the CPU/distributed baseline, not the NPU integration solution. Do not pass CUDA/GPU flags and assume they activate a phone NPU.

Reference: https://github.com/OpenCPIL/prima.cpp

## Staged implementation

### Stage A: establish a CPU baseline

1. Build and run the engine on one phone with a short context and short generation.
2. Record exact SoC, Android build, Termux version, engine commit, model architecture/quantization, load time, prompt processing time, generation tokens/s, peak RSS, available system memory, and temperature/thermal status.
3. Keep the test under 4 GiB process RSS and watch system-wide memory pressure too.
4. Repeat on the second phone only after the first is stable.
5. Measure local network bandwidth and latency independently before adding distributed inference.

No accelerator work should obscure whether the model and engine work on CPU.

### Stage B: probe the vendor NPU without changing the main engine

Create a small, separate NPU capability probe. It should report only verified facts:

- Android SoC/device identifier and ABI;
- whether a supported vendor runtime library is present and loadable;
- whether the runtime can enumerate the NPU device;
- supported data types and operations;
- whether a tiny known test graph can execute and return numerically valid output;
- initialization cost, peak memory, and latency.

Do not download a large SDK or model converter until the probe shows a plausible supported route. A library existing on disk is not proof that Termux can use it; vendor services, permissions, ABI compatibility, and runtime versions may still block access.

### Stage C: choose the narrowest viable NPU route

Evaluate routes in this order, only when supported by the exact phone:

1. Android/vendor-supported runtime with a documented path to the NPU.
2. A native Android backend that can be called from the eventual app and that exposes the required operations.
3. Keep CPU inference if the NPU route requires an unsupported model conversion, unavailable vendor services, or too much memory.

The target model must be checked for architecture, operators, tensor shapes, and quantization support. A GGUF file cannot automatically be sent to an NPU runtime; conversion or a separate compatible model representation may be required. Never assume full model support based on a successful tiny demo.

### Stage D: hybrid execution, only after the probe passes

Use an explicit backend interface with a per-operation capability table:

- CPU: fallback for every operation supported by the CPU engine.
- NPU: only operations and data types confirmed by the capability probe.
- Interop: measure conversion/copy/synchronization cost between CPU and NPU.
- Scheduler: choose CPU or NPU per supported subgraph using measured end-to-end latency, memory, and thermal state, not peak TOPS marketing.
- Fallback: if an NPU operation fails validation or runs slower after transfer overhead, use CPU and record the fallback.
- Numerical checks: compare outputs against the CPU reference with documented tolerances before enabling a path for generation.

Avoid shuttling every small tensor between CPU and NPU. Transfers, graph compilation, quantize/dequantize steps, and synchronization can erase the theoretical speedup. Prefer larger contiguous supported subgraphs when possible.

### Stage E: mix devices across the cluster

The scheduler should treat a phone as a measured collection of backends, not just a single CPU speed number. Each node advertises validated CPU/NPU capabilities, current thermal state, available memory, and measured sustained throughput. The coordinator assigns work only where the node's measured benefit exceeds communication and synchronization costs.

A practical first hybrid target is **CPU on all phones + optional NPU acceleration on one phone**, not an unverified promise that both phones' NPUs can cooperate. If the NPU path is not available from Termux, retain CPU cluster inference and implement the NPU path later through a native Android integration.

## Acceptance gates

A CPU/NPU change is accepted only if all apply:

- Tiny NPU graph produces valid results and matches the CPU reference within a stated tolerance.
- Exact model architecture and all operations assigned to NPU are supported.
- End-to-end prompt processing or generation improves in repeated measurements, not just isolated operator timing.
- Every phone stays at or below 4 GiB measured runtime memory.
- No persistent thermal throttling, process crashes, or silent numerical corruption.
- CPU-only fallback remains functional.
- Report median and p95 latency, tokens/s, peak RSS, initialization time, thermal state, and exact software/runtime versions.

## Current status

Not implemented or device-verified yet. The next action is a low-download capability probe and a CPU baseline, not downloading a large NPU toolchain blindly. The PhoneMesh Android UI is still not connected to a native inference backend.
