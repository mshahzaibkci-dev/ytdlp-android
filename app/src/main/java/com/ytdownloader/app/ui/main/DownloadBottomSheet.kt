package com.ytdownloader.app.ui.main

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.ytdownloader.app.R
import com.ytdownloader.app.data.model.VideoFormat
import com.ytdownloader.app.data.model.VideoInfo
import com.ytdownloader.app.databinding.BottomSheetDownloadBinding

class DownloadBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetDownloadBinding? = null
    private val binding get() = _binding!!

    private lateinit var videoInfo: VideoInfo
    private lateinit var formatAdapter: FormatAdapter

    companion object {
        const val TAG = "DownloadBottomSheet"
        const val REQUEST_KEY = "download_request"
        const val RESULT_FORMAT_ID   = "format_id"
        const val RESULT_FORMAT_NOTE = "format_note"
        const val RESULT_IS_AUDIO    = "is_audio"
        private const val ARG_VIDEO_INFO = "video_info"

        fun newInstance(videoInfo: VideoInfo) = DownloadBottomSheet().apply {
            arguments = bundleOf(ARG_VIDEO_INFO to videoInfo)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videoInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireArguments().getParcelable(ARG_VIDEO_INFO, VideoInfo::class.java)!!
        } else {
            @Suppress("DEPRECATION")
            requireArguments().getParcelable(ARG_VIDEO_INFO)!!
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetDownloadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }

        bindVideoInfo()
        setupFormatList()
        setupButtons()
    }

    private fun bindVideoInfo() {
        binding.apply {
            tvVideoTitle.text  = videoInfo.title
            tvChannelName.text = videoInfo.uploader ?: ""
            tvDuration.text    = videoInfo.getDurationFormatted()

            videoInfo.viewCount?.let { count ->
                tvViewCount.text       = formatViewCount(count)
                tvViewCount.visibility = View.VISIBLE
            } ?: run { tvViewCount.visibility = View.GONE }

            Glide.with(ivThumbnail)
                .load(videoInfo.thumbnail)
                .centerCrop()
                .placeholder(R.drawable.ic_video_placeholder)
                .into(ivThumbnail)
        }
    }

    private fun setupFormatList() {
        formatAdapter = FormatAdapter(videoInfo.formats) { _ -> updateDownloadButton() }
        binding.rvFormats.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter        = formatAdapter
        }
        updateDownloadButton()
    }

    private fun setupButtons() {
        binding.btnDownload.setOnClickListener {
            val fmt = formatAdapter.getSelectedFormat() ?: return@setOnClickListener
            setFragmentResult(REQUEST_KEY, bundleOf(
                RESULT_FORMAT_ID   to fmt.formatId,
                RESULT_FORMAT_NOTE to fmt.getDisplayName(),
                RESULT_IS_AUDIO    to !fmt.hasVideo
            ))
            dismiss()
        }
        binding.btnCancel.setOnClickListener { dismiss() }
    }

    private fun updateDownloadButton() {
        val fmt = formatAdapter.getSelectedFormat()
        binding.btnDownload.isEnabled = fmt != null
        binding.btnDownload.text = if (fmt != null) "Download ${fmt.getDisplayName()}"
                                   else "Select Quality"
    }

    private fun formatViewCount(count: Long): String = when {
        count >= 1_000_000_000 -> "%.1fB views".format(count / 1_000_000_000.0)
        count >= 1_000_000     -> "%.1fM views".format(count / 1_000_000.0)
        count >= 1_000         -> "%.1fK views".format(count / 1_000.0)
        else                   -> "$count views"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
