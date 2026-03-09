package com.chen.androidcameraview

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraEffect
import androidx.camera.effects.Frame
import androidx.camera.effects.OverlayEffect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 时间戳水印管理器
 * 使用 CameraX OverlayEffect 在视频中嵌入时间戳
 */
class TimestampOverlayManager {

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss:SSS", Locale.getDefault())

    // 文字绘制画笔
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 48f
        isAntiAlias = true
        isFakeBoldText = true
        typeface = android.graphics.Typeface.MONOSPACE
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    // 背景绘制画笔
    private val bgPaint = Paint().apply {
        color = Color.argb(128, 0, 0, 0)
        style = Paint.Style.FILL
    }

    // 归一化坐标 (0-1)
    @Volatile
    var normalizedX: Float = 0.03f
    @Volatile
    var normalizedY: Float = 0.05f

    private val handler = Handler(Looper.getMainLooper())

    private var overlayEffect: OverlayEffect? = null

    /**
     * 创建 OverlayEffect 用于 CameraX
     */
    fun createOverlayEffect(): OverlayEffect {
        // 创建 OverlayEffect，应用于预览和视频录制
        // 构造函数: (targets: Int, queueDepth: Int, handler: Handler, errorListener: Consumer<Throwable!>)
        val effect = OverlayEffect(
            CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE,
            1,  // queueDepth - 队列深度
            handler,
            { throwable: Throwable -> throwable.printStackTrace() }
        )

        // 设置绘制监听器，每帧都会调用
        effect.setOnDrawListener { frame: Frame ->
            val canvas = frame.overlayCanvas
            val size = frame.size

            // 根据分辨率调整文字大小
            textPaint.textSize = (size.height * 0.025f).coerceIn(32f, 64f)

            // 绘制时间戳
            drawTimestamp(canvas, size.width, size.height)

            // 返回 true 表示绘制了内容
            true
        }

        overlayEffect = effect
        return effect
    }

    private fun drawTimestamp(canvas: Canvas, width: Int, height: Int) {
        val timestamp = timestampFormat.format(Date())

        // 计算文字边界
        val textBounds = Rect()
        textPaint.getTextBounds(timestamp, 0, timestamp.length, textBounds)

        // 计算实际位置（从归一化坐标转换）
        val padding = 16f
        val maxX = width - textBounds.width() - padding * 2
        val maxY = height - padding

        val x = (normalizedX * width).coerceIn(padding, maxX.coerceAtLeast(padding))
        val y = (normalizedY * height + textBounds.height()).coerceIn(
            textBounds.height() + padding,
            maxY
        )

        // 绘制背景矩形
        val bgRect = RectF(
            x - padding,
            y - textBounds.height() - padding,
            x + textBounds.width() + padding,
            y + padding
        )
        canvas.drawRoundRect(bgRect, 8f, 8f, bgPaint)

        // 绘制时间戳文字
        canvas.drawText(timestamp, x, y, textPaint)
    }

    /**
     * 更新时间戳位置
     * @param x 归一化 X 坐标 (0-1)
     * @param y 归一化 Y 坐标 (0-1)
     */
    fun updatePosition(x: Float, y: Float) {
        normalizedX = x.coerceIn(0f, 1f)
        normalizedY = y.coerceIn(0f, 1f)
    }

    /**
     * 释放资源
     */
    fun release() {
        overlayEffect?.close()
    }
}

