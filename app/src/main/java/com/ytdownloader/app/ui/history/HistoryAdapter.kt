package com.ytdownloader.app.ui.history

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.ytdownloader.app.R
import com.ytdownloader.app.data.model.DownloadRecord
import com.ytdownloader.app.data.model.DownloadStatus
import com.ytdownloader.app.databinding.ItemHistoryBinding
import java.text.SimpleDateFormat
import java.util.*

class HistoryAdapter(
    private val onOpen: (DownloadRecord) -> Unit,
    private val onShare: (DownloadRecord) -> Unit,
    private val onDelete: (DownloadRecord) -> Unit,
    private val onRetry: (DownloadRecord) -> Unit
) : ListAdapter<DownloadRecord, HistoryAdapter.VH>(DiffCallback) {

    private val dateFormat = SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault())

    inner class VH(private val binding: ItemHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(record: DownloadRecord) {
            binding.apply {
                tvTitle.text = record.title
                tvQuality.text = record.qualityLabel
                tvUploader.text = record.uploader ?: ""

                val dateStr = record.completedAt?.let { dateFormat.format(Date(it)) }
                    ?: dateFormat.format(Date(record.createdAt))
                tvDate.text = dateStr

                // File size
                if (record.fileSize > 0) {
                    tvFileSize.text = formatFileSize(record.fileSize)
                    tvFileSize.isVisible = true
                } else {
                    tvFileSize.isVisible = false
                }

                Glide.with(ivThumbnail)
                    .load(record.thumbnailUrl)
                    .centerCrop()
                    .placeholder(R.drawable.ic_video_placeholder)
                    .into(ivThumbnail)

                // Status chip
                when (record.status) {
                    DownloadStatus.COMPLETED -> {
                        chipStatus.text = "Completed"
                        chipStatus.chipBackgroundColor = ContextCompat.getColorStateList(
                            root.context, R.color.status_completed
                        )
                        btnOpen.isVisible = true
                        btnShare.isVisible = true
                        btnRetry.isVisible = false
                        tvError.isVisible = false
                    }
                    DownloadStatus.FAILED -> {
                        chipStatus.text = "Failed"
                        chipStatus.chipBackgroundColor = ContextCompat.getColorStateList(
                            root.context, R.color.status_failed
                        )
                        btnOpen.isVisible = false
                        btnShare.isVisible = false
                        btnRetry.isVisible = true
                        tvError.isVisible = !record.errorMessage.isNullOrBlank()
                        tvError.text = record.errorMessage
                    }
                    DownloadStatus.CANCELLED -> {
                        chipStatus.text = "Cancelled"
                        chipStatus.chipBackgroundColor = ContextCompat.getColorStateList(
                            root.context, R.color.status_cancelled
                        )
                        btnOpen.isVisible = false
                        btnShare.isVisible = false
                        btnRetry.isVisible = true
                        tvError.isVisible = false
                    }
                    else -> {
                        chipStatus.text = record.status.name
                        btnOpen.isVisible = false
                        btnShare.isVisible = false
                        btnRetry.isVisible = false
                        tvError.isVisible = false
                    }
                }

                btnOpen.setOnClickListener { onOpen(record) }
                btnShare.setOnClickListener { onShare(record) }
                btnDelete.setOnClickListener { onDelete(record) }
                btnRetry.setOnClickListener { onRetry(record) }
                root.setOnClickListener {
                    if (record.status == DownloadStatus.COMPLETED) onOpen(record)
                }
            }
        }
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    object DiffCallback : DiffUtil.ItemCallback<DownloadRecord>() {
        override fun areItemsTheSame(a: DownloadRecord, b: DownloadRecord) = a.id == b.id
        override fun areContentsTheSame(a: DownloadRecord, b: DownloadRecord) = a == b
    }
}
