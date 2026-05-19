package com.ytdownloader.app.ui.main

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.snackbar.Snackbar
import com.ytdownloader.app.R
import com.ytdownloader.app.data.model.*
import com.ytdownloader.app.databinding.ActivityMainBinding
import com.ytdownloader.app.ui.history.HistoryActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var activeAdapter: ActiveDownloadAdapter

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.all { it }
        if (!allGranted) {
            showSnackbar("Storage permission required to save downloads")
        }
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* ignore notification result */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        requestPermissions()
        setupUrlInput()
        setupVideoPreview()
        setupActiveDownloads()
        observeViewModel()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        intent ?: return
        val sharedText = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        sharedText?.let { url ->
            val ytUrl = extractYouTubeUrl(url)
            if (ytUrl != null) {
                binding.etUrl.setText(ytUrl)
                viewModel.fetchVideoInfo(ytUrl)
            }
        }
    }

    private fun extractYouTubeUrl(text: String): String? {
        val regex = Regex(
            "(https?://)?(www\\.)?(youtube\\.com/(watch\\?v=|shorts/|live/)|youtu\\.be/)[\\w\\-?=&%#]+"
        )
        return regex.find(text)?.value?.let {
            if (!it.startsWith("http")) "https://$it" else it
        }
    }

    // ─── Permissions ──────────────────────────────────────────────────────────

    private fun requestPermissions() {
        val perms = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasPermission(Manifest.permission.READ_MEDIA_VIDEO))
                perms.add(Manifest.permission.READ_MEDIA_VIDEO)
            if (!hasPermission(Manifest.permission.READ_MEDIA_AUDIO))
                perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (!hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE))
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        if (perms.isNotEmpty()) permissionLauncher.launch(perms.toTypedArray())
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // ─── URL Input ────────────────────────────────────────────────────────────

    private fun setupUrlInput() {
        binding.etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                fetchVideoInfo()
                true
            } else false
        }

        binding.btnFetch.setOnClickListener { fetchVideoInfo() }

        binding.btnPaste.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.text?.toString()
            if (!text.isNullOrBlank()) {
                binding.etUrl.setText(text)
                fetchVideoInfo()
            } else {
                showSnackbar("Clipboard is empty")
            }
        }

        binding.btnClear.setOnClickListener {
            binding.etUrl.setText("")
            viewModel.clearVideoInfo()
        }
    }

    private fun fetchVideoInfo() {
        hideKeyboard()
        val url = binding.etUrl.text?.toString()?.trim() ?: return
        viewModel.fetchVideoInfo(url)
    }

    // ─── Video Preview Card ───────────────────────────────────────────────────

    private fun setupVideoPreview() {
        binding.btnDownloadNow.setOnClickListener {
            val state = viewModel.videoInfoState.value
            if (state is UiState.Success) {
                showFormatSheet(state.data)
            }
        }
    }

    private fun showFormatSheet(videoInfo: VideoInfo) {
        // Register format result listener
        supportFragmentManager.setFragmentResultListener(
            DownloadBottomSheet.REQUEST_KEY, this
        ) { _, bundle ->
            val formatId = bundle.getString(DownloadBottomSheet.RESULT_FORMAT_ID) ?: return@setFragmentResultListener
            val formatNote = bundle.getString(DownloadBottomSheet.RESULT_FORMAT_NOTE) ?: ""
            val isAudio = bundle.getBoolean(DownloadBottomSheet.RESULT_IS_AUDIO, false)

            val format = VideoFormat(
                formatId = formatId,
                formatNote = formatNote,
                ext = if (isAudio) "mp3" else "mp4",
                resolution = if (!isAudio) formatNote else null,
                width = null, height = null, filesize = null, tbr = null,
                vcodec = if (!isAudio) "h264" else null,
                acodec = "aac",
                hasVideo = !isAudio,
                hasAudio = true
            )

            val request = DownloadRequest(
                url = videoInfo.url,
                format = format,
                qualityPreset = QualityPreset.CUSTOM,
                videoInfo = videoInfo
            )
            viewModel.startDownload(request)
        }

        DownloadBottomSheet.newInstance(videoInfo)
            .show(supportFragmentManager, DownloadBottomSheet.TAG)
    }

    private fun bindVideoPreview(info: VideoInfo) {
        binding.apply {
            cardVideoPreview.isVisible = true
            tvVideoTitle.text = info.title
            tvChannelName.text = info.uploader ?: ""
            tvDuration.text = info.getDurationFormatted()
            info.viewCount?.let {
                tvViewCount.text = formatViewCount(it)
                tvViewCount.isVisible = true
            } ?: run { tvViewCount.isVisible = false }

            Glide.with(ivThumbnail)
                .load(info.thumbnail)
                .centerCrop()
                .placeholder(R.drawable.ic_video_placeholder)
                .into(ivThumbnail)

            btnDownloadNow.isEnabled = true
        }
    }

    // ─── Active Downloads ─────────────────────────────────────────────────────

    private fun setupActiveDownloads() {
        activeAdapter = ActiveDownloadAdapter(
            onCancel = { record -> viewModel.cancelDownload(record.id) }
        )
        binding.rvActiveDownloads.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = activeAdapter
        }
    }

    // ─── Observers ────────────────────────────────────────────────────────────

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.videoInfoState.collectLatest { state ->
                        renderVideoInfoState(state)
                    }
                }
                launch {
                    viewModel.activeDownloads.collectLatest { downloads ->
                        activeAdapter.submitList(downloads)
                        binding.groupActiveDownloads.isVisible = downloads.isNotEmpty()
                        binding.tvActiveHeader.text =
                            "Active Downloads (${downloads.size})"
                    }
                }
                launch {
                    viewModel.progressMap.collectLatest { map ->
                        map.forEach { (id, progress) ->
                            activeAdapter.updateProgress(id, progress)
                        }
                    }
                }
                launch {
                    viewModel.events.collectLatest { event ->
                        handleEvent(event)
                    }
                }
                launch {
                    viewModel.binariesReady.collectLatest { ready ->
                        binding.btnFetch.isEnabled = ready
                        binding.btnPaste.isEnabled = ready
                        if (!ready) {
                            binding.tvBinaryStatus.isVisible = true
                            binding.tvBinaryStatus.text = "Initializing yt-dlp…"
                        } else {
                            binding.tvBinaryStatus.isVisible = false
                        }
                    }
                }
            }
        }
    }

    private fun renderVideoInfoState(state: UiState<VideoInfo>) {
        binding.apply {
            // Loading spinner
            progressFetch.isVisible = state is UiState.Loading
            btnFetch.isEnabled = state !is UiState.Loading

            when (state) {
                is UiState.Idle -> {
                    cardVideoPreview.isVisible = false
                    cardErrorMessage.isVisible = false
                }
                is UiState.Loading -> {
                    cardVideoPreview.isVisible = false
                    cardErrorMessage.isVisible = false
                    shimmerLayout.isVisible = true
                    shimmerLayout.startShimmer()
                }
                is UiState.Success -> {
                    shimmerLayout.stopShimmer()
                    shimmerLayout.isVisible = false
                    cardErrorMessage.isVisible = false
                    bindVideoPreview(state.data)
                }
                is UiState.Error -> {
                    shimmerLayout.stopShimmer()
                    shimmerLayout.isVisible = false
                    cardVideoPreview.isVisible = false
                    cardErrorMessage.isVisible = true
                    tvErrorMessage.text = state.message
                }
            }
        }
    }

    private fun handleEvent(event: MainViewModel.UiEvent) {
        when (event) {
            is MainViewModel.UiEvent.ShowError ->
                showSnackbar(event.message)
            is MainViewModel.UiEvent.DownloadStarted ->
                showSnackbar("Started: ${event.title}", Snackbar.LENGTH_SHORT)
            is MainViewModel.UiEvent.DownloadComplete ->
                showSnackbar("Download complete! ✓", Snackbar.LENGTH_LONG)
            is MainViewModel.UiEvent.RetryDownload -> {
                binding.etUrl.setText(event.record.url)
                viewModel.fetchVideoInfo(event.record.url)
            }
        }
    }

    // ─── Menu ─────────────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_history -> {
                startActivity(Intent(this, HistoryActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun showSnackbar(
        message: String,
        duration: Int = Snackbar.LENGTH_LONG
    ) {
        Snackbar.make(binding.root, message, duration).show()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etUrl.windowToken, 0)
    }

    private fun formatViewCount(count: Long): String = when {
        count >= 1_000_000_000 -> "%.1fB views".format(count / 1_000_000_000.0)
        count >= 1_000_000 -> "%.1fM views".format(count / 1_000_000.0)
        count >= 1_000 -> "%.1fK views".format(count / 1_000.0)
        else -> "$count views"
    }
}
