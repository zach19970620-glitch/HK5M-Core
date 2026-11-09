package com.unico.hk5mcore.speech

import android.content.Context
import android.util.Log
import com.unico.hk5mcore.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class SpeechUiState(
    val status: String = "未开始",
    val listening: Boolean = false,
    val committedText: String = "",
    val partialText: String = "",
    val error: String? = null,
    val credentialsMissing: Boolean = false,
    val networkProbe: NetworkProbeReport = NetworkProbeReport(),
)

/**
 * 麦克风 PCM → 火山引擎豆包流式 ASR → 实时字幕。
 *
 * 文档：https://www.volcengine.com/docs/6561/1354869
 */
class SpeechRecognitionBridge(
    private val appContext: Context,
) {

    companion object {
        private const val TAG = "SpeechBridge"
    }

    private val lifecycleLock = Any()
    private val active = AtomicBoolean(false)
    private val capture = AudioPcmCapture()
    private var client: VolcAsrClient? = null
    private val micExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "speech-mic-start").apply { isDaemon = true }
    }

    private val _ui = MutableStateFlow(SpeechUiState())
    val ui: StateFlow<SpeechUiState> = _ui.asStateFlow()

    fun hasCredentials(): Boolean {
        if (BuildConfig.VOLC_API_KEY.isNotBlank()) return true
        return BuildConfig.VOLC_APP_KEY.isNotBlank() &&
            BuildConfig.VOLC_ACCESS_KEY.isNotBlank()
    }

    fun start(): Boolean {
        synchronized(lifecycleLock) {
            if (active.get()) return true
            if (!hasCredentials()) {
                _ui.update {
                    it.copy(
                        status = "未配置密钥",
                        credentialsMissing = true,
                        error = "请在 local.properties 填写 volc.apiKey，或 volc.appKey + volc.accessKey",
                        listening = false,
                    )
                }
                return false
            }

            _ui.update {
                it.copy(
                    status = "连接中…",
                    listening = true,
                    error = null,
                    credentialsMissing = false,
                    partialText = "",
                )
            }
            active.set(true)

            val asr = VolcAsrClient(
                appKey = BuildConfig.VOLC_APP_KEY,
                accessKey = BuildConfig.VOLC_ACCESS_KEY,
                apiKey = BuildConfig.VOLC_API_KEY,
                resourceId = BuildConfig.VOLC_RESOURCE_ID,
                wsUrl = BuildConfig.VOLC_WS_URL,
                listener = object : VolcAsrClient.Listener {
                    override fun onConnecting(logId: String) {
                        if (!active.get()) return
                        _ui.update { it.copy(status = "握手中…", error = null) }
                    }

                    override fun onSessionReady(logId: String) {
                        Log.i(TAG, "session ready logid=$logId")
                        if (!active.get()) return
                        _ui.update { it.copy(status = "识别中", error = null) }
                        // 切到独立线程开麦，避免在 OkHttp 回调线程里初始化 AudioRecord
                        micExecutor.execute {
                            if (!active.get()) return@execute
                            val ok = capture.start { pcm ->
                                if (active.get()) client?.writePcm(pcm)
                            }
                            if (!ok) {
                                synchronized(lifecycleLock) {
                                    stopInternal("错误", "无法打开麦克风")
                                }
                            }
                        }
                    }

                    override fun onPartial(text: String) {
                        if (!active.get()) return
                        _ui.update { it.copy(partialText = text, status = "识别中") }
                    }

                    override fun onDefinite(text: String) {
                        if (!active.get()) return
                        _ui.update { state ->
                            val next = buildString {
                                append(state.committedText)
                                if (state.committedText.isNotEmpty() &&
                                    !state.committedText.endsWith('\n')
                                ) {
                                    append('\n')
                                }
                                append(text)
                            }
                            state.copy(committedText = next, partialText = "")
                        }
                    }

                    override fun onError(message: String) {
                        Log.e(TAG, "asr error: $message")
                        synchronized(lifecycleLock) {
                            if (!active.get()) return
                            val hint = if (
                                message.contains("abort", ignoreCase = true) ||
                                message.contains("SocketException", ignoreCase = true)
                            ) {
                                "$message\n若反复失败，请补全 volc.appKey(AppId) + volc.accessKey，并确认已开通 seedasr 套餐。"
                            } else {
                                message
                            }
                            stopInternal("错误", hint)
                        }
                    }

                    override fun onClosed() {
                        Log.i(TAG, "asr closed")
                    }
                },
            )
            client = asr
            asr.connect()
            return true
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            stopInternal("已停止", error = null)
        }
    }

    fun clear() {
        _ui.update { it.copy(committedText = "", partialText = "", error = null) }
    }

    fun testNetwork() {
        NetworkProbe.runAsync(appContext) { report ->
            _ui.update { it.copy(networkProbe = report) }
        }
    }

    fun release() {
        synchronized(lifecycleLock) {
            stopInternal("已释放", error = null)
            client?.release()
            client = null
        }
        micExecutor.shutdownNow()
    }

    private fun stopInternal(updateStatus: String, error: String?) {
        if (!active.getAndSet(false) && client == null) {
            _ui.update {
                it.copy(status = updateStatus, listening = false, error = error)
            }
            return
        }
        capture.stop()
        try {
            client?.finish()
        } catch (e: Exception) {
            Log.w(TAG, "finish", e)
        }
        try {
            client?.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "disconnect", e)
        }
        client = null
        _ui.update {
            it.copy(
                status = updateStatus,
                listening = false,
                partialText = if (error != null) it.partialText else "",
                error = error,
            )
        }
    }
}
