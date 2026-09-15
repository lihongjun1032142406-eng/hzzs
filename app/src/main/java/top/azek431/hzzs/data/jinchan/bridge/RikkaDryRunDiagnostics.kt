package top.azek431.hzzs.data.jinchan.bridge

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class RikkaDryRunDiagnosticSnapshot(val counters: Map<String, Long>)

/** Small process-local counter set; detailed evidence stays in the existing AppLog export path. */
object RikkaDryRunDiagnostics {
    private val counters = ConcurrentHashMap<String, AtomicLong>()

    fun observationPublished() = increment("OBSERVATION_PUBLISHED")
    fun decisionReceived() = increment("RIKKA_DECISION_RECEIVED")
    fun event(name: String) = increment(name)
    fun decisionValidated(@Suppress("UNUSED_PARAMETER") result: RikkaDecisionValidationResult) {
        increment("DECISION_VALIDATED")
    }
    fun decisionRejected(reason: RikkaDecisionRejection) {
        increment("DECISION_REJECTED")
        when (reason) {
            RikkaDecisionRejection.PROVENANCE_MISMATCH -> {
                increment("PROVENANCE_MISMATCH")
                increment("STALE_DECISION_REJECT")
            }
            RikkaDecisionRejection.UID_NOT_UNIQUE_ACTIVE -> increment("UID_REJECT")
            else -> Unit
        }
    }
    fun snapshot() = RikkaDryRunDiagnosticSnapshot(counters.mapValues { it.value.get() }.toSortedMap())
    internal fun resetForTest() = counters.clear()
    private fun increment(name: String) { counters.computeIfAbsent(name) { AtomicLong() }.incrementAndGet() }
}
