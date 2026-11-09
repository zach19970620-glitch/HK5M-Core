package com.unico.hk5mcore.speech

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class AudioPcmCaptureTest {

    @Test
    fun `downmixes stereo and decimates by three`() {
        // 3 帧立体声 (L,R):均值 = (100+200 + 100+200 + 100+200) / 6 = 150
        val stereo = shortArrayOf(100, 200, 100, 200, 100, 200)
        assertContentEquals(shortArrayOf(150), AudioPcmCapture.to16kMono(stereo))
    }

    @Test
    fun `output length is one sixth of interleaved input`() {
        val stereo = ShortArray(7680) { 1 }
        assertEquals(1280, AudioPcmCapture.to16kMono(stereo).size)
    }

    @Test
    fun `keeps negative samples without overflow`() {
        val stereo = shortArrayOf(-32768, -32768, -32768, -32768, -32768, -32768)
        assertContentEquals(shortArrayOf(-32768), AudioPcmCapture.to16kMono(stereo))
    }
}
