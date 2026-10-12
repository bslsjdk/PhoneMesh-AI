package com.bslsjdk.phonemesh

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Conservative adaptive planner for contiguous layer placement.
 *
 * This produces a proposal only; the native engine must confirm that it can
 * actually load the selected layer ranges. It never splits GGUF bytes.
 */
data class BackendMeasurement(
    val backendId: String,                 // e.g. cpu or a verified npu backend
    val measuredLayersPerSecond: Double?,  // measured on representative model ops
    val supportedLayerFraction: Double,    // 0..1, validated for this exact model
    val initializationMillis: Long,
    val extraMemoryBytes: Long,
    val numericalValidationPassed: Boolean
)

data class AdaptiveNodeProfile(
    val nodeId: String,
    val online: Boolean,
    val measuredRuntimePeakBytes: Long?,
    val availableRamBytes: Long,
    val thermalStatus: ThermalStatus,
    val measuredNetworkRttMillis: Double?,
    val measuredNetworkBytesPerSecond: Double?,
    val fixedRuntimeBytes: Long,
    val estimatedBytesPerLayer: Long,
    val backends: List<BackendMeasurement>
)

data class AdaptiveLayerAssignment(
    val nodeId: String,
    val layerStartInclusive: Int,
    val layerCount: Int,
    val backendId: String,
    val estimatedComputeMillis: Double,
    val estimatedMemoryBytes: Long
)

data class AdaptivePlan(
    val assignments: List<AdaptiveLayerAssignment>,
    val estimatedComputeAndTransferMillis: Double,
    val warnings: List<String>
)

/**
 * Re-plan from fresh measurements. A node is excluded if its peak is unknown,
 * its thermal state is critical, it has no validated backend, or its projected
 * memory cannot stay below the conservative budget.
 */
object DynamicWorkloadPlanner {
    // Leave 512 MiB headroom below the hard 4 GiB acceptance ceiling.
    private const val PLANNING_BUDGET_BYTES = RuntimeBudget.MAX_BYTES - 512L * 1024L * 1024L

