package com.kaleyra.androiddeepfilternet.ui.activity

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kaleyra.androiddeepfilternet.R
import com.kaleyra.androiddeepfilternet.filter.NativeDeepFilterNetLoader
import com.kaleyra.androiddeepfilternet.player.AudioProgressCallback
import com.kaleyra.androiddeepfilternet.player.StreamingDenoisePlayer
import com.kaleyra.androiddeepfilternet.utils.AndroidRawResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * DeepFilterNet降噪播放器 - 流式降噪架构
 * 参考rnnoise的WQMp3FilePlayer流式处理模式：
 * 播放过程中逐帧处理音频，降噪切换即时生效
 * Author: Jimmy Gan
 * Date: 2026-03-07
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WQDeepFilterNet"
        private const val SEEK_OFFSET_MS = 5_000L
    }

    // UI组件
    private lateinit var rvAudioList: RecyclerView
    private lateinit var seekBarProgress: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var btnDenoise: TextView
    private lateinit var btnPrev5s: Button
    private lateinit var btnPlayPause: TextView
    private lateinit var btnNext5s: Button
    private lateinit var btnAttenuation: TextView

    // 数据
    data class AudioItem(val name: String, val resId: Int)
    private val audioItems = listOf(
        AudioItem("Airplane noise", R.raw.airplane),
        AudioItem("Crowd noise", R.raw.crowd),
        AudioItem("Restaurant noise", R.raw.restaurant),
        AudioItem("Client audio", R.raw.client_audio_2)
    )
    private var selectedIndex = -1
    private lateinit var adapter: AudioListAdapter

    // 流式降噪播放器
    private lateinit var streamingPlayer: StreamingDenoisePlayer
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isProgressTracking = false

    // 资源加载器
    private lateinit var rawResourceLoader: AndroidRawResourceLoader

    // 降噪状态
    private var isDenoiseEnabled = false
    private var currentAttenuationLevel = 50f

    // 加载状态控制
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadJob: Job? = null
    @Volatile private var isLoading = false
    @Volatile private var loadingIndex = -1
    @Volatile private var isModelReady = false

    // 原始音频数据缓存: resId -> ByteArray
    private val rawAudioCache = mutableMapOf<Int, ByteArray>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // 初始化资源加载器
        rawResourceLoader = AndroidRawResourceLoader()

        // 初始化流式播放器
        streamingPlayer = StreamingDenoisePlayer()
        streamingPlayer.setAudioProgressCallback(object : AudioProgressCallback {
            override fun onProgressUpdate(currentPosition: Long, totalDuration: Long) {
                mainHandler.post {
                    if (!isProgressTracking && totalDuration > 0) {
                        val progress = (currentPosition.toDouble() / totalDuration * 1000).toInt()
                        seekBarProgress.progress = progress.coerceIn(0, 1000)
                    }
                    tvCurrentTime.text = formatTime(currentPosition)
                    tvTotalTime.text = "-${formatTime((totalDuration - currentPosition).coerceAtLeast(0))}"
                }
            }

            override fun onAudioCompleted() {
                mainHandler.post {
                    handlePlaybackCompleted()
                }
            }
        })

        // 预加载 DeepFilterNet 模型（启动时就开始，用户操作时模型已经准备好）
        val modelLoadStartTime = System.currentTimeMillis()
        Log.i(TAG, "onCreate: 开始预加载DeepFilterNet模型...")
        val loader = NativeDeepFilterNetLoader(applicationContext)
        coroutineScope.launch {
            try {
                val model = loader.loadDeepFilterNet()
                streamingPlayer.deepFilterModel = model
                streamingPlayer.initFrameProcessing()
                isModelReady = true
                val elapsed = System.currentTimeMillis() - modelLoadStartTime
                Log.i(TAG, "onCreate: DeepFilterNet模型预加载完成, 耗时=${elapsed}ms, frameLength=${model.frameLength}")
            } catch (e: Exception) {
                Log.e(TAG, "onCreate: DeepFilterNet模型预加载失败: ${e.message}", e)
            }
        }

        setupUI()
        Log.i(TAG, "onCreate: 流式降噪播放器初始化完成")
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        // 释放模型
        streamingPlayer.deepFilterModel?.release()
        streamingPlayer.release()
        rawAudioCache.clear()
        Log.i(TAG, "onDestroy: 已释放所有资源")
    }

    private fun setupUI() {
        rvAudioList = findViewById(R.id.rvAudioList)
        seekBarProgress = findViewById(R.id.seekBarProgress)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        btnDenoise = findViewById(R.id.btnDenoise)
        btnPrev5s = findViewById(R.id.btnPrev5s)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnNext5s = findViewById(R.id.btnNext5s)
        btnAttenuation = findViewById(R.id.btnAttenuation)

        // 初始状态：播放按钮禁用
        btnPlayPause.isEnabled = false

        // 设置RecyclerView - 列表立即显示
        adapter = AudioListAdapter(audioItems) { position ->
            Log.i(TAG, "选中音频: ${audioItems[position].name}, index=$position")
            selectedIndex = position
            adapter.setSelectedIndex(position)
            loadingIndex = position

            // 停止当前播放
            stopPlayback()
            btnPlayPause.isEnabled = false

            // 取消之前的加载任务
            loadJob?.cancel()

            // 加载原始音频数据（快速，只是读文件）
            loadJob = coroutineScope.launch {
                try {
                    isLoading = true
                    val currentLoadIndex = loadingIndex
                    val loaded = loadRawAudioData(currentLoadIndex)
                    if (loadingIndex != currentLoadIndex) {
                        Log.i(TAG, "加载过程中用户切换了选择，放弃当前结果")
                        return@launch
                    }
                    if (loaded) {
                        btnPlayPause.isEnabled = true
                        Log.i(TAG, "音频加载完成，播放按钮已启用, duration=${streamingPlayer.getDuration()}ms")
                    } else {
                        Log.e(TAG, "音频加载失败")
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    Log.i(TAG, "加载任务被取消")
                    throw e
                } finally {
                    isLoading = false
                }
            }
        }
        rvAudioList.layoutManager = LinearLayoutManager(this)
        rvAudioList.adapter = adapter

        // 进度条
        seekBarProgress.max = 1000
        seekBarProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = streamingPlayer.getDuration()
                    if (duration > 0) {
                        val targetMs = (progress / 1000.0 * duration).toLong()
                        streamingPlayer.seekTo(targetMs)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) { isProgressTracking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar) { isProgressTracking = false }
        })

        // 播放/暂停按钮
        btnPlayPause.setOnClickListener {
            if (selectedIndex < 0) return@setOnClickListener

            Log.i(TAG, "播放按钮点击: isPlaying=${streamingPlayer.isPlaying()}, isPaused=${streamingPlayer.isPaused()}, denoise=$isDenoiseEnabled, modelReady=$isModelReady")

            if (streamingPlayer.isPlaying()) {
                streamingPlayer.pause()
                btnPlayPause.text = "播放"
            } else {
                // 同步降噪状态到播放器（即时生效）
                streamingPlayer.isDenoiseEnabled = isDenoiseEnabled
                streamingPlayer.attenuationLevel = currentAttenuationLevel
                streamingPlayer.play()
                btnPlayPause.text = "暂停"
            }
        }

        // 后退5s
        btnPrev5s.setOnClickListener {
            val pos = streamingPlayer.getCurrentPosition() - SEEK_OFFSET_MS
            streamingPlayer.seekTo(pos.coerceAtLeast(0))
        }

        // 前进5s
        btnNext5s.setOnClickListener {
            val pos = streamingPlayer.getCurrentPosition() + SEEK_OFFSET_MS
            streamingPlayer.seekTo(pos)
        }

        // 降噪开关按钮
        btnDenoise.setOnClickListener { showDenoiseDialog() }

        // 衰减级别按钮
        btnAttenuation.setOnClickListener { showAttenuationDialog() }

        // 更新UI显示
        updateDenoiseButtonText()
        updateAttenuationButtonText()
    }

    /**
     * 加载原始音频数据并传给流式播放器
     * 不需要预处理降噪，播放时实时处理
     */
    private suspend fun loadRawAudioData(index: Int): Boolean {
        if (index < 0 || index >= audioItems.size) return false
        val item = audioItems[index]

        Log.i(TAG, "开始加载音频数据: ${item.name}")

        val rawData = withContext(Dispatchers.IO) {
            rawAudioCache[item.resId] ?: run {
                val data = rawResourceLoader.loadRawData(applicationContext, item.resId)
                if (data != null) {
                    rawAudioCache[item.resId] = data
                }
                data
            }
        }

        if (rawData != null) {
            val loaded = streamingPlayer.loadAudio(rawData)
            Log.i(TAG, "音频加载${if (loaded) "成功" else "失败"}: ${item.name}, size=${rawData.size}, duration=${streamingPlayer.getDuration()}ms")
            return loaded
        } else {
            Log.e(TAG, "音频数据加载失败: ${item.name}")
            return false
        }
    }

    private fun stopPlayback() {
        streamingPlayer.stop()
        btnPlayPause.text = "播放"
        seekBarProgress.progress = 0
        tvCurrentTime.text = "00:00"
        tvTotalTime.text = "-00:00"
    }

    private fun handlePlaybackCompleted() {
        btnPlayPause.text = "播放"
        seekBarProgress.progress = 0
        tvCurrentTime.text = "00:00"
        tvTotalTime.text = "-00:00"
    }

    /**
     * 显示降噪开关对话框
     * 降噪切换即时生效，下一帧音频就会应用降噪/不降噪
     */
    private fun showDenoiseDialog() {
        val labels = arrayOf("关闭降噪", "开启降噪")
        val currentIndex = if (isDenoiseEnabled) 1 else 0

        AlertDialog.Builder(this)
            .setTitle("降噪设置")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                val newEnabled = which == 1
                if (newEnabled != isDenoiseEnabled) {
                    isDenoiseEnabled = newEnabled
                    updateDenoiseButtonText()
                    // 直接设置播放器的降噪开关，下一帧即时生效（类似rnnoise的setDenoiseLevel）
                    streamingPlayer.isDenoiseEnabled = isDenoiseEnabled
                    Log.i(TAG, "降噪切换: enabled=$isDenoiseEnabled, 即时生效")
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 显示衰减级别对话框
     * 衰减级别切换即时生效，下一帧音频就会应用新的衰减级别
     */
    private fun showAttenuationDialog() {
        val levels = arrayOf("0", "10", "20", "30", "40", "50", "60", "70", "80", "90", "100")
        val currentIndex = levels.indexOfFirst { it.toFloat() == currentAttenuationLevel }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("衰减级别 (Attenuation)")
            .setSingleChoiceItems(levels, currentIndex) { dialog, which ->
                val newLevel = levels[which].toFloat()
                if (newLevel != currentAttenuationLevel) {
                    currentAttenuationLevel = newLevel
                    updateAttenuationButtonText()
                    // 直接设置播放器的衰减级别，下一帧即时生效
                    streamingPlayer.attenuationLevel = currentAttenuationLevel
                    Log.i(TAG, "衰减级别变更: level=$currentAttenuationLevel, 即时生效")
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateDenoiseButtonText() {
        btnDenoise.text = if (isDenoiseEnabled) "降噪:开" else "降噪:关"
    }

    private fun updateAttenuationButtonText() {
        btnAttenuation.text = "衰减:${currentAttenuationLevel.toInt()}"
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000).toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    // RecyclerView Adapter
    class AudioListAdapter(
        private val items: List<AudioItem>,
        private val onItemClick: (Int) -> Unit
    ) : RecyclerView.Adapter<AudioListAdapter.ViewHolder>() {

        private var selectedIndex = -1

        fun setSelectedIndex(index: Int) {
            val oldIndex = selectedIndex
            selectedIndex = index
            if (oldIndex >= 0) notifyItemChanged(oldIndex)
            if (index >= 0) notifyItemChanged(index)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_1, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.textView.text = items[position].name
            if (position == selectedIndex) {
                holder.textView.setTextColor(0xFF007AFF.toInt())
                holder.textView.setCompoundDrawablesWithIntrinsicBounds(0, 0,
                    R.drawable.ic_check_blue, 0)
            } else {
                holder.textView.setTextColor(0xFF000000.toInt())
                holder.textView.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            }
            holder.itemView.setOnClickListener { onItemClick(position) }
        }

        override fun getItemCount() = items.size

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val textView: TextView = itemView.findViewById(android.R.id.text1)
        }
    }
}
