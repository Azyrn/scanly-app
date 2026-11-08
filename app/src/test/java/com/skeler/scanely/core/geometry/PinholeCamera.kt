package com.skeler.scanely.core.geometry

import kotlin.math.cos
import kotlin.math.sin

/**
 * A phone camera looking at a flat rectangle: [focal] in pixels, principal point at the image centre.
 * The rectangle ([widthUnits] x [heightUnits], centred on the origin of its plane) is tilted by
 * [tiltDeg] about the camera's x axis, turned by [yawDeg] about y and [rollDeg] about the optical axis,
 * and sits [distance] in front of the camera.
 */
class PinholeCamera(
    val imageWidth: Int,
    val imageHeight: Int,
    private val focal: Double = 0.8 * maxOf(imageWidth, imageHeight)
) {
    fun project(
        widthUnits: Double,
        heightUnits: Double,
        tiltDeg: Double,
        yawDeg: Double = 0.0,
        rollDeg: Double = 0.0,
        distance: Double = 2.2 * maxOf(widthUnits, heightUnits),
        shiftX: Double = 0.0,
        shiftY: Double = 0.0
    ): Quad {
        val corners = listOf(
            -widthUnits / 2 to -heightUnits / 2, widthUnits / 2 to -heightUnits / 2,
            widthUnits / 2 to heightUnits / 2, -widthUnits / 2 to heightUnits / 2
        )
        val pts = FloatArray(8)
        corners.forEachIndexed { i, (x, y) ->
            val p = rotate(doubleArrayOf(x, y, 0.0), tiltDeg, yawDeg, rollDeg)
            val zc = p[2] + distance
            pts[i * 2] = (focal * (p[0] + shiftX) / zc + imageWidth / 2.0).toFloat()
            pts[i * 2 + 1] = (focal * (p[1] + shiftY) / zc + imageHeight / 2.0).toFloat()
        }
        return Quad(pts)
    }

    private fun rotate(v: DoubleArray, tilt: Double, yaw: Double, roll: Double): DoubleArray {
        val a = Math.toRadians(tilt)
        val b = Math.toRadians(yaw)
        val c = Math.toRadians(roll)
        // about x
        val y1 = v[1] * cos(a) - v[2] * sin(a)
        val z1 = v[1] * sin(a) + v[2] * cos(a)
        // about y
        val x2 = v[0] * cos(b) + z1 * sin(b)
        val z2 = -v[0] * sin(b) + z1 * cos(b)
        // about z
        val x3 = x2 * cos(c) - y1 * sin(c)
        val y3 = x2 * sin(c) + y1 * cos(c)
        return doubleArrayOf(x3, y3, z2)
    }
}
