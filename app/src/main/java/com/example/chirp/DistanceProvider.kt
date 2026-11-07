package com.example.chirp

import kotlinx.coroutines.delay
import kotlin.random.Random

/** A scan source independent of Compose. Real audio work must use a background dispatcher. */
interface DistanceProvider {
    suspend fun scanMeters(): Double
}

/** Demo data only: never represents an acoustic measurement. */
class SimulatedDistanceProvider : DistanceProvider {
    override suspend fun scanMeters(): Double {
        delay(1_500) // Cancellable, without blocking the UI thread.
        return Random.nextInt(50, 301) / 100.0
    }
}
