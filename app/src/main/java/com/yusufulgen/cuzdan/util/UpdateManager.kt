package com.yusufulgen.cuzdan.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import com.yusufulgen.cuzdan.R
import com.yusufulgen.cuzdan.data.model.ReleaseNotesProvider
import com.yusufulgen.cuzdan.databinding.DialogUpdateAvailableBinding
import com.yusufulgen.cuzdan.ui.notifications.ReleaseNotesBottomSheet
import com.google.android.material.bottomsheet.BottomSheetDialog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefManager: PreferenceManager
) {

    fun checkForUpdates(activity: androidx.fragment.app.FragmentActivity) {
        // Mocking an update check. In a real app, you would fetch this from a server.
        val latestVersion = "1.3" 
        val currentVersion = getCurrentVersion()

        if (isNewerVersion(latestVersion, currentVersion)) {
            showUpdateDialog(activity)
        } else {
            // If no update, check if we should show release notes for the current version
            checkReleaseNotes(activity)
        }
    }

    private fun checkReleaseNotes(activity: androidx.fragment.app.FragmentActivity) {
        val currentVersion = getCurrentVersion()
        val lastShownVersion = prefManager.getLastShownReleaseVersion()

        if (currentVersion != lastShownVersion) {
            val note = ReleaseNotesProvider.notes.find { it.version == currentVersion }
            if (note != null) {
                ReleaseNotesBottomSheet.newInstance(note).show(activity.supportFragmentManager, "ReleaseNotes")
                prefManager.setLastShownReleaseVersion(currentVersion)
            }
        }
    }

    private fun showUpdateDialog(activity: androidx.fragment.app.FragmentActivity) {
        val dialog = BottomSheetDialog(activity)
        val binding = DialogUpdateAvailableBinding.inflate(LayoutInflater.from(activity))
        dialog.setContentView(binding.root)

        binding.btnUpdate.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(activity.getString(R.string.play_store_link))
                setPackage("com.android.vending")
            }
            try {
                activity.startActivity(intent)
            } catch (e: Exception) {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(activity.getString(R.string.play_store_link))))
            }
            dialog.dismiss()
        }

        binding.btnClose.setOnClickListener {
            dialog.dismiss()
            // Even if user closes, we might want to check release notes if they haven't seen them
            checkReleaseNotes(activity)
        }

        dialog.show()
    }

    private fun getCurrentVersion(): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        if (latest.isEmpty() || current.isEmpty()) return false
        val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        
        for (i in 0 until minOf(latestParts.size, currentParts.size)) {
            if (latestParts[i] > currentParts[i]) return true
            if (latestParts[i] < currentParts[i]) return false
        }
        return latestParts.size > currentParts.size
    }
}
