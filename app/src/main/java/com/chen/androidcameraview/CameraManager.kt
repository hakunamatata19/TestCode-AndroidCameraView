package com.chen.androidcameraview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.OrientationEventListener
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 相机管理模块
 *
 * 支持前后摄像头切换、预览、拍照（ImageCapture）和视频录制（VideoCapture）。
 * 视频录制使用 CameraX VideoCapture + OverlayEffect 将时间戳水印写入视频帧。
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    companion object {
        private const val TAG = "CameraManager"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewView: PreviewView? = null
    private var isFrontCamera = false
    private var overlayEffect: OverlayEffect? = null

    /** 设备物理方向（通过传感器检测），0/90/180/270 */
    @Volatile
    var deviceOrientation: Int = 0
        private set

    /** 方向变化回调 */
    var onOrientationChanged: ((Int) -> Unit)? = null

    private val orientationListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            // 将连续角度量化为 0/90/180/270
            val newOrientation = when {
                orientation in 45..134 -> 270   // 手机右转横屏
                orientation in 135..224 -> 180  // 倒置
                orientation in 225..314 -> 90   // 手机左转横屏
                else -> 0                        // 竖屏
            }
            if (newOrientation != deviceOrientation) {
                deviceOrientation = newOrientation
                onOrientationChanged?.invoke(newOrientation)
            }
        }
    }

    /** 外部可获取 ImageCapture 用于拍照 */
    var imageCapture: ImageCapture? = null
        private set

    /** 外部可获取 VideoCapture 用于录像 */
    var videoCapture: VideoCapture<Recorder>? = null
        private set

    /** 水印归一化坐标 (0~1)，由外部设置 */
    var watermarkNormalizedX: Float = 0.05f
    var watermarkNormalizedY: Float = 0.05f

    fun startCamera(
        previewView: PreviewView,
        onReady: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        this.previewView = previewView
        orientationListener.enable()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cameraProvider = future.get()
                bindCamera(onReady, onError)
            } catch (e: Exception) {
                Log.e(TAG, "相机启动失败", e)
                onError(e.message ?: "相机启动失败")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** 切换前后摄像头 */
    fun flipCamera(
        onReady: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        isFrontCamera = !isFrontCamera
        bindCamera(onReady, onError)
    }

    fun isUsingFrontCamera(): Boolean = isFrontCamera

    private fun bindCamera(
        onReady: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val provider = cameraProvider ?: return
        val view = previewView ?: return

        try {
            provider.unbindAll()
            // 清理旧的 overlay
            overlayEffect?.close()

            val cameraSelector = if (isFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = view.surfaceProvider
            }

            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            // 创建 VideoCapture（使用 Recorder）
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(Quality.HD)
                )
                .build()
            val video = VideoCapture.withOutput(recorder)

            // 创建 OverlayEffect，仅作用于 VideoCapture（预览层由 TimestampWatermarkView 负责显示）
            val effect = OverlayEffect(
                CameraEffect.VIDEO_CAPTURE,
                0,
                Handler(Looper.getMainLooper())
            ) {
                // onDrawListener 不需要做什么
            }

            // 设置每帧绘制回调
            effect.setOnDrawListener { frame ->
                val canvas: Canvas = frame.overlayCanvas
                // 清除上一帧残留内容
                canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)

                val cw = canvas.width
                val ch = canvas.height

                // 根据实验结果：
                // - 竖屏时 CameraX 对 overlay 做顺时针90°旋转 → 预旋转270°补偿
                // - 横屏(逆时针)时 CameraX 不旋转 overlay → 不需要预旋转
                canvas.save()
                when (deviceOrientation) {
                    0 -> {
                        // 竖屏：预旋转270°补偿 CameraX 的90°旋转
                        canvas.rotate(270f, 0f, 0f)
                        canvas.translate(-ch.toFloat(), 0f)
                        drawTimestamp(canvas, ch, cw)
                    }
                    90 -> {
                        // 左横屏（逆时针）：CameraX 不旋转，canvas直接绘制
                        drawTimestamp(canvas, cw, ch)
                    }
                    270 -> {
                        // 右横屏（顺时针）：CameraX旋转180° → 预旋转180°补偿
                        canvas.rotate(180f, cw / 2f, ch / 2f)
                        drawTimestamp(canvas, cw, ch)
                    }
                    180 -> {
                        // 倒置
                        canvas.rotate(90f, 0f, 0f)
                        canvas.translate(0f, -cw.toFloat())
                        drawTimestamp(canvas, ch, cw)
                    }
                    else -> {
                        canvas.rotate(270f, 0f, 0f)
                        canvas.translate(-ch.toFloat(), 0f)
                        drawTimestamp(canvas, ch, cw)
                    }
                }
                canvas.restore()
                true
            }

            overlayEffect = effect

            // 绑定 use cases 和 effect
            val useCaseGroup = androidx.camera.core.UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(capture)
                .addUseCase(video)
                .addEffect(effect)
                .build()

            provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                useCaseGroup
            )

            imageCapture = capture
            videoCapture = video
            Log.d(TAG, "相机绑定成功，VideoCapture + OverlayEffect 已就绪")
            onReady()
        } catch (e: Exception) {
            Log.e(TAG, "相机绑定失败", e)
            onError(e.message ?: "相机绑定失败")
        }
    }

    // =========================================================================
    // 时间戳绘制
    // =========================================================================
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isFakeBoldText = true
        typeface = Typeface.MONOSPACE
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 0, 0, 0)
        style = Paint.Style.FILL
    }

    /**
     * 在 canvas 上绘制时间戳
     * @param width  逻辑宽度（竖屏方向的宽）
     * @param height 逻辑高度（竖屏方向的高）
     */
    private fun drawTimestamp(canvas: Canvas, width: Int, height: Int) {
        val timestamp = timestampFormat.format(Date())

        // 根据帧短边动态计算文字大小，保证横竖屏水印大小一致
        val shortSide = minOf(width, height)
        val fontSize = shortSide * 0.04f
        textPaint.textSize = fontSize
        textPaint.setShadowLayer(fontSize * 0.06f, 2f, 2f, Color.BLACK)

        val bounds = Rect()
        textPaint.getTextBounds(timestamp, 0, timestamp.length, bounds)

        val padding = fontSize * 0.4f
        val cornerRadius = fontSize * 0.2f

        // 将屏幕归一化坐标变换为当前 canvas 逻辑坐标
        // watermarkNormalizedX/Y 是竖屏容器中的归一化位置
        val logicNormX: Float
        val logicNormY: Float
        when (deviceOrientation) {
            90 -> {
                // 左横屏（逆时针）：canvas 横向不旋转
                // 容器Y→视频X，容器X反向→视频Y
                logicNormX = watermarkNormalizedY
                logicNormY = 1f - watermarkNormalizedX
            }
            270 -> {
                // 右横屏（顺时针）：canvas 旋转180°后坐标系
                // 容器Y反向→视频X，容器X→视频Y
                logicNormX = 1f - watermarkNormalizedY
                logicNormY = watermarkNormalizedX
            }
            180 -> {
                // 倒置
                logicNormX = 1f - watermarkNormalizedX
                logicNormY = 1f - watermarkNormalizedY
            }
            else -> {
                // 竖屏：canvas 旋转270°后坐标系与屏幕对齐
                logicNormX = watermarkNormalizedX
                logicNormY = watermarkNormalizedY
            }
        }

        // 归一化坐标的语义：水印整体（背景圆角矩形）左上角相对画面的位置
        // 与预览端 TimestampWatermarkView 保持一致
        val totalW = bounds.width() + padding * 2
        val totalH = bounds.height() + padding * 2

        // 水印左上角期望位置
        val rectLeft = (logicNormX * width).coerceIn(0f, (width - totalW).coerceAtLeast(0f))
        val rectTop = (logicNormY * height).coerceIn(0f, (height - totalH).coerceAtLeast(0f))

        // 背景
        val bgRect = RectF(
            rectLeft,
            rectTop,
            rectLeft + totalW,
            rectTop + totalH
        )
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint)

        // 文字基线 = top + padding + textHeight - bounds.bottom 调整以居中
        val textX = rectLeft + padding - bounds.left
        val textY = rectTop + padding - bounds.top
        canvas.drawText(timestamp, textX, textY, textPaint)
    }

    fun release() {
        orientationListener.disable()
        overlayEffect?.close()
        overlayEffect = null
    }

    /**
     * 获取 overlay canvas 需要旋转的角度
     * 基于传感器检测到的设备物理方向（不依赖 Activity 方向）
     */
    private fun getDeviceRotationDegrees(): Int {
        // deviceOrientation: 0=竖屏, 90=左横屏, 270=右横屏
        // 对于后置摄像头传感器方向通常为90°，所以：
        // 竖屏(0°) -> canvas需旋转270°补偿
        // 左横屏(90°) -> canvas需旋转0°（自然方向）
        // 右横屏(270°) -> canvas需旋转180°
        return when (deviceOrientation) {
            0 -> 270
            90 -> 0
            180 -> 90
            270 -> 180
            else -> 270
        }
    }
}