    fun propose(
        totalLayers: Int,
        nodes: List<AdaptiveNodeProfile>,
        activationBytesAtBoundary: Long,
        maxNetworkRttMillis: Double = 20.0
    ): AdaptivePlan {
        require(totalLayers > 0) { "totalLayers must be positive" }
        require(activationBytesAtBoundary >= 0L) { "activationBytesAtBoundary must not be negative" }

        val warnings = mutableListOf<String>()
        val eligible = nodes.mapNotNull { node ->
            val peak = node.measuredRuntimePeakBytes
            if (!node.online) {
                warnings += "${node.nodeId}: offline; excluded"
                return@mapNotNull null
            }
            if (peak == null || peak !in 1L..RuntimeBudget.MAX_BYTES) {
                warnings += "${node.nodeId}: missing/invalid measured peak RSS; excluded until measured"
                return@mapNotNull null
            }
            if (node.thermalStatus == ThermalStatus.HOT || node.thermalStatus == ThermalStatus.CRITICAL) {
                warnings += "${node.nodeId}: thermal backoff; excluded from new assignments"
                return@mapNotNull null
            }
            if (node.availableRamBytes <= 0L || node.fixedRuntimeBytes >= PLANNING_BUDGET_BYTES ||
                node.estimatedBytesPerLayer <= 0L) {
                warnings += "${node.nodeId}: memory/layer estimate invalid; excluded"
                return@mapNotNull null
            }
            val backend = node.backends
                .filter { it.numericalValidationPassed && it.supportedLayerFraction > 0.0 &&
                    it.supportedLayerFraction <= 1.0 && it.measuredLayersPerSecond != null &&
                    it.measuredLayersPerSecond > 0.0 && it.extraMemoryBytes >= 0L }
                .maxByOrNull { it.measuredLayersPerSecond!! * it.supportedLayerFraction }
            if (backend == null) {
                warnings += "${node.nodeId}: no measured, numerically validated backend; excluded"
                return@mapNotNull null
            }
            val perLayerBytes = node.estimatedBytesPerLayer
            val memCap = min(PLANNING_BUDGET_BYTES, node.availableRamBytes + peak)
            val maxLayersByMemory = ((memCap - node.fixedRuntimeBytes - backend.extraMemoryBytes) / perLayerBytes)
                .coerceAtLeast(0L).coerceAtMost(totalLayers.toLong()).toInt()
            if (maxLayersByMemory == 0) {
                warnings += "${node.nodeId}: projected memory leaves no safe layer capacity"
                return@mapNotNull null
            }
            Candidate(node, backend, maxLayersByMemory)
        }

        if (eligible.isEmpty()) {
            warnings += "No eligible node has verified measurements; keep the request on a separately validated single-node path."
            return AdaptivePlan(emptyList(), Double.POSITIVE_INFINITY, warnings)
        }

        // For a single autoregressive stream, layers are sequential. Splitting
        // them adds network latency, so do not automatically recruit more nodes.
        // Enumerate contiguous partitions for small clusters and minimize the
        // estimated sum of compute + activation-transfer costs.
        val candidates = eligible.take(4)
        var best: List<AdaptiveLayerAssignment>? = null
        var bestCost = Double.POSITIVE_INFINITY

        fun search(
            nodeIndex: Int,
            nextLayer: Int,
            current: MutableList<AdaptiveLayerAssignment>,
            accumulated: Double
        ) {
            if (nodeIndex == candidates.size) {
                if (nextLayer == totalLayers && accumulated < bestCost) {
                    bestCost = accumulated
                    best = current.toList()
                }
                return
            }
            val c = candidates[nodeIndex]
            val remainingNodes = candidates.size - nodeIndex - 1
            val maxCount = min(c.maxLayers, totalLayers - nextLayer - remainingNodes)
            if (maxCount <= 0) {
                search(nodeIndex + 1, nextLayer, current, accumulated)
                return
            }
            // This node may be unused, which is important when network overhead
            // makes a phone harmful. Only allow skipping before any layer is placed.
            if (nextLayer == 0 && remainingNodes > 0) {
                search(nodeIndex + 1, nextLayer, current, accumulated)
            }
            for (count in 1..maxCount) {
                val compute = count * 1000.0 / (c.backend.measuredLayersPerSecond!!)
                val isBoundary = nextLayer + count < totalLayers
                val networkCost = if (isBoundary) {
                    val rtt = c.node.measuredNetworkRttMillis ?: maxNetworkRttMillis
                    val bps = c.node.measuredNetworkBytesPerSecond ?: 1.0
                    if (bps <= 0.0) Double.POSITIVE_INFINITY
                    else rtt + activationBytesAtBoundary * 1000.0 / bps
                } else 0.0
                val mem = c.node.fixedRuntimeBytes + c.backend.extraMemoryBytes +
                    count.toLong() * c.node.estimatedBytesPerLayer
                if (mem > PLANNING_BUDGET_BYTES || mem > RuntimeBudget.MAX_BYTES) continue
                val assignment = AdaptiveLayerAssignment(
                    nodeId = c.node.nodeId,
                    layerStartInclusive = nextLayer,
                    layerCount = count,
                    backendId = c.backend.backendId,
                    estimatedComputeMillis = compute,
                    estimatedMemoryBytes = mem
                )
                current += assignment
                search(nodeIndex + 1, nextLayer + count, current, accumulated + compute + networkCost)
                current.removeAt(current.lastIndex)
            }
        }

        search(0, 0, mutableListOf(), 0.0)
        val result = best
        if (result == null) {
            warnings += "No safe contiguous partition found; do not launch distributed inference."
            return AdaptivePlan(emptyList(), Double.POSITIVE_INFINITY, warnings)
        }
        if (result.size == 1) warnings += "Single-node plan selected; extra phones were not worth estimated transfer cost."
        warnings += "Proposal only: compare with an end-to-end benchmark before accepting this plan."
        return AdaptivePlan(result, bestCost, warnings)
    }

    private data class Candidate(
        val node: AdaptiveNodeProfile,
        val backend: BackendMeasurement,
        val maxLayers: Int
    )
}
