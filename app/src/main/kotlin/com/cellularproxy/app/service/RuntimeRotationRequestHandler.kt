package com.cellularproxy.app.service

import com.cellularproxy.shared.rotation.RotationTransitionResult
import java.io.Closeable

interface RuntimeRotationRequestHandler : Closeable {
    fun rotateMobileData(): RotationTransitionResult

    fun rotateAirplaneMode(): RotationTransitionResult
}
