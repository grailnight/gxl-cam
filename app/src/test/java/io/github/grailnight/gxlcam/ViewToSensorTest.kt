package io.github.grailnight.gxlcam

import io.github.grailnight.gxlcam.camera.CameraController
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewToSensorTest {
    private fun check(expected: Pair<Float, Float>, actual: Pair<Float, Float>) {
        assertEquals(expected.first, actual.first, 1e-6f)
        assertEquals(expected.second, actual.second, 1e-6f)
    }

    @Test
    fun backCamera90() {
        // Top edge of the portrait preview is the sensor's left edge.
        check(0f to 0.5f, CameraController.viewToSensor(0.5f, 0f, 90, mirrored = false))
        // Top-right corner of the view is the sensor's top-left corner.
        check(0f to 0f, CameraController.viewToSensor(1f, 0f, 90, mirrored = false))
        check(0.5f to 0.5f, CameraController.viewToSensor(0.5f, 0.5f, 90, mirrored = false))
    }

    @Test
    fun frontCamera270Mirrored() {
        // Sensor rotated 90° CCW then mirrored: view top-left is sensor bottom-right.
        check(1f to 1f, CameraController.viewToSensor(0f, 0f, 270, mirrored = true))
        check(0f to 0f, CameraController.viewToSensor(1f, 1f, 270, mirrored = true))
    }

    @Test
    fun noRotation() {
        check(0.25f to 0.75f, CameraController.viewToSensor(0.25f, 0.75f, 0, mirrored = false))
    }
}
