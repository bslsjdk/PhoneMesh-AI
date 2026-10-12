package com.bslsjdk.phonemesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicWorkloadPlannerTest {
    private fun node(
        id: String,
        online: Boolean = true,
        peakBytes: Long? = 512L * MIB,
        thermal: ThermalStatus = ThermalStatus.COOL,
        validated: Boolean = true,
        availableRamBytes: Long = 2L * GIB
    ) = AdaptiveNodeProfile(
        nodeId = id,
        online = online,
        measuredRuntimePeakBytes = peakBytes,
        availableRamBytes = availableRamBytes,
        thermalStatus = thermal,
        measuredNetworkRttMillis = 1.0,
        measuredNetworkBytesPerSecond = 10_000_000.0,
        fixedRuntimeBytes = 128L * MIB,
        estimatedBytesPerLayer = 64L * MIB,
        backends = listOf(
            BackendMeasurement(
                backendId = "cpu",
                measuredLayersPerSecond = 10.0,
                supportedLayerFraction = 1.0,
                initializationMillis = 100,
                extraMemoryBytes = 32L * MIB,
                numericalValidationPassed = validated
            )
        )
    )

    @Test
    fun validPlanCoversEveryLayerExactlyOnce() {
        val plan = DynamicWorkloadPlanner.propose(
            totalLayers = 8,
            nodes = listOf(node("a"), node("b")),
            activationBytesAtBoundary = 1024
        )

        assertTrue(plan.assignments.isNotEmpty())
        val ordered = plan.assignments.sortedBy { it.layerStartInclusive }
        var next = 0
        ordered.forEach { assignment ->
            assertEquals(next, assignment.layerStartInclusive)
            assertTrue(assignment.layerCount > 0)
            assertTrue(assignment.estimatedMemoryBytes <= 3L * GIB + 512L * MIB)
            next += assignment.layerCount
        }
        assertEquals(8, next)
    }

    @Test
    fun offlineUnknownRssAndHotNodesAreExcluded() {
        val plan = DynamicWorkloadPlanner.propose(
            totalLayers = 4,
            nodes = listOf(
                node("offline", online = false),
                node("unknown-rss", peakBytes = null),
                node("hot", thermal = ThermalStatus.HOT)
            ),
            activationBytesAtBoundary = 0
        )

        assertTrue(plan.assignments.isEmpty())
        assertTrue(plan.warnings.any { it.contains("offline") })
        assertTrue(plan.warnings.any { it.contains("missing/invalid measured peak RSS") })
        assertTrue(plan.warnings.any { it.contains("thermal backoff") })
    }


    @Test
    fun lowAvailableRamIsNotTreatedAsSafeJustBecausePeakRssWasPreviouslyLow() {
        val plan = DynamicWorkloadPlanner.propose(
            totalLayers = 2,
            nodes = listOf(node("low-free-ram", availableRamBytes = 128L * MIB)),
            activationBytesAtBoundary = 0
        )

        assertTrue(plan.assignments.isEmpty())
        assertTrue(plan.warnings.any { it.contains("projected memory leaves no safe layer capacity") })
    }

    @Test
    fun unvalidatedBackendIsNeverAssigned() {
        val plan = DynamicWorkloadPlanner.propose(
            totalLayers = 4,
            nodes = listOf(node("unvalidated", validated = false)),
            activationBytesAtBoundary = 0
        )

        assertTrue(plan.assignments.isEmpty())
        assertTrue(plan.warnings.any { it.contains("no measured, numerically validated backend") })
        assertFalse(plan.assignments.any { it.nodeId == "unvalidated" })
    }

    private companion object {
        const val MIB = 1024L * 1024L
        const val GIB = 1024L * MIB
    }
}
