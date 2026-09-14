package top.azek431.hzzs.data.jinchan.frame

import top.azek431.hzzs.service.capture.CapturedFrame

/** Opaque identity for one capture/runtime generation. */
@JvmInline
value class JinChanFrameSessionId(val value: Long)

/**
 * Durable metadata for an accepted frame. Deliberately contains no pixel/buffer reference, so it
 * remains safe after the caller closes the corresponding [CapturedFrame] lease.
 */
data class JinChanLatestFrameMetadata(
    val sessionId: JinChanFrameSessionId,
    val frameId: Long,
    val timestampElapsedRealtimeNanos: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val sourceRotationDegrees: Int,
    val canonicalWidth: Int,
    val canonicalHeight: Int,
) {
    val sourceSequence: Long get() = frameId
}

enum class JinChanFrameRuntimeStatus {
    VALID,
    EMPTY,
    STALE,
    WRONG_SESSION,
    INACTIVE,
    OUT_OF_ORDER,
    INVALID_FRAME,
}

data class JinChanFrameRuntimeResult(
    val status: JinChanFrameRuntimeStatus,
    val metadata: JinChanLatestFrameMetadata? = null,
)

/**
 * Session-scoped, metadata-only latest-frame registry for the JinChan capture path.
 *
 * [accept] borrows a frame only for the synchronous bridge call and stores metadata, never the
 * canonical frame or its pooled pixels. Callers continue to own and close every capture lease.
 * All methods are synchronized because capture completion and lifecycle changes may race.
 */
class JinChanFrameRuntime {
    private var nextSessionId = 0L
    private var activeSessionId: JinChanFrameSessionId? = null
    private var latestMetadata: JinChanLatestFrameMetadata? = null

    @Synchronized
    fun startSession(): JinChanFrameSessionId {
        if (nextSessionId == Long.MAX_VALUE) {
            // Never reuse an identity: exhausting the counter is safer than accepting old frames.
            error("JinChan frame session identity exhausted")
        }
        return JinChanFrameSessionId(++nextSessionId).also {
            activeSessionId = it
            latestMetadata = null
        }
    }

    @Synchronized
    fun stopSession(sessionId: JinChanFrameSessionId): JinChanFrameRuntimeStatus {
        val active = activeSessionId ?: return JinChanFrameRuntimeStatus.INACTIVE
        if (sessionId != active) return JinChanFrameRuntimeStatus.WRONG_SESSION
        latestMetadata = null
        activeSessionId = null
        return JinChanFrameRuntimeStatus.INACTIVE
    }

    /** Clears the current frame without changing the active session identity. */
    @Synchronized
    fun resetSession(sessionId: JinChanFrameSessionId): JinChanFrameRuntimeStatus {
        val active = activeSessionId ?: return JinChanFrameRuntimeStatus.INACTIVE
        if (sessionId != active) return JinChanFrameRuntimeStatus.WRONG_SESSION
        latestMetadata = null
        return JinChanFrameRuntimeStatus.EMPTY
    }

    @Synchronized
    fun accept(
        sessionId: JinChanFrameSessionId,
        source: CapturedFrame,
        nowElapsedRealtimeNanos: Long,
        staleTimeoutNanos: Long,
    ): JinChanFrameRuntimeResult {
        require(staleTimeoutNanos >= 0L)
        val active = activeSessionId ?: return result(JinChanFrameRuntimeStatus.INACTIVE)
        if (sessionId != active) return result(JinChanFrameRuntimeStatus.WRONG_SESSION)
        if (isStaleOrInvalid(source.elapsedRealtimeNanos, nowElapsedRealtimeNanos, staleTimeoutNanos)) {
            return result(
                if (source.elapsedRealtimeNanos > nowElapsedRealtimeNanos) {
                    JinChanFrameRuntimeStatus.INVALID_FRAME
                } else {
                    JinChanFrameRuntimeStatus.STALE
                },
            )
        }
        if (latestMetadata?.frameId?.let { source.sequence <= it } == true) {
            return result(JinChanFrameRuntimeStatus.OUT_OF_ORDER)
        }

        val canonical = when (val bridged = JinChanFrameBridge.adapt(source)) {
            is JinChanFrameBridgeResult.Valid -> bridged.frame
            is JinChanFrameBridgeResult.Rejected -> return result(JinChanFrameRuntimeStatus.INVALID_FRAME)
        }
        return acceptCanonical(sessionId, canonical, nowElapsedRealtimeNanos, staleTimeoutNanos)
    }

