package com.bslsjdk.phonemesh

/**
 * Adapter boundary between PhoneMesh (device management, transport, policy,
 * measurements) and a replaceable inference engine such as prima.cpp.
 *
 * No engine is wired in yet. Implementations must not report READY until
 * their worker/coordinator process has passed a real health check.
 */
interface InferenceBackend {
    val id: String

    suspend fun inspectLocalNode(): NodeCapabilities

    suspend fun startWorker(config: WorkerConfig): BackendResult

    suspend fun startCoordinator(config: CoordinatorConfig): BackendResult

    suspend fun health(): BackendHealth

    suspend fun stop()
}

data class NodeCapabilities(
    val architecture: String,
    val abi: String,
    val cpuCores: Int,
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val runtimePeakBytes: Long?,
    val supportsNativeBackend: Boolean,
    val engineVersion: String?
)

data class WorkerConfig(
    val nodeId: String,
    val bindAddress: String,
    val port: Int,
    val modelShardPath: String?,
    val assignedLayerStart: Int,
    val assignedLayerCount: Int,
    val maxRuntimeBytes: Long = RuntimeBudget.MAX_BYTES
)

data class CoordinatorConfig(
    val modelId: String,
    val modelManifestPath: String,
    val workerEndpoints: List<String>,
    val layerAssignments: List<LayerAssignment>,
    val contextTokens: Int,
    val maxRuntimeBytes: Long = RuntimeBudget.MAX_BYTES
)

data class LayerAssignment(
    val nodeId: String,
    val layerStartInclusive: Int,
    val layerCount: Int,
    val estimatedPeakRuntimeBytes: Long
) {
    val layerEndExclusive: Int
        get() = layerStartInclusive + layerCount
}

data class BackendResult(
    val success: Boolean,
    val message: String,
    val processId: Long? = null
)

data class BackendHealth(
    val ready: Boolean,
    val message: String,
    val runtimePeakBytes: Long?,
    val thermalStatus: ThermalStatus
)

/**
 * Conservative validation before launching a backend. A missing memory
 * estimate is not treated as zero: it fails closed until measured.
 */
object ClusterPlanValidator {
    fun validate(
        totalLayers: Int,
        assignments: List<LayerAssignment>,
        knownNodeIds: Set<String>
    ): List<String> {
        val errors = mutableListOf<String>()
        if (totalLayers <= 0) errors += "totalLayers must be positive"
        if (assignments.isEmpty()) errors += "No layer assignments supplied"

        assignments.forEach { assignment ->
            if (assignment.nodeId !in knownNodeIds) {
                errors += "Unknown node: ${assignment.nodeId}"
            }
            if (assignment.layerStartInclusive < 0 || assignment.layerCount <= 0 ||
                assignment.layerEndExclusive > totalLayers) {
                errors += "Invalid layer range for node ${assignment.nodeId}"
            }
            if (!RuntimeBudget.acceptsMeasuredPeak(assignment.estimatedPeakRuntimeBytes)) {
                errors += "Node ${assignment.nodeId} exceeds the 4 GiB runtime budget or has no valid estimate"
            }
        }

        val ordered = assignments.sortedBy { it.layerStartInclusive }
        var expectedStart = 0
        ordered.forEach { assignment ->
            if (assignment.layerStartInclusive != expectedStart) {
                errors += "Layer ranges overlap or leave a gap near layer $expectedStart"
            }
            expectedStart = assignment.layerEndExclusive
        }
        if (expectedStart != totalLayers) {
            errors += "Layer plan covers $expectedStart of $totalLayers layers"
        }
        return errors.distinct()
    }
}
