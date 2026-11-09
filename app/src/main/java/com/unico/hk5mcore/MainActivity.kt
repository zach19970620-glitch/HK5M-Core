package com.unico.hk5mcore

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.unico.hk5mcore.camera.FrameCaptureManager
import com.unico.hk5mcore.camera.UsbCameraMonitor
import com.unico.hk5mcore.inference.InferenceBridge
import com.unico.hk5mcore.speech.SpeechRecognitionBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 三个基础模块的最小联调页:相机抓帧、人脸推理、语音听写。互相独立,各自一组按钮。
 */
class MainActivity : ComponentActivity() {

    private lateinit var captureManager: FrameCaptureManager
    private lateinit var usbMonitor: UsbCameraMonitor
    private lateinit var inferenceBridge: InferenceBridge
    private lateinit var speechBridge: SpeechRecognitionBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        captureManager = FrameCaptureManager(applicationContext)
        usbMonitor = UsbCameraMonitor(applicationContext)
        inferenceBridge = InferenceBridge(applicationContext)
        speechBridge = SpeechRecognitionBridge(applicationContext)
        captureManager.onFrame = { frame -> inferenceBridge.onFrame(frame) }
        usbMonitor.start()

        setContent {
            val scope = rememberCoroutineScope()
            var previewView by remember { mutableStateOf<PreviewView?>(null) }
            var message by remember { mutableStateOf<String?>(null) }
            var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { ok ->
                val action = pendingAction
                pendingAction = null
                if (ok) action?.invoke() else message = "权限被拒绝"
            }

            /** 已授权直接执行,否则申请后再执行。 */
            fun withPermission(permission: String, action: () -> Unit) {
                if (granted(permission)) action() else {
                    pendingAction = action
                    permissionLauncher.launch(permission)
                }
            }

            val usbDevices by usbMonitor.devices.collectAsState()
            val stats by captureManager.stats.collectAsState()
            val faces by inferenceBridge.faces.collectAsState()
            val inferStatus by inferenceBridge.status.collectAsState()
            val speech by speechBridge.ui.collectAsState()

            Column(
                Modifier.fillMaxSize().background(Color.White).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("相机 + 人脸", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                // 预览独立占一块固定区域(TextureView 才会被 clip 在这块里)。
                // 画面顺时针转 90°:视图先按转后尺寸对调宽高,再整体 rotationZ=90
                BoxWithConstraints(
                    Modifier.fillMaxWidth().height(240.dp).clipToBounds().background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).also {
                                it.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                it.scaleType = PreviewView.ScaleType.FIT_CENTER
                                previewView = it
                            }
                        },
                        modifier = Modifier
                            .requiredSize(width = maxHeight, height = maxWidth)
                            .graphicsLayer { rotationZ = 90f },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        withPermission(Manifest.permission.CAMERA) {
                            val view = previewView ?: return@withPermission
                            scope.launch { message = startCapture(view) }
                        }
                    }) { Text("开始抓帧+人脸") }
                    Button(onClick = { captureManager.stop() }) { Text("停止") }
                }
                Text("USB 相机 ${usbDevices.size} · " +
                    if (stats.isRunning) "${stats.cameraLabel} ${stats.width}x${stats.height} ${"%.1f".format(stats.fps)}fps" else "相机未运行")
                Text("推理:$inferStatus · 人脸 ${faces.size}")
                faces.forEach {
                    Text("#${it.id} (${it.left},${it.top})-(${it.right},${it.bottom}) ${"%.2f".format(it.score)} ${it.overlayLabel}")
                }

                Text("语音听写", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        withPermission(Manifest.permission.RECORD_AUDIO) {
                            // start() 会建立网络连接,放到 IO 线程
                            scope.launch(Dispatchers.IO) { speechBridge.start() }
                        }
                    }) { Text("开始听写") }
                    Button(onClick = { speechBridge.stop() }) { Text("停止") }
                    Button(onClick = { speechBridge.clear() }) { Text("清空") }
                    Button(onClick = { speechBridge.testNetwork() }) { Text("测网络") }
                }
                Text("状态:${speech.status}${speech.error?.let { " · $it" } ?: ""}")
                Text(speech.committedText.ifEmpty { "(无定稿)" })
                if (speech.partialText.isNotEmpty()) Text("… ${speech.partialText}")

                message?.let { Text(it) }
            }
        }
    }

    /** 推理初始化放 IO 线程;返回错误文案,成功为 null。推理失败不影响相机预览。 */
    private suspend fun startCapture(view: PreviewView): String? {
        return try {
            withContext(Dispatchers.IO) { inferenceBridge.start() }
            captureManager.refreshCameraList()
            val cams = captureManager.cameras.value
            val id = cams.firstOrNull { it.isExternal }?.cameraId ?: cams.firstOrNull()?.cameraId
            captureManager.start(this, view, id)
            null
        } catch (t: Throwable) {
            Log.e("MainActivity", "start capture failed", t)
            "打开相机失败: ${t.message}"
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        captureManager.onFrame = null
        captureManager.stop()
        speechBridge.release()
        inferenceBridge.release()
        captureManager.release()
        usbMonitor.stop()
        super.onDestroy()
    }
}