    /** H3 entry point for an already adapted same-lease frame; avoids a second bridge adaptation. */
    @Synchronized
    fun acceptCanonical(
        sessionId: JinChanFrameSessionId,
        canonical: JinChanCanonicalFrame,
        nowElapsedRealtimeNanos: Long,
        staleTimeoutNanos: Long,
    ): JinChanFrameRuntimeResult {
        require(staleTimeoutNanos >= 0L)
        val active = activeSessionId ?: return result(JinChanFrameRuntimeStatus.INACTIVE)
        if (sessionId != active) return result(JinChanFrameRuntimeStatus.WRONG_SESSION)
        if (isStaleOrInvalid(canonical.sourceElapsedRealtimeNanos, nowElapsedRealtimeNanos, staleTimeoutNanos)) {
            return result(if (canonical.sourceElapsedRealtimeNanos > nowElapsedRealtimeNanos) JinChanFrameRuntimeStatus.INVALID_FRAME else JinChanFrameRuntimeStatus.STALE)
        }
        if (latestMetadata?.frameId?.let { canonical.sourceSequence <= it } == true) return result(JinChanFrameRuntimeStatus.OUT_OF_ORDER)
        val metadata = JinChanLatestFrameMetadata(
            sessionId = sessionId,
            frameId = canonical.sourceSequence,
            timestampElapsedRealtimeNanos = canonical.sourceElapsedRealtimeNanos,
            sourceWidth = canonical.sourceWidth,
            sourceHeight = canonical.sourceHeight,
            sourceRotationDegrees = canonical.sourceRotationDegrees,
            canonicalWidth = canonical.canonicalWidth,
            canonicalHeight = canonical.canonicalHeight,
        )
        latestMetadata = metadata
        return JinChanFrameRuntimeResult(JinChanFrameRuntimeStatus.VALID, metadata)
    }

    @Synchronized
    fun latest(
        sessionId: JinChanFrameSessionId,
        nowElapsedRealtimeNanos: Long,
        staleTimeoutNanos: Long,
    ): JinChanFrameRuntimeResult {
        require(staleTimeoutNanos >= 0L)
        val active = activeSessionId ?: return result(JinChanFrameRuntimeStatus.INACTIVE)
        if (sessionId != active) return result(JinChanFrameRuntimeStatus.WRONG_SESSION)
        val metadata = latestMetadata ?: return result(JinChanFrameRuntimeStatus.EMPTY)
        if (isStaleOrInvalid(metadata.timestampElapsedRealtimeNanos, nowElapsedRealtimeNanos, staleTimeoutNanos)) {
            latestMetadata = null
            return result(
                if (metadata.timestampElapsedRealtimeNanos > nowElapsedRealtimeNanos) {
                    JinChanFrameRuntimeStatus.INVALID_FRAME
                } else {
                    JinChanFrameRuntimeStatus.STALE
                },
            )
        }
        return JinChanFrameRuntimeResult(JinChanFrameRuntimeStatus.VALID, metadata)
    }

    private fun result(status: JinChanFrameRuntimeStatus) = JinChanFrameRuntimeResult(status)

    private fun isStaleOrInvalid(timestamp: Long, now: Long, timeout: Long): Boolean =
        timestamp < 0L || now < timestamp || now - timestamp > timeout
}
