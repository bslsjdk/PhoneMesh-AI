package com.bslsjdk.phonemesh

/**
 * Runtime view of a peer. Measurements should be refreshed during a session;
 * advertised RAM alone is not enough to decide whether a node is safe to use.
 */
data class ClusterNode(
    val nodeId: String,
    val displayName: String,
    val address: String,
    val rpcPort: Int?,
    val role: NodeRole,
    val online: Boolean,
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val measuredRuntimePeakBytes: Long?,
    val cpuCores: Int,
    val sustainedTokensPerSecond: Double?,
    val roundTripMicrosP50: Long?,
    val throughputBytesPerSecond: Long?,
    val thermalStatus: ThermalStatus,
    val lastSeenEpochMillis: Long
) {
    fun isEligibleForInference(): Boolean {
        val peak = measuredRuntimePeakBytes ?: return false
        return online &&
            nodeId.isNotBlank() &&
            availableRamBytes > 0L &&
            peak in 1L..RuntimeBudget.MAX_BYTES &&
            thermalStatus != ThermalStatus.CRITICAL
    }
}

enum class NodeRole { COORDINATOR, WORKER, UNKNOWN }

enum class ThermalStatus { UNKNOWN, COOL, WARM, HOT, CRITICAL }

/**
 * Hard runtime-RAM acceptance ceiling. This is a policy guard, not a claim
 * that a model's weight-file size equals its runtime memory requirement.
 */
object RuntimeBudget {
    const val MAX_BYTES: Long = 4L * 1024L * 1024L * 1024L

    fun acceptsMeasuredPeak(peakBytes: Long): Boolean =
        peakBytes in 1L..MAX_BYTES
}
