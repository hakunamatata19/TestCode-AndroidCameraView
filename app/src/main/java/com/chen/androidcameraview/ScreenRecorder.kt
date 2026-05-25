package com.chen.androidcameraview

import android.content.ContentValues
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 录屏模块
 *
 * 原理：
 *   MediaProjection → VirtualDisplay（将屏幕指定区域渲染到 encoder 的 InputSurface）
 *   MediaCodec（Surface 输入模式）→ 编码为 H.264
 *   MediaMuxer → 封装为 mp4
 *
 * 优势：
 *   - 相机预览画面 + 时间戳 View 已经在屏幕上合成好了，直接录制即可
 *   - 无需任何帧级别的手动合成，彻底解决水印残影问题
 *   - 代码简单，没有 YUV 转换、OverlayEffect 等复杂逻辑
 */
class ScreenRecorder(private val context: Context) {

    companion object {
        private const val TAG = "ScreenRecorder"
        private const val MIME_TYPE = "video/avc"
        private const val BIT_RATE = 8_000_000
        private const val FRAME_RATE = 30
        private const val I_FRAME_INTERVAL = 1
    }

    // 状态
    val isRecording: Boolean get() = _isRecording
    private var _isRecording = false

    // 组件
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var muxerTrackIndex = -1
    private var muxerStarted = false

    // 输出临时文件
    private var tmpFile: File? = null

    // 编码线程
    private var encoderThread: Thread? = null

    /**
     * 开始录制
     *
     * @param projection  MediaProjection（由 Activity 从授权结果中获取）
     * @param width       录制区域宽度（px）
     * @param height      录制区域高度（px）
     * @param dpi         屏幕 DPI
     * @param onStop      录制停止后回调，参数为保存的视频 Uri
     */
    fun start(
        projection: MediaProjection,
        width: Int,
        height: Int,
        dpi: Int,
        onStop: (Uri?) -> Unit
    ) {
        if (_isRecording) return
        _isRecording = true
        mediaProjection = projection

        // ---- 1. 准备临时文件 & Muxer ----------------------------------------
        tmpFile = File(context.cacheDir, "screen_${System.currentTimeMillis()}.mp4")
        mediaMuxer = MediaMuxer(tmpFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // ---- 2. 配置编码器（Surface 输入模式）---------------------------------
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        }
        val codec = MediaCodec.createEncoderByType(MIME_TYPE)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        // 创建 InputSurface，VirtualDisplay 会把屏幕内容渲染到这里
        val inputSurface = codec.createInputSurface()
        codec.start()
        mediaCodec = codec

        // ---- 3. 创建 VirtualDisplay，将屏幕渲染到 InputSurface ---------------
        virtualDisplay = projection.createVirtualDisplay(
            "ScreenRecorder",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null, null
        )

        // ---- 4. 启动编码线程，持续从编码器取数据写入 Muxer --------------------
        encoderThread = Thread({
            drainEncoder(onStop)
        }, "EncoderThread").also { it.start() }

        Log.d(TAG, "录制开始 ${width}x${height}")
    }

    /**
     * 停止录制
     * 向编码器发送 EOS，等待编码线程自然结束
     */
    fun stop() {
        if (!_isRecording) return
        _isRecording = false
        Log.d(TAG, "发送录制停止信号")
        // Surface 输入模式下，调用 signalEndOfInputStream 通知编码器没有更多数据
        mediaCodec?.signalEndOfInputStream()
    }

    // =========================================================================
    // 编码线程：持续 drainEncoder 直到收到 EOS
    // =========================================================================
    private fun drainEncoder(onStop: (Uri?) -> Unit) {
        val codec = mediaCodec ?: return
        val muxer = mediaMuxer ?: return
        val info = MediaCodec.BufferInfo()

        try {
            while (true) {
                val outIdx = codec.dequeueOutputBuffer(info, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // 必须在这里才能拿到真正的输出 format，然后才能 addTrack + start muxer
                        muxerTrackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                        Log.d(TAG, "Muxer 已启动，track=$muxerTrackIndex")
                    }
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)!!
                        // 跳过 SPS/PPS 配置帧
                        if (muxerStarted && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                            && info.size > 0) {
                            muxer.writeSampleData(muxerTrackIndex, buf, info)
                        }
                        codec.releaseOutputBuffer(outIdx, false)

                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            Log.d(TAG, "编码器 EOS，录制结束")
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "编码线程异常", e)
        } finally {
            release()
            val uri = tmpFile?.let { saveToMediaStore(it) }
            tmpFile?.delete()
            tmpFile = null
            android.os.Handler(android.os.Looper.getMainLooper()).post { onStop(uri) }
        }
    }

    private fun release() {
        runCatching { virtualDisplay?.release() }
        runCatching { mediaProjection?.stop() }
        runCatching { if (muxerStarted) mediaMuxer?.stop() }
        runCatching { mediaMuxer?.release() }
        runCatching { mediaCodec?.stop() }
        runCatching { mediaCodec?.release() }
        virtualDisplay = null
        mediaProjection = null
        mediaMuxer = null
        mediaCodec = null
        muxerStarted = false
        muxerTrackIndex = -1
        Log.d(TAG, "资源已释放")
    }

    private fun saveToMediaStore(file: File): Uri? {
        return try {
            val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA)
                .format(System.currentTimeMillis())
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "VID_$name.mp4")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraView")
                }
            }
            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().buffered().use { it.copyTo(out) }
            }
            uri
        } catch (e: Exception) {
            Log.e(TAG, "保存到 MediaStore 失败", e)
            null
        }
    }
}
