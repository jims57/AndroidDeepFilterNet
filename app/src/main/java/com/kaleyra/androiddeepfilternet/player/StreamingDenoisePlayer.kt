package com.kaleyra.androiddeepfilternet.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.kaleyra.noise_filter.DeepFilterNet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 流式降噪播放器 - 参考rnnoise的WQMp3FilePlayer架构
 * 在播放过程中逐帧处理音频，降噪切换即时生效
 * 使用AudioTrack直接播放PCM数据，不需要ExoPlayer
 *
 * Author: Jimmy Gan
 * Date: 2026-03-07
 */
class StreamingDenoisePlayer {

    companion object {
        private const val TAG = "WQDeepFilterNet"
        // 音频格式参数（与DeepFilterNet训练数据一致）
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2 // 16-bit PCM
        // 播放线程每次喂数据的间隔
        private const val FEED_INTERVAL_MS = 10L
    }

    // 音频数据
    private var rawPcmData: ByteArray? = null
    // WAV头偏移（跳过WAV文件头，直接读PCM数据）
    private var pcmDataOffset: Int = 0
    private var pcmDataLength: Int = 0
    // 当前读取位置（字节偏移，相对于pcmDataOffset）
    private val currentPosition = AtomicLong(0L)

    // AudioTrack
    private var audioTrack: AudioTrack? = null

    // 播放状态
    private val isPlaying = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private var playbackThread: Thread? = null

    // DeepFilterNet模型（外部传入，生命周期由外部管理）
    @Volatile
    var deepFilterModel: DeepFilterNet? = null

    // 降噪开关（可在播放过程中随时切换，即时生效）
    @Volatile
    var isDenoiseEnabled: Boolean = false

    // 衰减级别（可在播放过程中随时切换）
    @Volatile
    var attenuationLevel: Float = 50f

    // 帧长度（从模型获取）
    private var frameLength: Int = 0

    // 帧处理缓冲区（复用，避免每帧分配）
    private var frameProcessingBuffer: ByteBuffer? = null

    // 回调
    private var progressCallback: AudioProgressCallback? = null

    // 实际采样率（从WAV头解析）
    private var actualSampleRate: Int = SAMPLE_RATE

    fun setAudioProgressCallback(callback: AudioProgressCallback?) {
        progressCallback = callback
    }

    /**
     * 加载原始音频数据（WAV格式）
     * 解析WAV头获取采样率等参数，定位PCM数据起始位置
     */
    fun loadAudio(data: ByteArray): Boolean {
        stop()
        rawPcmData = data

        // 解析WAV头
        if (data.size < 44) {
            Log.e(TAG, "StreamingPlayer: 数据太短，不是有效的WAV文件")
            return false
        }

        // 检查RIFF头
        val riffHeader = String(data, 0, 4)
        if (riffHeader == "RIFF") {
            // WAV格式，解析头
            val wavFormat = String(data, 8, 4)
            if (wavFormat != "WAVE") {
                Log.e(TAG, "StreamingPlayer: 不是有效的WAV文件")
                return false
            }

            // 解析采样率（偏移24-27，小端序）
            actualSampleRate = (data[24].toInt() and 0xFF) or
                    ((data[25].toInt() and 0xFF) shl 8) or
                    ((data[26].toInt() and 0xFF) shl 16) or
                    ((data[27].toInt() and 0xFF) shl 24)

            // 查找data chunk
            var offset = 12
            while (offset + 8 < data.size) {
                val chunkId = String(data, offset, 4)
                val chunkSize = (data[offset + 4].toInt() and 0xFF) or
                        ((data[offset + 5].toInt() and 0xFF) shl 8) or
                        ((data[offset + 6].toInt() and 0xFF) shl 16) or
                        ((data[offset + 7].toInt() and 0xFF) shl 24)

                if (chunkId == "data") {
                    pcmDataOffset = offset + 8
                    pcmDataLength = chunkSize.coerceAtMost(data.size - pcmDataOffset)
                    break
                }
                offset += 8 + chunkSize
                // 确保偶数对齐
                if (chunkSize % 2 != 0) offset++
            }

            if (pcmDataLength == 0) {
                Log.e(TAG, "StreamingPlayer: WAV文件中未找到data chunk")
                return false
            }

            Log.i(TAG, "StreamingPlayer: WAV加载成功, sampleRate=$actualSampleRate, pcmOffset=$pcmDataOffset, pcmLength=$pcmDataLength")
        } else {
            // 假设是纯PCM数据
            pcmDataOffset = 0
            pcmDataLength = data.size
            actualSampleRate = SAMPLE_RATE
            Log.i(TAG, "StreamingPlayer: 纯PCM数据加载, size=${data.size}, 使用默认采样率=$actualSampleRate")
        }

        currentPosition.set(0L)

        // 初始化AudioTrack
        initAudioTrack()

        return true
    }

