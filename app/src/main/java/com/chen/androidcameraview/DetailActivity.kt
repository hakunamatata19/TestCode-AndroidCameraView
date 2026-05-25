package com.chen.androidcameraview

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.chen.androidcameraview.databinding.ActivityDetailBinding

/**
 * 录制界面
 *
 * 架构：
 *  使用 CameraX VideoCapture 直接从相机录制视频，无需 MediaProjection。
 *  时间戳水印显示在预览层（未来可通过 OverlayEffect 写入视频帧）。
 */
class DetailActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DetailActivity"
        const val EXTRA_TITLE = "extra_title"
    }

    private lateinit var binding: ActivityDetailBinding

    // ---- 模块 ---------------------------------------------------------------
    private lateinit var cameraManager: CameraManager
    private lateinit var watermarkView: TimestampWatermarkView

    // ---- 录制 ----------------------------------------------------------------
    private var activeRecording: Recording? = null
    private val isRecording: Boolean get() = activeRecording != null

    // ---- 录制计时 ------------------------------------------------------------
    private val handler = Handler(Looper.getMainLooper())
    private var recordingSeconds = 0
    private val timerRunnable = object : Runnable {
        override fun run() {
            recordingSeconds++
            binding.tvRecordingTime.text =
                String.format("%02d:%02d", recordingSeconds / 60, recordingSeconds % 60)
            handler.postDelayed(this, 1000)
        }
    }

    // ---- 权限 Launcher -------------------------------------------------------
    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) {
            Log.d(TAG, "权限已全部授予")
            startCamera()
        } else {
            Log.w(TAG, "权限被拒绝: $perms")
            Toast.makeText(this, "需要相机和录音权限", Toast.LENGTH_SHORT).show()
        }
    }

    // =========================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }

        cameraManager = CameraManager(this, this)

        addWatermarkView()
        checkCameraPermAndStart()
        setupRecordButton()
        setupFlipButton()

        Log.d(TAG, "onCreate 完成")
    }

    // =========================================================================
    // 相机
    // =========================================================================
    private fun checkCameraPermAndStart() {
        val required = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (required.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) {
            Log.d(TAG, "权限已具备，启动相机")
            startCamera()
        } else {
            Log.d(TAG, "请求相机和录音权限")
            cameraPermLauncher.launch(required)
        }
    }

    private fun startCamera() {
        cameraManager.startCamera(
            previewView = binding.previewView,
            onReady = { Log.d(TAG, "相机启动成功，VideoCapture 已就绪") },
            onError = { msg ->
                Log.e(TAG, "相机启动失败: $msg")
                Toast.makeText(this, "相机启动失败: $msg", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // =========================================================================
    // 翻转摄像头
    // =========================================================================
    private fun setupFlipButton() {
        binding.btnFlipCamera.setOnClickListener {
            if (isRecording) {
                Toast.makeText(this, "录制中无法切换摄像头", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            cameraManager.flipCamera(
                onError = { msg ->
                    Toast.makeText(this, "切换摄像头失败: $msg", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    // =========================================================================
    // 水印 View
    // =========================================================================
    private fun addWatermarkView() {
        watermarkView = TimestampWatermarkView(this).apply {
            onPositionChanged = { normalizedX, normalizedY ->
                binding.tvDragHint.visibility = View.GONE
                // 直接存储屏幕坐标系下的归一化位置
                // 坐标变换在 CameraManager 的 drawTimestamp 中根据方向处理
                cameraManager.watermarkNormalizedX = normalizedX
                cameraManager.watermarkNormalizedY = normalizedY
                Log.d(TAG, "水印位置更新: ($normalizedX, $normalizedY)")
            }
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = 48; topMargin = 48 }
        binding.previewContainer.addView(watermarkView, lp)

        // 监听设备方向变化，旋转预览水印使其与视频输出方向一致
        cameraManager.onOrientationChanged = { orientation ->
            runOnUiThread {
                // orientation: 0=竖屏, 90=左横屏, 270=右横屏
                val targetRotation = orientation.toFloat()
                watermarkView.animate()
                    .rotation(targetRotation)
                    .setDuration(200)
                    .withEndAction {
                        // 旋转完成后，修正位置确保不超出父容器
                        clampWatermarkPosition()
                    }
                    .start()
            }
        }
    }

    /** 修正水印位置，确保旋转后不超出父容器 */
    private fun clampWatermarkPosition() {
        val parent = watermarkView.parent as? View ?: return
        val rot = (watermarkView.rotation % 360 + 360) % 360
        val visualWidth: Int
        val visualHeight: Int
        if (rot == 90f || rot == 270f) {
            visualWidth = watermarkView.height
            visualHeight = watermarkView.width
        } else {
            visualWidth = watermarkView.width
            visualHeight = watermarkView.height
        }
        val offsetX = (visualWidth - watermarkView.width) / 2f
        val offsetY = (visualHeight - watermarkView.height) / 2f

        watermarkView.translationX = watermarkView.translationX.coerceIn(
            -watermarkView.left.toFloat() + offsetX,
            (parent.width - watermarkView.left - watermarkView.width).toFloat() - offsetX
        )
        watermarkView.translationY = watermarkView.translationY.coerceIn(
            -watermarkView.top.toFloat() + offsetY,
            (parent.height - watermarkView.top - watermarkView.height).toFloat() - offsetY
        )
    }

    // =========================================================================
    // 录制按钮（使用 CameraX VideoCapture，无需 MediaProjection）
    // =========================================================================
    private fun setupRecordButton() {
        binding.btnRecord.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }
    }

    @androidx.annotation.OptIn(androidx.camera.video.ExperimentalPersistentRecording::class)
    private fun startRecording() {
        val videoCapture = cameraManager.videoCapture
        if (videoCapture == null) {
            Log.e(TAG, "VideoCapture 未就绪，无法录制")
            Toast.makeText(this, "相机未就绪，请稍后重试", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d(TAG, "准备开始录制...")

        // 配置输出到 MediaStore
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "VID_${System.currentTimeMillis()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraView")
            }
        }

        val outputOptions = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        // 开始录制
        val recording = videoCapture.output
            .prepareRecording(this, outputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { event ->
                handleRecordEvent(event)
            }

        activeRecording = recording
        Log.d(TAG, "录制已启动")

        // 更新 UI
        binding.btnRecord.text = "停止录制"
        binding.tvRecordingTime.visibility = View.VISIBLE
        binding.btnFlipCamera.visibility = View.GONE
        recordingSeconds = 0
        handler.post(timerRunnable)
    }

    private fun stopRecording() {
        Log.d(TAG, "停止录制...")
        activeRecording?.stop()
        binding.btnRecord.isEnabled = false
        binding.btnRecord.text = "保存中..."
    }

    private fun handleRecordEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> {
                Log.d(TAG, "VideoRecordEvent.Start - 录制正式开始")
            }
            is VideoRecordEvent.Status -> {
                val stats = event.recordingStats
                Log.v(TAG, "录制中: ${stats.numBytesRecorded} bytes, ${stats.recordedDurationNanos / 1_000_000}ms")
            }
            is VideoRecordEvent.Finalize -> {
                activeRecording = null
                handler.removeCallbacks(timerRunnable)

                if (event.hasError()) {
                    Log.e(TAG, "录制失败: error=${event.error}, cause=${event.cause?.message}", event.cause)
                    runOnUiThread {
                        binding.tvRecordingTime.visibility = View.GONE
                        binding.btnFlipCamera.visibility = View.VISIBLE
                        resetRecordButton()
                        Toast.makeText(this, "录制失败: ${event.cause?.message}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val uri = event.outputResults.outputUri
                    Log.d(TAG, "录制完成，已保存: $uri")
                    runOnUiThread {
                        binding.tvRecordingTime.visibility = View.GONE
                        binding.btnFlipCamera.visibility = View.VISIBLE
                        resetRecordButton()
                        Toast.makeText(this, "视频已保存", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun resetRecordButton() {
        binding.btnRecord.isEnabled = true
        binding.btnRecord.text = "开始录制"
    }

    // =========================================================================
    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        activeRecording?.stop()
        cameraManager.release()
        Log.d(TAG, "onDestroy")
    }
}
