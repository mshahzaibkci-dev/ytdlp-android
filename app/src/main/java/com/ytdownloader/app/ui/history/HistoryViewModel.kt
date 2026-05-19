package com.ytdownloader.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytdownloader.app.data.model.DownloadRecord
import com.ytdownloader.app.data.repository.DownloadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: DownloadRepository
) : ViewModel() {

    enum class FilterMode { ALL, COMPLETED, FAILED }

    private val _filter = MutableStateFlow(FilterMode.ALL)
    val filter: StateFlow<FilterMode> = _filter.asStateFlow()

    val downloads: StateFlow<List<DownloadRecord>> = _filter.flatMapLatest { mode ->
        when (mode) {
            FilterMode.ALL -> repository.getAllDownloads()
            FilterMode.COMPLETED -> repository.getCompletedDownloads()
            FilterMode.FAILED -> repository.getFailedDownloads()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _events = MutableSharedFlow<Event>()
    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun setFilter(mode: FilterMode) { _filter.value = mode }

    fun deleteRecord(record: DownloadRecord) {
        viewModelScope.launch {
            repository.deleteRecord(record.id)
        }
    }

    fun deleteRecordAndFile(record: DownloadRecord) {
        viewModelScope.launch {
            record.filePath?.let { path ->
                if (!path.startsWith("content://")) {
                    File(path).takeIf { it.exists() }?.delete()
                }
            }
            repository.deleteRecord(record.id)
            _events.emit(Event.ShowMessage("Deleted"))
        }
    }

    fun clearCompleted() {
        viewModelScope.launch {
            repository.clearCompleted()
            _events.emit(Event.ShowMessage("Cleared completed downloads"))
        }
    }

    fun clearFailed() {
        viewModelScope.launch {
            repository.clearFailed()
            _events.emit(Event.ShowMessage("Cleared failed downloads"))
        }
    }

    fun openFile(record: DownloadRecord) {
        viewModelScope.launch {
            val path = record.filePath
            if (path.isNullOrBlank()) {
                _events.emit(Event.ShowMessage("File path not available"))
                return@launch
            }
            _events.emit(Event.OpenFile(path, record.qualityLabel))
        }
    }

    fun shareFile(record: DownloadRecord) {
        viewModelScope.launch {
            val path = record.filePath ?: return@launch
            _events.emit(Event.ShareFile(path))
        }
    }

    sealed class Event {
        data class ShowMessage(val msg: String) : Event()
        data class OpenFile(val path: String, val qualityLabel: String) : Event()
        data class ShareFile(val path: String) : Event()
    }
}
