package com.unico.hk5mcore.camera

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertSame

class Nv21ResizerTest {

    @Test
    fun resizeSelectsNearestLumaAndChromaSamples() {
        val source = ByteArray(4 * 4 * 3 / 2) { it.toByte() }

        val resized = Nv21Resizer.resize(
            source = source,
            sourceWidth = 4,
            sourceHeight = 4,
            targetWidth = 2,
            targetHeight = 2,
        )

        assertContentEquals(
            byteArrayOf(0, 2, 8, 10, 16, 17),
            resized,
        )
    }

    @Test
    fun resizeReturnsSameBufferWhenDimensionsDoNotChange() {
        val source = ByteArray(4 * 4 * 3 / 2)

        val resized = Nv21Resizer.resize(source, 4, 4, 4, 4)

        assertSame(source, resized)
    }
}
