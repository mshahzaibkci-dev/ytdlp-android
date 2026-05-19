package com.ytdownloader.app.ui.main

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.ytdownloader.app.R
import com.ytdownloader.app.data.model.DownloadProgress
import com.ytdownloader.app.data.model.DownloadRecord
import com.ytdownloader.app.data.model.DownloadStatus
import com.ytdownloader.app.databinding.ItemActiveDownloadBinding

class ActiveDownloadAdapter(
    private val onCancel: (DownloadRecord) -> Unit
) : ListAdapter<DownloadRecord, ActiveDownloadAdapter.VH>(DiffCallback) {

    private val progressMap = mutableMapOf<Long, DownloadProgress>()

    fun updateProgress(downloadId: Long, progress: DownloadProgress) {
        progressMap[downloadId] = progress
        val idx = currentList.indexOfFirst { it.id == downloadId }
        if (idx >= 0) notifyItemChanged(idx)
    }

    inner class VH(private val binding: ItemActiveDownloadBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(record: DownloadRecord) {
            binding.apply {
                tvTitle.text = record.title
                tvQuality.text = record.qualityLabel
                tvUploader.text = record.uploader ?: ""

                Glide.with(ivThumbnail)
                    .load(record.thumbnailUrl)
                    .placeholder(R.drawable.ic_video_placeholder)
                    .centerCrop()
                    .into(ivThumbnail)

                val liveProgress = progressMap[record.id]
                val displayProgress = liveProgress?.progress ?: record.progress
                val status = liveProgress?.status ?: record.status

                progressBar.progress = displayProgress
                tvPercent.text = "$displayProgress%"

                when (status) {
                    DownloadStatus.QUEUED -> {
                        tvStatus.text = "Queued"
                        progressBar.isIndeterminate = true
                        tvSpeed.isVisible = false
                        tvEta.isVisible = false
                    }
                    DownloadStatus.FETCHING_INFO -> {
                        tvStatus.text = "Fetching info…"
                        progressBar.isIndeterminate = true
                        tvSpeed.isVisible = false
                        tvEta.isVisible = false
                    }
                    DownloadStatus.DOWNLOADING -> {
                        progressBar.isIndeterminate = false
                        tvStatus.text = "Downloading"
                        if (liveProgress != null) {
                            tvSpeed.text = liveProgress.getSpeedFormatted()
                            tvSpeed.isVisible = true
                            val eta = liveProgress.getEtaFormatted()
                            tvEta.text = eta?.let { "ETA $it" } ?: ""
                            tvEta.isVisible = eta != null
                        } else {
                            tvSpeed.isVisible = false
                            tvEta.isVisible = false
                        }
                    }
                    DownloadStatus.MERGING -> {
                        progressBar.isIndeterminate = true
                        tvStatus.text = "Merging…"
                        tvSpeed.isVisible = false
                        tvEta.isVisible = false
                    }
                    else -> {
                        tvStatus.text = status.name.lowercase().replaceFirstChar { it.uppercase() }
                        tvSpeed.isVisible = false
                        tvEta.isVisible = false
                    }
                }

                btnCancel.setOnClickListener { onCancel(record) }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemActiveDownloadBinding.inflate(
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
