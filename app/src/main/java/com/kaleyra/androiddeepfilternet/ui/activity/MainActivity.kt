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
import com.kaleyra.androiddeepfilternet.filter.DefaultDeepAudioFilter
import com.kaleyra.androiddeepfilternet.player.AudioPlayer
import com.kaleyra.androiddeepfilternet.player.AudioProgressCallback
import com.kaleyra.androiddeepfilternet.player.DefaultAudioPlayer
import com.kaleyra.androiddeepfilternet.utils.AndroidRawResourceLoader
import com.kaleyra.androiddeepfilternet.utils.ByteBufferConverter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * DeepFilterNet降噪播放器 - 参考Mp3Fragment的UI和交互模式
 * 使用RecyclerView显示音频列表，底部播放器控制面板
 * 选中音频后按需加载和降噪处理，即时播放
 * Author: Jimmy Gan
 * Date: 2026-03-06
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DeepFilterNetPlayer"
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

    // 播放器
    private lateinit var audioPlayer: AudioPlayer
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isProgressTracking = false

    // DeepFilterNet降噪
    private lateinit var deepAudioFilter: DefaultDeepAudioFilter
    private lateinit var rawResourceLoader: AndroidRawResourceLoader

    // 降噪状态
    private var isDenoiseEnabled = false
    private var currentAttenuationLevel = 50f

    // 加载状态控制
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadJob: Job? = null
    private var filterJob: Job? = null
    @Volatile private var isLoading = false
    @Volatile private var loadingIndex = -1

    // 已加载的音频源缓存: mediaId -> 是否已加载
    private val loadedAudioSources = mutableMapOf<String, Boolean>()
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

        // 初始化DeepFilterNet
        deepAudioFilter = DefaultDeepAudioFilter(applicationContext)
        rawResourceLoader = AndroidRawResourceLoader()

        // 初始化播放器
        audioPlayer = DefaultAudioPlayer(applicationContext)
        audioPlayer.setAudioProgressCallback(object : AudioProgressCallback {
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
                audioPlayer.stop()
                mainHandler.post {
                    handlePlaybackCompleted()
                }
            }
        })

        setupUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        audioPlayer.release()
        rawAudioCache.clear()
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
            // 立即更新选中状态UI
            selectedIndex = position
            adapter.setSelectedIndex(position)
            loadingIndex = position

            // 停止当前播放
            stopPlayback()
            btnPlayPause.isEnabled = false

            // 取消之前的加载任务
            loadJob?.cancel()
            filterJob?.cancel()

            // 第一阶段：快速加载原始音频，完成后立即启用播放
            loadJob = coroutineScope.launch {
                try {
                    isLoading = true
                    val currentLoadIndex = loadingIndex
                    val loaded = loadRawAudioSource(currentLoadIndex)
                    // 检查是否在加载过程中用户切换了选择
                    if (loadingIndex != currentLoadIndex) {
                        Log.i(TAG, "加载过程中用户切换了选择，放弃当前结果")
                        return@launch
                    }
                    if (loaded) {
                        btnPlayPause.isEnabled = true
                        Log.i(TAG, "原始音频就绪，播放按钮已启用")

                        // 第二阶段：后台加载降噪音频（不阻塞播放）
                        filterJob = coroutineScope.launch {
                            loadFilteredAudioSource(currentLoadIndex)
                        }
                    } else {
                        Log.e(TAG, "原始音频加载失败")
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
                    val duration = audioPlayer.getCurrentTrackDuration()
                    if (duration > 0) {
                        val targetMs = (progress / 1000.0 * duration).toLong()
                        audioPlayer.seekTo(targetMs)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) { isProgressTracking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar) { isProgressTracking = false }
        })

        // 播放/暂停按钮
        btnPlayPause.setOnClickListener {
            if (selectedIndex < 0) return@setOnClickListener
            val item = audioItems[selectedIndex]
            val mediaId = getMediaSourceId(item.resId, isDenoiseEnabled)

            Log.i(TAG, "播放按钮点击, isPlaying=${audioPlayer.isPlaying()}, mediaId=$mediaId, loaded=${loadedAudioSources[mediaId]}")

            if (audioPlayer.isPlaying()) {
                audioPlayer.pause()
                btnPlayPause.text = "播放"
            } else {
                // 如果降噪音频还没加载好，先用原始音频播放
                val actualMediaId = if (loadedAudioSources[mediaId] == true) {
                    mediaId
                } else {
                    val fallbackId = getMediaSourceId(item.resId, false)
                    Log.i(TAG, "降噪音频未就绪，使用原始音频: $fallbackId")
                    fallbackId
                }
                audioPlayer.start(id = actualMediaId, resetPosition = false)
                btnPlayPause.text = "暂停"
            }
        }

        // 后退5s
        btnPrev5s.setOnClickListener {
            val pos = audioPlayer.getCurrentTrackPosition() - SEEK_OFFSET_MS
            audioPlayer.seekTo(pos.coerceAtLeast(0))
        }

        // 前进5s
        btnNext5s.setOnClickListener {
            val pos = audioPlayer.getCurrentTrackPosition() + SEEK_OFFSET_MS
            audioPlayer.seekTo(pos)
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
     * 第一阶段：快速加载原始音频（不需要DeepFilterNet模型）
     * 读取raw资源 -> ByteArray -> 添加到播放器，立即可播放
     */
    private suspend fun loadRawAudioSource(index: Int): Boolean {
        if (index < 0 || index >= audioItems.size) return false
        val item = audioItems[index]
        val noisyId = getMediaSourceId(item.resId, false)

        // 如果已经加载过noisy源，直接返回
        if (loadedAudioSources[noisyId] == true) {
            Log.i(TAG, "原始音频已缓存: ${item.name}")
            return true
        }

        Log.i(TAG, "开始加载原始音频: ${item.name}")

        val rawData = withContext(Dispatchers.IO) {
            // 优先使用缓存
            rawAudioCache[item.resId] ?: run {
                val data = rawResourceLoader.loadRawData(applicationContext, item.resId)
                if (data != null) {
                    rawAudioCache[item.resId] = data
                }
                data
            }
        }

        if (rawData != null) {
            val noisyBuffer = ByteBufferConverter.convert(rawData)
            audioPlayer.addMediaSource(noisyId, noisyBuffer.array())
            loadedAudioSources[noisyId] = true
            Log.i(TAG, "原始音频加载完成: ${item.name}, size=${rawData.size}")
            return true
        } else {
            Log.e(TAG, "原始音频加载失败: ${item.name}")
            return false
        }
    }

    /**
     * 第二阶段：后台加载降噪音频（需要DeepFilterNet模型，耗时较长）
     * 加载完成后可切换到降噪播放
     */
    private suspend fun loadFilteredAudioSource(index: Int) {
        if (index < 0 || index >= audioItems.size) return
        val item = audioItems[index]
        val filteredId = getMediaSourceId(item.resId, true)

        // 如果已经加载过filtered源，直接返回
        if (loadedAudioSources[filteredId] == true) {
            Log.i(TAG, "降噪音频已缓存: ${item.name}")
            return
        }

        val rawData = rawAudioCache[item.resId]
        if (rawData == null) {
            Log.e(TAG, "无法加载降噪音频，原始数据未缓存: ${item.name}")
            return
        }

        Log.i(TAG, "开始DeepFilterNet降噪处理: ${item.name}, attenuation=$currentAttenuationLevel")

        val filteredBuffer = withContext(Dispatchers.IO) {
            deepAudioFilter.filter(rawData, currentAttenuationLevel)
        }

        if (filteredBuffer != null) {
            audioPlayer.addMediaSource(filteredId, filteredBuffer.array())
            loadedAudioSources[filteredId] = true
            Log.i(TAG, "降噪音频加载完成: ${item.name}")
        } else {
            Log.e(TAG, "降噪处理失败: ${item.name}")
        }
    }

    private fun stopPlayback() {
        audioPlayer.stop()
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

                    // 如果正在播放，切换到对应的音频源
                    if (audioPlayer.isPlaying() && selectedIndex >= 0) {
                        val item = audioItems[selectedIndex]
                        val mediaId = getMediaSourceId(item.resId, isDenoiseEnabled)
                        if (loadedAudioSources[mediaId] == true) {
                            audioPlayer.start(id = mediaId, resetPosition = false)
                        }
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 显示衰减级别对话框
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

                    // 衰减级别改变只影响降噪音频，需要清除filtered缓存并重新生成
                    if (selectedIndex >= 0) {
                        // 清除所有filtered源
                        val keysToRemove = loadedAudioSources.keys.filter { it.endsWith("_filtered") }
                        keysToRemove.forEach { loadedAudioSources.remove(it) }

                        // 取消之前的降噪任务，重新启动
                        filterJob?.cancel()
                        filterJob = coroutineScope.launch {
                            loadFilteredAudioSource(selectedIndex)

                            // 如果正在播放且降噪开启，切换到新的降噪音频
                            if (audioPlayer.isPlaying() && isDenoiseEnabled) {
                                val item = audioItems[selectedIndex]
                                val filteredId = getMediaSourceId(item.resId, true)
                                if (loadedAudioSources[filteredId] == true) {
                                    audioPlayer.start(id = filteredId, resetPosition = false)
                                }
                            }
                        }
                    }
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

    private fun getMediaSourceId(resId: Int, isDenoiseEnabled: Boolean): String {
        return "${resId}_${if (isDenoiseEnabled) "filtered" else "noisy"}"
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
