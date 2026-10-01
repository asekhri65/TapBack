package com.aaravsekhri.dot.guide.visual

import android.content.Intent
import kotlinx.coroutines.flow.Flow

/**
 * The user's consent to capture, for exactly one session.
 *
 * Android hands back a single-use token; it cannot be stored, reused after the
 * session ends, or carried into a later guide. Dot keeps it only as long as the
 * projection is running and asks again next time.
 */
data class ProjectionPermission(
    val resultCode: Int,
    val data: Intent,
)

/** Where frames come from. Abstracted so the tracker can be tested without one. */
interface FrameSource {
    val frames: Flow<CapturedFrame>
    suspend fun start(permission: ProjectionPermission)
    suspend fun stop()
}