    private fun initAudioTrack() {
        releaseAudioTrack()

        val minBufferSize = AudioTrack.getMinBufferSize(actualSampleRate, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = minBufferSize.coerceAtLeast(actualSampleRate * BYTES_PER_SAMPLE) // 至少1秒缓冲

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(actualSampleRate)
                    .setChannelMask(CHANNEL_CONFIG)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        Log.i(TAG, "StreamingPlayer: AudioTrack初始化, sampleRate=$actualSampleRate, bufferSize=$bufferSize")
    }

    private fun releaseAudioTrack() {
        try {
            audioTrack?.stop()
        } catch (e: Exception) {
            // ignore
        }
        try {
            audioTrack?.release()
        } catch (e: Exception) {
            // ignore
        }
        audioTrack = null
    }

    /**
     * 初始化DeepFilterNet帧处理（获取frameLength并分配缓冲区）
     * 必须在模型加载完成后调用
     */
    fun initFrameProcessing(): Boolean {
        val model = deepFilterModel ?: return false
        frameLength = model.frameLength.toInt()
        if (frameLength <= 0) {
            Log.e(TAG, "StreamingPlayer: 无效的frameLength=$frameLength")
            return false
        }

        frameProcessingBuffer = ByteBuffer.allocateDirect(frameLength).apply {
            order(ByteOrder.LITTLE_ENDIAN)
        }

        Log.i(TAG, "StreamingPlayer: 帧处理初始化完成, frameLength=$frameLength bytes (${frameLength / BYTES_PER_SAMPLE} samples)")
        return true
    }

    /**
     * 开始播放
     */
    fun play() {
        if (rawPcmData == null || audioTrack == null) {
            Log.e(TAG, "StreamingPlayer: 未加载音频数据或AudioTrack未初始化")
            return
        }

        if (isPaused.get()) {
            // 从暂停恢复
            isPaused.set(false)
            audioTrack?.play()
            Log.i(TAG, "StreamingPlayer: 恢复播放")
            return
        }

        if (isPlaying.get()) return

        isPlaying.set(true)
        isPaused.set(false)

        audioTrack?.play()

        // 启动播放线程（类似rnnoise的playbackThreadFunc）
        playbackThread = Thread({
            playbackLoop()
        }, "DeepFilterNet-Playback").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        Log.i(TAG, "StreamingPlayer: 开始播放, denoise=$isDenoiseEnabled, attenuation=$attenuationLevel")
    }

    /**
     * 暂停播放
     */
    fun pause() {
        if (!isPlaying.get()) return
        isPaused.set(true)
        try {
            audioTrack?.pause()
        } catch (e: Exception) {
            Log.e(TAG, "StreamingPlayer: pause异常: ${e.message}")
        }
        Log.i(TAG, "StreamingPlayer: 暂停播放")
    }

    /**
     * 停止播放
     */
    fun stop() {
        isPlaying.set(false)
        isPaused.set(false)

        playbackThread?.let { thread ->
            try {
                thread.join(2000)
            } catch (e: InterruptedException) {
                // ignore
            }
        }
        playbackThread = null

        try {
            audioTrack?.stop()
            audioTrack?.flush()
        } catch (e: Exception) {
            // ignore
        }

        currentPosition.set(0L)
        Log.i(TAG, "StreamingPlayer: 停止播放")
    }

    /**
     * 跳转到指定位置（毫秒）
     */
    fun seekTo(positionMs: Long) {
        val totalDuration = getDuration()
        if (totalDuration <= 0) return

        val targetMs = positionMs.coerceIn(0L, totalDuration)
        // 将毫秒转换为字节偏移
        val bytesPerMs = (actualSampleRate * BYTES_PER_SAMPLE) / 1000.0
        var byteOffset = (targetMs * bytesPerMs).toLong()
        // 对齐到采样边界
        byteOffset = byteOffset - (byteOffset % BYTES_PER_SAMPLE)
        byteOffset = byteOffset.coerceIn(0L, pcmDataLength.toLong())

        currentPosition.set(byteOffset)

        // 清空AudioTrack缓冲区以避免旧数据播放
        try {
            audioTrack?.flush()
        } catch (e: Exception) {
            // ignore
        }

        Log.i(TAG, "StreamingPlayer: seekTo ${targetMs}ms, byteOffset=$byteOffset")
    }

    fun isPlaying(): Boolean = isPlaying.get() && !isPaused.get()

    fun isPaused(): Boolean = isPaused.get()

    /**
     * 获取当前播放位置（毫秒）
     */
    fun getCurrentPosition(): Long {
        val bytesPlayed = currentPosition.get()
        if (actualSampleRate <= 0) return 0
        return (bytesPlayed * 1000L) / (actualSampleRate * BYTES_PER_SAMPLE)
    }

    /**
     * 获取总时长（毫秒）
     */
    fun getDuration(): Long {
        if (actualSampleRate <= 0 || pcmDataLength <= 0) return 0
        return (pcmDataLength.toLong() * 1000L) / (actualSampleRate * BYTES_PER_SAMPLE)
    }

    fun release() {
        stop()
        releaseAudioTrack()
        rawPcmData = null
        frameProcessingBuffer = null
        progressCallback = null
        Log.i(TAG, "StreamingPlayer: 已释放所有资源")
    }

    /**
     * 核心播放循环 - 类似rnnoise的playbackThreadFunc
     * 逐帧读取PCM数据，如果降噪开启则通过DeepFilterNet处理，然后喂给AudioTrack
     */
    private fun playbackLoop() {
        Log.i(TAG, "StreamingPlayer: 播放线程启动")

        val data = rawPcmData ?: return
        val track = audioTrack ?: return

        // 确定每次处理的chunk大小
        // 如果DeepFilterNet模型已初始化，使用其frameLength
        // 否则使用一个合理的默认值（10ms的数据量）
        val chunkSize = if (frameLength > 0) frameLength else (actualSampleRate * BYTES_PER_SAMPLE / 100) // 10ms

        val outputChunk = ByteArray(chunkSize)
        var isFirstFrame = true
        var lastProgressUpdateMs = 0L

        while (isPlaying.get()) {
            if (isPaused.get()) {
                Thread.sleep(50)
                continue
            }

            val pos = currentPosition.get().toInt()
            val remaining = pcmDataLength - pos

            if (remaining <= 0) {
                // 播放完成
                Log.i(TAG, "StreamingPlayer: 播放完成")
                isPlaying.set(false)

                // 回调通知
                progressCallback?.onAudioCompleted()
                break
            }

            val bytesToProcess = chunkSize.coerceAtMost(remaining)

            // 从原始数据读取一个chunk
            val srcOffset = pcmDataOffset + pos

            val wantDenoise = isDenoiseEnabled
            val model = deepFilterModel
            val fpBuffer = frameProcessingBuffer

            if (wantDenoise && model != null && fpBuffer != null && frameLength > 0 && bytesToProcess == frameLength) {
                // 降噪模式：通过DeepFilterNet逐帧处理
                if (!isFirstFrame) {
                    // 设置衰减级别
                    model.setAttenuationLimit(attenuationLevel)

                    fpBuffer.clear()
                    fpBuffer.put(data, srcOffset, bytesToProcess)
                    fpBuffer.flip()

                    // 调用DeepFilterNet处理单帧（就像rnnoise的wq_denoiser_process_bytes）
                    model.processFrame(fpBuffer)

                    fpBuffer.rewind()
                    fpBuffer.get(outputChunk, 0, bytesToProcess)
                } else {
                    // 第一帧跳过处理（包含WAV头信息等）
                    System.arraycopy(data, srcOffset, outputChunk, 0, bytesToProcess)
                    isFirstFrame = false
                }
            } else {
                // 非降噪模式：直接复制原始数据
                System.arraycopy(data, srcOffset, outputChunk, 0, bytesToProcess)
                if (isFirstFrame) isFirstFrame = false
            }

            // 写入AudioTrack
            val written = track.write(outputChunk, 0, bytesToProcess)
            if (written > 0) {
                currentPosition.addAndGet(written.toLong())
            } else if (written < 0) {
                Log.e(TAG, "StreamingPlayer: AudioTrack.write失败, code=$written")
                break
            }

            // 定期更新进度（不要每帧都更新，太频繁）
            val currentMs = getCurrentPosition()
            if (currentMs - lastProgressUpdateMs >= 100) {
                lastProgressUpdateMs = currentMs
                val duration = getDuration()
                progressCallback?.onProgressUpdate(currentMs, duration)
            }
        }

        Log.i(TAG, "StreamingPlayer: 播放线程结束")
    }
}
