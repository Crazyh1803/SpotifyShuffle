package com.spotifytrueshuffle.background

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-process event bus that connects [PlaylistBuildForegroundService] to [MainViewModel].
 *
 * Using a process-level singleton means the service can push progress/results to the
 * ViewModel without a bound service or BroadcastReceiver.
 *
 * Both flows use a buffer so fast emits from the service are never dropped:
 *  - [progress] keeps the last 16 progress messages (older ones are dropped silently).
 *  - [results] buffers exactly 1 result so it is available even if the ViewModel collects
 *    it slightly after the service emits it.
 */
object BuildEventBus {

    data class Progress(val message: String, val step: Int, val total: Int)

    private val _progress = MutableSharedFlow<Progress>(extraBufferCapacity = 16)
    val progress = _progress.asSharedFlow()

    private val _results = MutableSharedFlow<PlaylistBuildService.BuildResult>(extraBufferCapacity = 1)
    val results = _results.asSharedFlow()

    /**
     * Non-suspending emit for progress — safe to call from a plain (non-suspend) lambda.
     * Always succeeds because [extraBufferCapacity] == 16.
     */
    fun tryEmitProgress(message: String, step: Int, total: Int) {
        _progress.tryEmit(Progress(message, step, total))
    }

    /**
     * Suspending emit for the final build result.
     * Called once per build from a coroutine inside [PlaylistBuildForegroundService].
     */
    suspend fun emitResult(result: PlaylistBuildService.BuildResult) {
        _results.emit(result)
    }
}
