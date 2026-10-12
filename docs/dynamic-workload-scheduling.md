# Dynamic workload allocation and slowdown protection

## Core rule

Never hard-code a permanent percentage such as 60% on one phone and 40% on another. The coordinator must re-estimate each node from measured performance and choose among: increase its share, reduce its share, keep its share, or temporarily exclude it.

The Kotlin proposal helper is `DynamicWorkloadPlanner.kt`. It is a conservative planner scaffold, **not yet wired to a working native inference engine**. It accepts measured node/backend profiles and proposes contiguous layer ranges. The selected engine must support those ranges before they can be launched.

## What the scheduler measures

For each phone and each backend (CPU or a verified NPU path), collect:

- effective model-layer or representative-operation throughput, not advertised peak TOPS;
- actual inference latency: prompt processing, per-token latency, and end-to-end tokens/s;
- measured process peak RSS and current available system memory;
- temperature/thermal state and sustained speed over time;
- link RTT, payload throughput, timeouts, and retransmits;
- backend startup/graph compilation time, memory overhead, and numerical-validation status;
- whether the exact model architecture, quantization, and assigned operations are supported.

Unknown measurements are unknown, not zero. An unmeasured NPU must not be treated as faster than CPU.

## Initial allocation

1. Run a validated single-phone baseline.
2. Measure each eligible backend using a representative workload at the same model, quantization, context, and batch/prompt settings.
3. Estimate compute cost per assigned layer and activation-transfer cost across each proposed boundary.
4. Propose a small set of contiguous layer partitions, including a single-node plan. Reject plans above the conservative memory budget.
5. Run a short canary inference and compare actual end-to-end latency with the single-node baseline.
6. Keep a multi-node plan only if repeated measurements show a real benefit. Otherwise use the best single node.

A single autoregressive token usually traverses transformer layers sequentially. Splitting one token's layers across phones can add network round trips and synchronization, so more phones do not automatically mean faster generation. Distributed prefill, multiple concurrent requests, or batch workloads may benefit differently and must be measured separately.

## Feedback loop

Use an event-driven controller, not constant frantic rebalancing:

- Collect short rolling windows of latency, tokens/s, RTT, memory, and thermal state.
- Smooth noisy samples with an exponential moving average; keep raw samples for p50/p95 reporting.
- Re-plan only after a meaningful change persists for multiple windows, or immediately on a safety event.
- Use hysteresis: require a clear measured gain before changing a plan, and enforce a cooldown after moving work to avoid oscillation.
- Increase a node's layer share gradually after sustained good performance; reduce it faster when latency rises, memory headroom shrinks, or thermal status worsens.
- If a node disconnects, its backend errors, numerical checks fail, or memory is near the ceiling, stop assigning new work to it and fall back only if the engine can safely resume from a known inference state.
- Keep a known-good plan and record every plan change with reason and measurements.

Suggested initial tuning values for experiments, not universal truths:
- telemetry window: 3–5 seconds;
- normal re-plan: after 3 consecutive windows with a sustained change;
- require at least 10% measured end-to-end improvement before accepting a non-safety reallocation;
- cooldown: 10 seconds after a reallocation;
- memory planning budget: 3.5 GiB per process, leaving headroom below the hard 4 GiB limit.

These thresholds must be tuned from actual device data. Safety events override hysteresis and cooldown.

## CPU + NPU selection

Treat CPU and NPU as separate backends on the same phone. Use NPU only for exact model subgraphs and data types that have passed numerical validation. Account for graph initialization, quantization/dequantization, tensor copies, synchronization, and memory. If the NPU subgraph is slower end-to-end than CPU, fall back to CPU and record why.

Do not split every small operation across CPU and NPU. Prefer larger contiguous supported subgraphs where practical, with explicit fallback at validated boundaries.

## Failure and recovery

- **Thermal slowdown:** decrease new work on the hot node; allow it to cool; remeasure before adding work back.
- **Low available memory / RSS near limit:** reduce context/batch/cache if supported; otherwise unload that backend or node before crossing 4 GiB.
- **Network slowdown:** recalculate transfer costs; merge layer ranges or exclude the remote node if communication outweighs compute saved.
- **NPU failure or unsupported operator:** move that operation/subgraph to CPU if numerical equivalence is validated.
- **Worker lost mid-generation:** do not blindly continue with stale KV cache or partial tensors. Abort/restart from a consistent checkpoint unless the engine explicitly supports recovery.
- **Bad output/numerical drift:** reject the backend and use the validated CPU path.

## Validation gates

Before calling the controller functional, test synthetic planner cases and real-device runs:

1. A fast node gets more layers than a slower equivalent node when communication is negligible.
2. A slow or high-RTT node can receive zero layers.
3. A hot/offline node is excluded.
4. Unknown or over-budget RSS is rejected.
5. Unsupported/unvalidated NPU backends are rejected.
6. No generated plan has gaps, overlaps, or out-of-range layer indices.
7. The chosen multi-node plan beats single-node end-to-end latency across repeated matched trials.
8. Each process stays below 4 GiB measured peak RSS and Android system memory remains healthy.
9. The controller does not oscillate when measurements fluctuate slightly.
10. Results report p50/p95 latency, tokens/s, memory, temperature, network bytes, and exact engine/model revisions.

The current planner is only a proposal generator. Until the native backend supports dynamic layer movement and these tests pass, dynamic allocation must not be advertised as running in production.
