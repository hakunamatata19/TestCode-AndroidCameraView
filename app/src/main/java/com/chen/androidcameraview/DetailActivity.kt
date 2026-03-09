package com.chen.androidcameraview

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.chen.androidcameraview.databinding.ActivityDetailBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetailBinding

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var isRecording = false

    // 时间戳水印管理器
    private lateinit var timestampOverlayManager: TimestampOverlayManager

    // 录制计时
    private val handler = Handler(Looper.getMainLooper())
    private var recordingSeconds = 0
    private val timerRunnable = object : Runnable {
        override fun run() {
            recordingSeconds++
            val minutes = recordingSeconds / 60
            val seconds = recordingSeconds % 60
            binding.tvRecordingTime.text = String.format("%02d:%02d", minutes, seconds)
            handler.postDelayed(this, 1000)
        }
    }

    // 时间戳格式化器 (精确到毫秒)
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss:SSS", Locale.getDefault())

    // 时间戳更新 Runnable (每10毫秒更新一次)
    private val timestampRunnable = object : Runnable {
        override fun run() {
            binding.tvTimestamp.text = timestampFormat.format(Date())
            handler.postDelayed(this, 10) // 每10毫秒更新一次
        }
    }

    // 拖动相关变量
    private var dX = 0f
    private var dY = 0f

    // 权限请求
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "需要相机和录音权限才能录制视频", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // 初始化时间戳水印管理器
        timestampOverlayManager = TimestampOverlayManager()

        // 检查权限并启动相机
        checkPermissionsAndStartCamera()

        // 设置时间戳拖动功能
        setupTimestampDrag()

        // 开始更新时间戳
        startTimestampUpdate()

        // 录制按钮点击事件
        binding.btnRecord.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                @SuppressLint("MissingPermission")
                startRecording()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTimestampDrag() {
        binding.tvTimestamp.setOnTouchListener { view, event ->
            val parent = view.parent as View

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                    // 隐藏拖动提示
                    binding.tvDragHint.visibility = View.GONE
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // 计算新位置
                    var newX = event.rawX + dX
                    var newY = event.rawY + dY

                    // 限制在父容器范围内
                    val maxX = parent.width - view.width
                    val maxY = parent.height - view.height

                    newX = newX.coerceIn(0f, maxX.toFloat())
                    newY = newY.coerceIn(0f, maxY.toFloat())

                    view.x = newX
                    view.y = newY

                    // 同步更新 OverlayManager 中的位置（转换为归一化坐标）
                    val normalizedX = newX / parent.width
                    val normalizedY = newY / parent.height
                    timestampOverlayManager.updatePosition(normalizedX, normalizedY)

                    true
                }
                else -> false
            }
        }
    }

    private fun startTimestampUpdate() {
        handler.post(timestampRunnable)
    }

    private fun stopTimestampUpdate() {
        handler.removeCallbacks(timestampRunnable)
    }

    private fun checkPermissionsAndStartCamera() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            startCamera()
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // 预览
            val preview = Preview.Builder()
                .build()
                .also {
                    it.surfaceProvider = binding.previewView.surfaceProvider
                }

            // 视频录制
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            // 创建时间戳 Overlay 效果
            val overlayEffect = timestampOverlayManager.createOverlayEffect()

            // 选择后置摄像头
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // 解绑所有用例
                cameraProvider.unbindAll()

                // 创建 UseCaseGroup 并添加 Effect
                val useCaseGroup = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(videoCapture!!)
                    .addEffect(overlayEffect)
                    .build()

                // 绑定用例组到相机
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    useCaseGroup
                )

            } catch (e: Exception) {
                Toast.makeText(this, "相机启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startRecording() {
        val videoCapture = this.videoCapture ?: return

        // 创建文件名
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "VID_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraView")
        }

        // 配置输出选项
        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        // 开始录制
        recording = videoCapture.output
            .prepareRecording(this, mediaStoreOutputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        isRecording = true
                        binding.btnRecord.text = "停止录制"
                        binding.tvRecordingTime.visibility = View.VISIBLE
                        recordingSeconds = 0
                        handler.post(timerRunnable)
                    }
                    is VideoRecordEvent.Finalize -> {
                        isRecording = false
                        binding.btnRecord.text = "开始录制"
                        binding.tvRecordingTime.visibility = View.GONE
                        handler.removeCallbacks(timerRunnable)

                        if (!recordEvent.hasError()) {
                            Toast.makeText(
                                this,
                                "视频已保存: ${recordEvent.outputResults.outputUri}",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Toast.makeText(
                                this,
                                "录制失败: ${recordEvent.error}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
    }

    private fun stopRecording() {
        recording?.stop()
        recording = null
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(timerRunnable)
        stopTimestampUpdate()
        // 释放时间戳水印管理器资源
        timestampOverlayManager.release()
    }

    companion object {
        const val EXTRA_TITLE = "extra_title"
    }
}

