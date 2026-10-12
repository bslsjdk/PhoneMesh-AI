package com.bslsjdk.phonemesh

/**
 * End-to-end measurement for a candidate plan. Throughput must be measured
 * across the complete request path, not inferred from isolated CPU/NPU speed.
 */
data class PlanPerformanceSample(
    val timestampMillis: Long,
    val tokensPerSecond: Double,
    val peakRssBytes: Long,
    val thermalStatus: ThermalStatus
)

enum class ReplanDisposition {
    KEEP_CURRENT,
    ACCEPT_PROPOSAL,
    REJECT_PROPOSAL,
    SAFETY_REPLAN_REQUIRED
}

data class ReplanEvaluation(
    val disposition: ReplanDisposition,
    val reason: String,
    val currentMedianTokensPerSecond: Double? = null,
    val proposedMedianTokensPerSecond: Double? = null
)

/**
 * Hysteresis gate for proposals from DynamicWorkloadPlanner.
 *
 * This class does not collect telemetry or launch inference. The caller must
 * provide real end-to-end samples for both plans and separately execute any
 * safety replan. Defaults are conservative starting values, not benchmark-
 * validated universal thresholds.
 */
class AdaptiveReplanGate(
    private val windowSize: Int = 3,
    private val minimumGainFraction: Double = 0.10,
    private val cooldownMillis: Long = 10_000L,
    private val maxProjectedNodeRssBytes: Long =
        RuntimeBudget.MAX_BYTES - 512L * 1024L * 1024L
) {
    private var lastAcceptedAtMillis: Long? = null

    init {
        require(windowSize >= 1) { "windowSize must be positive" }
        require(minimumGainFraction.isFinite() && minimumGainFraction >= 0.0) {
            "minimumGainFraction must be finite and non-negative"
        }
        require(cooldownMillis >= 0L) { "cooldownMillis must not be negative" }
        require(maxProjectedNodeRssBytes in 1L..RuntimeBudget.MAX_BYTES) {
            "maxProjectedNodeRssBytes must be within the runtime budget"
        }
    }

    fun evaluate(
        nowMillis: Long,
        currentSamples: List<PlanPerformanceSample>,
        proposedSamples: List<PlanPerformanceSample>,
        proposedMaxProjectedNodeRssBytes: Long,
        forceSafetyReplan: Boolean = false
    ): ReplanEvaluation {
        if (proposedMaxProjectedNodeRssBytes !in 1L..maxProjectedNodeRssBytes) {
            return ReplanEvaluation(
                ReplanDisposition.REJECT_PROPOSAL,
                "Proposed plan exceeds the conservative per-node memory budget"
            )
        }

        if (forceSafetyReplan || currentSamples.any(::isUnsafeSample)) {
            return ReplanEvaluation(
                ReplanDisposition.SAFETY_REPLAN_REQUIRED,
                "Current plan has a safety signal; do not wait for performance cooldown"
            )
        }

        if (proposedSamples.any(::isUnsafeSample)) {
            return ReplanEvaluation(
                ReplanDisposition.REJECT_PROPOSAL,
                "Candidate measurements contain invalid throughput, excessive RSS, or hot/critical thermal state"
            )
        }

        if (currentSamples.size < windowSize || proposedSamples.size < windowSize) {
            return ReplanEvaluation(
                ReplanDisposition.KEEP_CURRENT,
                "Not enough end-to-end samples to compare plans safely"
            )
        }

        val currentMedian = median(currentSamples.takeLast(windowSize).map { it.tokensPerSecond })
        val proposedMedian = median(proposedSamples.takeLast(windowSize).map { it.tokensPerSecond })
        if (!currentMedian.isFinite() || currentMedian <= 0.0 ||
            !proposedMedian.isFinite() || proposedMedian <= 0.0) {
            return ReplanEvaluation(
                ReplanDisposition.KEEP_CURRENT,
                "Throughput measurements are not valid for comparison",
                currentMedian,
                proposedMedian
            )
        }

        val gain = (proposedMedian - currentMedian) / currentMedian
        if (gain < minimumGainFraction) {
            return ReplanEvaluation(
                ReplanDisposition.KEEP_CURRENT,
                "Measured end-to-end gain is below the configured threshold",
                currentMedian,
                proposedMedian
            )
        }

        val lastAccepted = lastAcceptedAtMillis
        if (lastAccepted != null && (nowMillis < lastAccepted || nowMillis - lastAccepted < cooldownMillis)) {
            return ReplanEvaluation(
                ReplanDisposition.KEEP_CURRENT,
                "Replan cooldown is active",
                currentMedian,
                proposedMedian
            )
        }

        lastAcceptedAtMillis = nowMillis
        return ReplanEvaluation(
            ReplanDisposition.ACCEPT_PROPOSAL,
            "Candidate passed memory, safety, sample-window, gain, and cooldown checks",
            currentMedian,
            proposedMedian
        )
    }

    private fun isUnsafeSample(sample: PlanPerformanceSample): Boolean =
        !sample.tokensPerSecond.isFinite() || sample.tokensPerSecond <= 0.0 ||
            sample.peakRssBytes !in 1L..RuntimeBudget.MAX_BYTES ||
            sample.thermalStatus == ThermalStatus.HOT ||
            sample.thermalStatus == ThermalStatus.CRITICAL

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else (sorted[middle - 1] + sorted[middle]) / 2.0
    }
}
