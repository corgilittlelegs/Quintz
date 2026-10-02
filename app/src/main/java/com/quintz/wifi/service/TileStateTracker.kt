package com.quintz.wifi.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object TileStateTracker {
    private val _tileAddedFlow = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 1)
    val tileAddedFlow: SharedFlow<Boolean> = _tileAddedFlow.asSharedFlow()

    fun notifyTileState(added: Boolean) {
        _tileAddedFlow.tryEmit(added)
    }
}
