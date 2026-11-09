package com.unico.hk5mcore.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 对外输出 16kHz / 16bit / mono PCM，按 1280 字节一块回调（约 40ms）。
 *
 * 实际以 48kHz 双声道采集再转换:板子上的 USB 麦克风(HK DXMIC / 相机自带麦)只提供
 * 48kHz S16 的档位,直接请求 16kHz 单声道时系统音频层会按错误参数打开设备
 * (alsa "cannot set hw params"),read 永远阻塞、没有任何数据。
 */
class AudioPcmCapture {

    companion object {
        private const val TAG = "AudioPcmCapture"
        const val SAMPLE_RATE = 16_000
        const val FRAME_BYTES = 1280
        private const val CAPTURE_RATE = 48_000
        private const val DECIMATION = CAPTURE_RATE / SAMPLE_RATE
        private const val CHANNELS = 2
        private const val FRAME_SAMPLES = FRAME_BYTES / 2
        private const val CHANNEL = AudioFormat.CHANNEL_IN_STEREO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** 48kHz 交错双声道 → 16kHz 单声道:先左右平均,再每 3 个点取平均(简单低通 + 抽取)。 */
        fun to16kMono(stereo48k: ShortArray, length: Int = stereo48k.size): ShortArray {
            val outCount = length / (CHANNELS * DECIMATION)
            val out = ShortArray(outCount)
            var i = 0
            for (o in 0 until outCount) {
                var sum = 0
                repeat(DECIMATION) {
                    sum += stereo48k[i].toInt() + stereo48k[i + 1].toInt()
                    i += CHANNELS
                }
                out[o] = (sum / (DECIMATION * CHANNELS)).toShort()
            }
            return out
        }
    }

    private val running = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var worker: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(onFrame: (ByteArray) -> Unit): Boolean {
        if (!running.compareAndSet(false, true)) return false
        val minBuf = AudioRecord.getMinBufferSize(CAPTURE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) {
            running.set(false)
            Log.e(TAG, "invalid minBufferSize=$minBuf")
            return false
        }
        val inSamples = FRAME_SAMPLES * DECIMATION * CHANNELS
        val bufferSize = maxOf(minBuf, inSamples * 2 * 4)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                CAPTURE_RATE,
                CHANNEL,
                ENCODING,
                bufferSize,
            )
        } catch (e: Exception) {
            running.set(false)
            Log.e(TAG, "AudioRecord create failed", e)
            return false
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            running.set(false)
            Log.e(TAG, "AudioRecord not initialized")
            return false
        }
        record = recorder
        recorder.startRecording()
        worker = Thread({
            val buf = ShortArray(inSamples)
            var statAt = System.currentTimeMillis()
            var statSamples = 0
            var statPeak = 0
            while (running.get()) {
                // 读满一整块再转换,保证每次输出恰好 FRAME_BYTES
                var got = 0
                while (running.get() && got < inSamples) {
                    val n = recorder.read(buf, got, inSamples - got)
                    if (n < 0) {
                        Log.w(TAG, "AudioRecord read error: $n")
                        return@Thread
                    }
                    got += n
                }
                if (got < inSamples || !running.get()) break
                val mono = to16kMono(buf, got)
                val bytes = ByteArray(mono.size * 2)
                for (k in mono.indices) {
                    val v = mono[k].toInt()
                    bytes[k * 2] = v.toByte()
                    bytes[k * 2 + 1] = (v shr 8).toByte()
                    statPeak = maxOf(statPeak, kotlin.math.abs(v))
                }
                statSamples += mono.size
                val now = System.currentTimeMillis()
                if (now - statAt >= 1_000) {
                    Log.d(TAG, "pcm 1s: samples=$statSamples peak=$statPeak")
                    statAt = now; statSamples = 0; statPeak = 0
                }
                try {
                    onFrame(bytes)
                } catch (e: Exception) {
                    Log.w(TAG, "onFrame error", e)
                }
            }
        }, "pcm-capture").also { it.start() }
        return true
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        try {
            worker?.join(500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try {
            record?.release()
        } catch (_: Exception) {
        }
        record = null
        worker = null
    }
}
