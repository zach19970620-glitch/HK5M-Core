package com.unico.hk5mcore.camera

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class Nv21CropperTest {

    @Test
    fun centerZoomCropsLumaAndChromaWithEvenOffsets() {
        val source = ByteArray(12 * 12 * 3 / 2) { it.toByte() }

        val crop = Nv21Cropper.centerZoom(source, width = 12, height = 12)

        assertEquals(8, crop.width)
        assertEquals(8, crop.height)
        assertEquals(2, crop.offsetX)
        assertEquals(2, crop.offsetY)
        assertContentEquals(
            source.copyOfRange(26, 34),
            crop.nv21.copyOfRange(0, 8),
        )
        assertContentEquals(
            source.copyOfRange(158, 166),
            crop.nv21.copyOfRange(64, 72),
        )
    }
}
