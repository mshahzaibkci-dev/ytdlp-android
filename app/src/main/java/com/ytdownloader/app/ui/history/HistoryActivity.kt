package com.ytdownloader.app.ui.history

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.ytdownloader.app.R
import com.ytdownloader.app.data.model.DownloadRecord
import com.ytdownloader.app.data.model.DownloadStatus
import com.ytdownloader.app.databinding.ActivityHistoryBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

@AndroidEntryPoint
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private val viewModel: HistoryViewModel by viewModels()
    private lateinit var historyAdapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Download History"

        setupTabs()
        setupRecyclerView()
        observeViewModel()
    }

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val filter = when (tab.position) {
                    0 -> HistoryViewModel.FilterMode.ALL
                    1 -> HistoryViewModel.FilterMode.COMPLETED
                    2 -> HistoryViewModel.FilterMode.FAILED
                    else -> HistoryViewModel.FilterMode.ALL
                }
                viewModel.setFilter(filter)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun setupRecyclerView() {
        historyAdapter = HistoryAdapter(
            onOpen = { viewModel.openFile(it) },
            onShare = { viewModel.shareFile(it) },
            onDelete = { confirmDelete(it) },
            onRetry = { retryDownload(it) }
        )

        binding.rvHistory.apply {
            layoutManager = LinearLayoutManager(this@HistoryActivity)
            adapter = historyAdapter
        }

        // Swipe to delete
        val swipeCallback = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder,
                                target: RecyclerView.ViewHolder) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val record = historyAdapter.currentList[viewHolder.adapterPosition]
                confirmDelete(record)
                // Restore item while dialog is shown
                historyAdapter.notifyItemChanged(viewHolder.adapterPosition)
            }
        }
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.rvHistory)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.downloads.collectLatest { records ->
                        historyAdapter.submitList(records)
                        binding.layoutEmpty.isVisible = records.isEmpty()
                        binding.rvHistory.isVisible = records.isNotEmpty()
                    }
                }
                launch {
                    viewModel.events.collectLatest { event ->
                        handleEvent(event)
                    }
                }
            }
        }
    }

    private fun handleEvent(event: HistoryViewModel.Event) {
        when (event) {
            is HistoryViewModel.Event.ShowMessage ->
                Snackbar.make(binding.root, event.msg, Snackbar.LENGTH_SHORT).show()
            is HistoryViewModel.Event.OpenFile -> openFile(event.path, event.qualityLabel)
            is HistoryViewModel.Event.ShareFile -> shareFile(event.path)
        }
    }

    private fun openFile(path: String, qualityLabel: String) {
        try {
            val isAudio = qualityLabel.contains("audio", ignoreCase = true)
                || qualityLabel.contains("mp3", ignoreCase = true)
                || qualityLabel.contains("m4a", ignoreCase = true)

            val mimeType = if (isAudio) "audio/*" else "video/*"

            val intent = if (path.startsWith("content://")) {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(path), mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                val file = File(path)
                val uri = FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    file
                )
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

            startActivity(Intent.createChooser(intent, "Open with"))
        } catch (e: Exception) {
            Snackbar.make(binding.root, "Cannot open file: ${e.message}", Snackbar.LENGTH_LONG)
                .show()
        }
    }

    private fun shareFile(path: String) {
        try {
            val uri = if (path.startsWith("content://")) Uri.parse(path)
            else FileProvider.getUriForFile(this, "${packageName}.fileprovider", File(path))

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share"))
        } catch (e: Exception) {
            Snackbar.make(binding.root, "Cannot share file", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(record: DownloadRecord) {
        AlertDialog.Builder(this)
            .setTitle("Delete download?")
            .setMessage("\"${record.title}\"\n\nAlso delete the file from storage?")
            .setPositiveButton("Delete record only") { _, _ ->
                viewModel.deleteRecord(record)
            }
            .setNeutralButton("Delete record + file") { _, _ ->
                viewModel.deleteRecordAndFile(record)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun retryDownload(record: DownloadRecord) {
        val intent = Intent(this, com.ytdownloader.app.ui.main.MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(record.url)
        }
        startActivity(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_history, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            R.id.action_clear_completed -> {
                viewModel.clearCompleted(); true
            }
            R.id.action_clear_failed -> {
                viewModel.clearFailed(); true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
