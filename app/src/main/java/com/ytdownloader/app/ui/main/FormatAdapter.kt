package com.ytdownloader.app.ui.main

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.ytdownloader.app.R
import com.ytdownloader.app.data.model.VideoFormat
import com.ytdownloader.app.databinding.ItemFormatBinding

class FormatAdapter(
    private val formats: List<VideoFormat>,
    private val onSelect: (VideoFormat) -> Unit
) : RecyclerView.Adapter<FormatAdapter.VH>() {

    private var selectedIndex = 0

    inner class VH(private val binding: ItemFormatBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(format: VideoFormat, isSelected: Boolean) {
            binding.apply {
                tvFormatName.text = format.getDisplayName()
                tvFormatSize.text = format.getFileSizeFormatted() ?: ""

                // Icon
                val iconRes = when {
                    !format.hasVideo -> R.drawable.ic_audio
                    else -> R.drawable.ic_video
                }
                ivFormatIcon.setImageResource(iconRes)

                // Selection state
                root.isSelected = isSelected
                val bgColor = if (isSelected)
                    ContextCompat.getColor(root.context, R.color.format_selected_bg)
                else
                    ContextCompat.getColor(root.context, android.R.color.transparent)
                root.setBackgroundColor(bgColor)

                val textColor = if (isSelected)
                    ContextCompat.getColor(root.context, R.color.colorPrimary)
                else
                    ContextCompat.getColor(root.context, R.color.text_primary)
                tvFormatName.setTextColor(textColor)

                ivSelected.visibility = if (isSelected)
                    android.view.View.VISIBLE else android.view.View.INVISIBLE

                root.setOnClickListener {
                    val prev = selectedIndex
                    selectedIndex = adapterPosition
                    notifyItemChanged(prev)
                    notifyItemChanged(selectedIndex)
                    onSelect(format)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemFormatBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(formats[position], position == selectedIndex)
    }

    override fun getItemCount() = formats.size

    fun getSelectedFormat(): VideoFormat? = formats.getOrNull(selectedIndex)
}
