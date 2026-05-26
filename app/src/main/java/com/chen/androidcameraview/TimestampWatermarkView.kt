package com.chen.androidcameraview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 可拖动的时间戳水印 View（仅用于预览界面）
 *
 * 职责：
 *  - 每 10ms 刷新时间显示
 *  - 响应手指拖动，更新自身位置
 *  - 拖动过程中回调新的归一化坐标（供外部记录位置，录屏方案中仅用于隐藏提示）
 */
@SuppressLint("ClickableViewAccessibility")
class TimestampWatermarkView constructor(
    context: Context
) : View(context) {

    // ---- 回调 ----------------------------------------------------------------
    /** 拖动过程中持续回调归一化坐标 (x, y) */
    var onPositionChanged: ((normalizedX: Float, normalizedY: Float) -> Unit)? = null

    // ---- 绘制工具 ------------------------------------------------------------
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private var currentTimestamp = timestampFormat.format(Date())

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f // 会在 onSizeChanged 中根据父容器短边动态更新
        isFakeBoldText = true
        typeface = Typeface.MONOSPACE
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 0, 0, 0)
        style = Paint.Style.FILL
    }

    // 拖动状态下的边框
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F44336")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private var isDragging = false

    private val textBounds = Rect()
    private val bgRect = RectF()
    // 与 CameraManager.drawTimestamp 保持一致的视觉参数
    private var padding = 42f * 0.4f
    private var cornerRadius = 42f * 0.2f

    // ---- 定时刷新 ------------------------------------------------------------
    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            currentTimestamp = timestampFormat.format(Date())
            invalidate()
            handler.postDelayed(this, 500L)
        }
    }

    // ---- 拖动状态 ------------------------------------------------------------
    private var downRawX = 0f
    private var downRawY = 0f
    private var downTransX = 0f
    private var downTransY = 0f

    // ---- 生命周期 ------------------------------------------------------------
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(updateRunnable)
        // 等父容器布局完成后，根据短边调整字体大小（与视频合成中 shortSide * 0.04f 保持一致）
        post {
            val parentView = parent as? View ?: return@post
            val shortSide = minOf(parentView.width, parentView.height)
            if (shortSide > 0) {
                val newSize = shortSide * 0.04f
                textPaint.textSize = newSize
                textPaint.setShadowLayer(newSize * 0.06f, 2f, 2f, Color.BLACK)
                padding = newSize * 0.4f
                cornerRadius = newSize * 0.2f
                requestLayout()
                invalidate()
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(updateRunnable)
    }

    /** 当父容器大小发生变化（如横竖屏切换），重新计算字体大小 */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val parentView = parent as? View ?: return
        val shortSide = minOf(parentView.width, parentView.height)
        if (shortSide > 0) {
            val newSize = shortSide * 0.04f
            textPaint.textSize = newSize
            textPaint.setShadowLayer(newSize * 0.06f, 2f, 2f, Color.BLACK)
            padding = newSize * 0.4f
            cornerRadius = newSize * 0.2f
            requestLayout()
        }
    }

    // ---- 绘制 ----------------------------------------------------------------
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        textPaint.getTextBounds(currentTimestamp, 0, currentTimestamp.length, textBounds)

        val totalW = textBounds.width() + padding * 2
        val totalH = textBounds.height() + padding * 2

        bgRect.set(0f, 0f, totalW, totalH)
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint)

        // 拖动时绘制红色边框提示
        if (isDragging) {
            canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, borderPaint)
        }

        // 文字按 padding 偏移绘制，与 CameraManager.drawTimestamp 保持一致
        val textX = padding - textBounds.left
        val textY = padding - textBounds.top
        canvas.drawText(currentTimestamp, textX, textY, textPaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 固定测量一次后不再因文字变化触发 requestLayout
        textPaint.getTextBounds("2026-03-06 22:04:03", 0, 19, textBounds)
        val w = (textBounds.width() + padding * 2).toInt()
        val h = (textBounds.height() + padding * 2).toInt()
        setMeasuredDimension(w, h)
    }

    // ---- 触摸拖动 ------------------------------------------------------------
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val parentView = parent as? View ?: return false

        // 旋转后视觉宽高可能互换（90/270度时宽高对调）
        val visualWidth: Int
        val visualHeight: Int
        val rot = (rotation % 360 + 360) % 360
        if (rot == 90f || rot == 270f) {
            visualWidth = height
            visualHeight = width
        } else {
            visualWidth = width
            visualHeight = height
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downTransX = translationX
                downTransY = translationY
                isDragging = true
                animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).start()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY

                // 旋转后 pivot 在中心，视觉边界需要基于视觉尺寸计算
                val offsetX = (visualWidth - width) / 2f
                val offsetY = (visualHeight - height) / 2f

                val newTransX = (downTransX + dx).coerceIn(
                    -left.toFloat() + offsetX,
                    (parentView.width - left - width).toFloat() - offsetX
                )
                val newTransY = (downTransY + dy).coerceIn(
                    -top.toFloat() + offsetY,
                    (parentView.height - top - height).toFloat() - offsetY
                )

                translationX = newTransX
                translationY = newTransY

                // 计算归一化坐标并回调
                val absX = left + newTransX
                val absY = top + newTransY
                onPositionChanged?.invoke(
                    absX / parentView.width,
                    absY / parentView.height
                )
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                invalidate()
                return true
            }
        }
        return false
    }
}
