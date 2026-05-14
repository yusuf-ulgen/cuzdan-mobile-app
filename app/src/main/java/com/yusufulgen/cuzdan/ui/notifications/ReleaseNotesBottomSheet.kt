package com.yusufulgen.cuzdan.ui.notifications

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.yusufulgen.cuzdan.R
import com.yusufulgen.cuzdan.data.model.ReleaseNote
import com.yusufulgen.cuzdan.databinding.BottomSheetReleaseNotesBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class ReleaseNotesBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetReleaseNotesBinding? = null
    private val binding get() = _binding!!

    private var releaseNote: ReleaseNote? = null

    companion object {
        fun newInstance(releaseNote: ReleaseNote): ReleaseNotesBottomSheet {
            return ReleaseNotesBottomSheet().apply {
                this.releaseNote = releaseNote
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetReleaseNotesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        releaseNote?.let { note ->
            binding.textVersion.text = getString(R.string.release_notes_version, note.version)
            
            val featuresText = note.features.joinToString("\n") { "• $it" }
            binding.textFeatures.text = featuresText
        }

        binding.btnClose.setOnClickListener {
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
