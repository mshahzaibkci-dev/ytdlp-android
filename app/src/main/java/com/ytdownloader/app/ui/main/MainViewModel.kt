package com.ytdownloader.app.ui.main

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytdownloader.app.data.model.*
import com.ytdownloader.app.data.repository.DownloadRepository
import com.ytdownloader.app.service.DownloadService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DownloadRepository
) : ViewModel() {

    // ─── Video Info State ──────────────────────────────────────────────────────
    private val _videoInfoState = MutableStateFlow<UiState<VideoInfo>>(UiState.Idle)
    val videoInfoState: StateFlow<UiState<VideoInfo>> = _videoInfoState.asStateFlow()

    // ─── Active Downloads ──────────────────────────────────────────────────────
    val activeDownloads: StateFlow<List<DownloadRecord>> = repository
        .getActiveDownloads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeCount: StateFlow<Int> = repository
        .getActiveCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // ─── Per-download live progress (from BroadcastReceiver) ──────────────────
    private val _progressMap = MutableStateFlow<Map<Long, DownloadProgress>>(emptyMap())
    val progressMap: StateFlow<Map<Long, DownloadProgress>> = _progressMap.asStateFlow()

    // ─── Snackbar events ──────────────────────────────────────────────────────
    private val _events = MutableSharedFlow<UiEvent>()
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    // ─── Binaries ready ───────────────────────────────────────────────────────
    private val _binariesReady = MutableStateFlow(false)
    val binariesReady: StateFlow<Boolean> = _binariesReady.asStateFlow()

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadService.EXTRA_DOWNLOAD_ID, -1L)
            if (id == -1L) return
            val progress = intent.getIntExtra(DownloadService.EXTRA_PROGRESS, 0)
            val speed = intent.getLongExtra(DownloadService.EXTRA_SPEED, 0L)
            val eta = if (intent.hasExtra(DownloadService.EXTRA_ETA))
                intent.getLongExtra(DownloadService.EXTRA_ETA, 0L) else null
            val statusStr = intent.getStringExtra(DownloadService.EXTRA_STATUS) ?: return
            val status = DownloadStatus.valueOf(statusStr)
            val message = intent.getStringExtra(DownloadService.EXTRA_ERROR)

            val dp = DownloadProgress(id, progress, speed, eta, status, message)
            _progressMap.value = _progressMap.value.toMutableMap().also { it[id] = dp }

            if (status == DownloadStatus.COMPLETED) {
                viewModelScope.launch { _events.emit(UiEvent.DownloadComplete(id)) }
            } else if (status == DownloadStatus.FAILED) {
                viewModelScope.launch {
                    _events.emit(UiEvent.ShowError(message ?: "Download failed"))
                }
            }
        }
    }

    init {
        registerReceiver()
        checkBinaries()
    }

    private fun registerReceiver() {
        val filter = IntentFilter(DownloadService.ACTION_PROGRESS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(progressReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(progressReceiver, filter)
        }
    }

    private fun checkBinaries() {
        viewModelScope.launch {
            val ready = repository.ensureBinariesReady()
            _binariesReady.value = ready
            if (!ready) _events.emit(UiEvent.ShowError("Failed to initialize yt-dlp binary"))
        }
    }

    // ─── Fetch video info ──────────────────────────────────────────────────────

    fun fetchVideoInfo(url: String) {
        val cleaned = url.trim()
        if (cleaned.isBlank()) {
            viewModelScope.launch { _events.emit(UiEvent.ShowError("Please enter a URL")) }
            return
        }
        if (!isValidYouTubeUrl(cleaned)) {
            viewModelScope.launch { _events.emit(UiEvent.ShowError("Invalid YouTube URL")) }
            return
        }
        viewModelScope.launch {
            _videoInfoState.value = UiState.Loading
            val result = repository.fetchVideoInfo(cleaned)
            _videoInfoState.value = result.fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { UiState.Error(it.message ?: "Failed to fetch video info", it) }
            )
        }
    }

    fun clearVideoInfo() {
        _videoInfoState.value = UiState.Idle
    }

    // ─── Start download ────────────────────────────────────────────────────────

    fun startDownload(request: DownloadRequest) {
        viewModelScope.launch {
            try {
                val format = request.format ?: run {
                    _events.emit(UiEvent.ShowError("No format selected"))
                    return@launch
                }
                val downloadId = repository.createDownloadRecord(request)
                val isAudio = !format.hasVideo

                DownloadService.startDownload(
                    context = context,
                    downloadId = downloadId,
                    url = request.url,
                    formatId = format.formatId,
                    isAudio = isAudio,
                    title = request.videoInfo.title,
                    thumbnail = request.videoInfo.thumbnail
                )

                _events.emit(UiEvent.DownloadStarted(downloadId, request.videoInfo.title))
                _videoInfoState.value = UiState.Idle
            } catch (e: Exception) {
                _events.emit(UiEvent.ShowError(e.message ?: "Failed to start download"))
            }
        }
    }

    fun cancelDownload(downloadId: Long) {
        DownloadService.cancelDownload(context, downloadId)
    }

    fun retryDownload(record: DownloadRecord) {
        // Re-fetch info and start again with same format
        viewModelScope.launch {
            _events.emit(UiEvent.RetryDownload(record))
        }
    }

    private fun isValidYouTubeUrl(url: String): Boolean {
        val patterns = listOf(
            "youtube.com/watch",
            "youtu.be/",
            "youtube.com/shorts/",
            "youtube.com/live/",
            "m.youtube.com/watch",
            "music.youtube.com"
        )
        return patterns.any { url.contains(it, ignoreCase = true) }
    }

    override fun onCleared() {
        super.onCleared()
        try { context.unregisterReceiver(progressReceiver) } catch (_: Exception) {}
    }

    // ─── Events ───────────────────────────────────────────────────────────────

    sealed class UiEvent {
        data class ShowError(val message: String) : UiEvent()
        data class DownloadStarted(val id: Long, val title: String) : UiEvent()
        data class DownloadComplete(val id: Long) : UiEvent()
        data class RetryDownload(val record: DownloadRecord) : UiEvent()
    }
}
