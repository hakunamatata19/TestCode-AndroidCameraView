package com.chen.androidcameraview

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.chen.androidcameraview.databinding.ActivityPhotoCaptureBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 拍照界面
 *
 * 拍照后将时间戳水印烧录到照片中保存到相册。
 */
class PhotoCaptureActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PhotoCaptureActivity"
    }

    private lateinit var binding: ActivityPhotoCaptureBinding
    private lateinit var cameraManager: CameraManager
    private lateinit var watermarkView: TimestampWatermarkView

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) startCamera()
        else Toast.makeText(this, "需要相机权限", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityPhotoCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }

        cameraManager = CameraManager(this, this)

        addWatermarkView()
        checkCameraPermAndStart()
        setupCaptureButton()
        setupFlipButton()
    }

    private fun checkCameraPermAndStart() {
        val required = arrayOf(Manifest.permission.CAMERA)
        if (required.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) {
            startCamera()
        } else {
            cameraPermLauncher.launch(required)
        }
    }

    private fun startCamera() {
        cameraManager.startCamera(
            previewView = binding.previewView,
            onError = { msg ->
                Toast.makeText(this, "相机启动失败: $msg", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun addWatermarkView() {
        watermarkView = TimestampWatermarkView(this)
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = 48; topMargin = 48 }
        binding.previewContainer.addView(watermarkView, lp)
    }

    private fun setupFlipButton() {
        binding.btnFlipCamera.setOnClickListener {
            cameraManager.flipCamera(
                onError = { msg ->
                    Toast.makeText(this, "切换摄像头失败: $msg", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    private fun setupCaptureButton() {
        binding.btnCapture.setOnClickListener {
            takePhotoWithWatermark()
        }
    }

    private fun takePhotoWithWatermark() {
        val imageCapture = cameraManager.imageCapture ?: run {
            Toast.makeText(this, "相机未就绪", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnCapture.isEnabled = false
        binding.btnCapture.text = "处理中..."

        // 先拍照到临时文件
        val tmpFile = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(tmpFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    // 在后台线程处理水印
                    Thread {
                        val uri = burnWatermarkAndSave(tmpFile)
                        tmpFile.delete()
                        runOnUiThread {
                            binding.btnCapture.isEnabled = true
                            binding.btnCapture.text = "拍照"
                            if (uri != null) {
                                Toast.makeText(this@PhotoCaptureActivity, "照片已保存", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@PhotoCaptureActivity, "保存失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }.start()
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "拍照失败", exception)
                    binding.btnCapture.isEnabled = true
                    binding.btnCapture.text = "拍照"
                    Toast.makeText(this@PhotoCaptureActivity, "拍照失败: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    /**
     * 将时间戳水印烧录到照片并保存到 MediaStore
     */
    private fun burnWatermarkAndSave(photoFile: File): android.net.Uri? {
        return try {
            val originalBitmap = BitmapFactory.decodeFile(photoFile.absolutePath) ?: return null
            val mutableBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
            originalBitmap.recycle()

            val canvas = Canvas(mutableBitmap)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

            // 根据图片尺寸计算水印大小
            val shortSide = minOf(mutableBitmap.width, mutableBitmap.height)
            val fontSize = shortSide / 25f
            val padding = fontSize * 0.4f

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = fontSize
                isFakeBoldText = true
                typeface = Typeface.MONOSPACE
                setShadowLayer(3f, 1f, 1f, Color.BLACK)
            }

            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(160, 0, 0, 0)
                style = Paint.Style.FILL
            }

            val textBounds = Rect()
            textPaint.getTextBounds(timestamp, 0, timestamp.length, textBounds)

            val textX = padding * 2
            val textY = padding * 2 + textBounds.height()

            val bgRect = RectF(
                padding, padding,
                textX + textBounds.width() + padding,
                textY + padding
            )
            canvas.drawRoundRect(bgRect, 8f, 8f, bgPaint)
            canvas.drawText(timestamp, textX, textY, textPaint)

            // 保存到 MediaStore
            val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(System.currentTimeMillis())
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_$name.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraView")
                }
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            contentResolver.openOutputStream(uri)?.use { out ->
                mutableBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            mutableBitmap.recycle()
            uri
        } catch (e: Exception) {
            Log.e(TAG, "水印处理失败", e)
            null
        }
    }
}
