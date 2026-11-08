package com.example.chirp.spatial

import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialDirectionTest {
    @Test fun cameraForwardIsNegativeZ() {
        assertTrue(spatialDirection(0f, 0f, -2f).contains("ahead"))
        assertTrue(spatialDirection(0f, 0f, 2f).contains("behind"))
    }
    @Test fun directionsFollowCameraAxes() {
        assertTrue(spatialDirection(2f, 0f, 0f).contains("right"))
        assertTrue(spatialDirection(-2f, 0f, 0f).contains("left"))
        assertTrue(spatialDirection(0f, 1f, -1f).contains("above"))
        assertTrue(spatialDirection(0f, -1f, -1f).contains("below"))
    }
    @Test fun distanceIncludesVerticalSeparation() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.US)
            assertTrue(spatialDirection(0f, 3f, -4f).contains("5.0 meters"))
        } finally { java.util.Locale.setDefault(original) }
    }
}
