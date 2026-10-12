package com.bslsjdk.phonemesh

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveReplanGateTest {
    private fun samples(tps: Double, thermal: ThermalStatus = ThermalStatus.COOL) =
        listOf(1L, 2L, 3L).map {
            PlanPerformanceSample(
                timestampMillis = it * 1000L,
                tokensPerSecond = tps,
                peakRssBytes = 512L * MIB,
                thermalStatus = thermal
            )
        }

    @Test
    fun acceptsMeasuredGainOnlyAfterEnoughSamples() {
        val gate = AdaptiveReplanGate(windowSize = 3, minimumGainFraction = 0.10)
        val result = gate.evaluate(
            nowMillis = 20_000L,
            currentSamples = samples(10.0),
            proposedSamples = samples(12.0),
            proposedMaxProjectedNodeRssBytes = 1L * GIB
        )

        assertEquals(ReplanDisposition.ACCEPT_PROPOSAL, result.disposition)
        assertEquals(10.0, result.currentMedianTokensPerSecond!!, 0.0001)
        assertEquals(12.0, result.proposedMedianTokensPerSecond!!, 0.0001)
    }

    @Test
    fun keepsCurrentPlanWhenGainIsTooSmallAndHonorsCooldown() {
        val gate = AdaptiveReplanGate(windowSize = 3, minimumGainFraction = 0.10, cooldownMillis = 10_000L)
        val first = gate.evaluate(
            nowMillis = 20_000L,
            currentSamples = samples(10.0),
            proposedSamples = samples(12.0),
            proposedMaxProjectedNodeRssBytes = 1L * GIB
        )
        assertEquals(ReplanDisposition.ACCEPT_PROPOSAL, first.disposition)

        val duringCooldown = gate.evaluate(
            nowMillis = 25_000L,
            currentSamples = samples(10.0),
            proposedSamples = samples(12.0),
            proposedMaxProjectedNodeRssBytes = 1L * GIB
        )
        assertEquals(ReplanDisposition.KEEP_CURRENT, duringCooldown.disposition)

        val smallGain = gate.evaluate(
            nowMillis = 40_000L,
            currentSamples = samples(10.0),
            proposedSamples = samples(10.5),
            proposedMaxProjectedNodeRssBytes = 1L * GIB
        )
        assertEquals(ReplanDisposition.KEEP_CURRENT, smallGain.disposition)
    }

    @Test
    fun safetySignalsBypassCooldownAndUnsafeCandidatesAreRejected() {
        val gate = AdaptiveReplanGate(windowSize = 3, cooldownMillis = 60_000L)
        val first = gate.evaluate(
            nowMillis = 20_000L,
            currentSamples = samples(10.0),
            proposedSamples = samples(12.0),
            proposedMaxProjectedNodeRssBytes = 1L * GIB
        )
        assertEquals(ReplanDisposition.ACCEPT_PROPOSAL, first.disposition)

        val safety = gate.evaluate(
            nowMillis = 21_000L,
            currentSamples = samples(10.0),
            proposedSamples = samples(12.0),
            proposedMaxProjectedNodeRssBytes = 1L * GIB,
            forceSafetyReplan = true
        )
        assertEquals(ReplanDisposition.SAFETY_REPLAN_REQUIRED, safety.disposition)

        val hotCandidate = gate.evaluate(
            nowMillis = 90_000L,
            currentSamples = samples(10.0),
            proposedSamples = samples(12.0, ThermalStatus.HOT),
            proposedMaxProjectedNodeRssBytes = 1L * GIB
        )
        assertEquals(ReplanDisposition.REJECT_PROPOSAL, hotCandidate.disposition)
    }

    private companion object {
        const val MIB = 1024L * 1024L
        const val GIB = 1024L * MIB
    }
}
