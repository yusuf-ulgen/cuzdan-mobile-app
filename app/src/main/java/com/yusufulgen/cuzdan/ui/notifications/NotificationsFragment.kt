package com.yusufulgen.cuzdan.ui.notifications

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.yusufulgen.cuzdan.R
import com.yusufulgen.cuzdan.ui.home.HomeViewModel
import com.yusufulgen.cuzdan.databinding.DialogSupportBinding
import com.yusufulgen.cuzdan.databinding.FragmentNotificationsBinding
import com.yusufulgen.cuzdan.util.showToast
import com.yusufulgen.cuzdan.util.PreferenceManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.lifecycle.lifecycleScope
import com.yusufulgen.cuzdan.data.repository.PortfolioRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class NotificationsFragment : Fragment() {

    @Inject
    lateinit var prefManager: PreferenceManager

    @Inject
    lateinit var portfolioRepository: PortfolioRepository

    private val homeViewModel: HomeViewModel by activityViewModels()

    private var _binding: FragmentNotificationsBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }


    override fun onCreateView(

        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNotificationsBinding.inflate(inflater, container, false)
        
        setupRecyclerView()
        
        return binding.root
    }

    private fun setupRecyclerView() {
        val settings = listOf(
            SettingItem(0, getString(R.string.settings_dark_theme), hasSwitch = true, isSwitchChecked = prefManager.getThemeMode() == "dark", iconRes = R.drawable.ic_p_theme),
            SettingItem(1, getString(R.string.settings_notifications), hasSwitch = true, isSwitchChecked = prefManager.isNotificationsEnabled(), iconRes = R.drawable.ic_p_notif),
            SettingItem(3, getString(R.string.settings_language), value = if (prefManager.getLanguage() == "tr") getString(R.string.label_turkish) else getString(R.string.label_english), iconRes = R.drawable.ic_p_lang),
            SettingItem(12, getString(R.string.nav_alerts), iconRes = R.drawable.ic_p_alert_vector),
            SettingItem(5, getString(R.string.settings_biometrics), hasSwitch = true, isSwitchChecked = prefManager.isBiometricsEnabled(), iconRes = R.drawable.ic_p_bio),

            SettingItem(6, getString(R.string.settings_device_management), iconRes = R.drawable.ic_p_res),
            SettingItem(7, getString(R.string.settings_faq), iconRes = R.drawable.ic_p_faq),
            SettingItem(8, getString(R.string.settings_support), iconRes = R.drawable.ic_p_sup),
            SettingItem(9, getString(R.string.settings_recommend), iconRes = R.drawable.ic_p_sha),
            SettingItem(10, getString(R.string.settings_agreement), iconRes = R.drawable.ic_p_agr),
            SettingItem(11, getString(R.string.settings_legal), iconRes = R.drawable.ic_p_agr),
            SettingItem(13, getString(R.string.settings_developer_apps), iconRes = R.drawable.ic_p_sha)
        )

        val adapter = SettingsAdapter(
            items = settings,
            onSwitchChanged = { id, isChecked ->
                handleSwitchChange(id, isChecked)
            },
            onItemClicked = { id ->
                handleItemClick(id)
            }
        )
        binding.recyclerSettings.apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
        }
    }

    private fun handleSwitchChange(id: Int, isChecked: Boolean) {
        when (id) {
            0 -> {
                prefManager.setThemeMode(if (isChecked) "dark" else "light")
                requireActivity().recreate()
            }
            1 -> prefManager.setNotificationsEnabled(isChecked)

            5 -> {
                prefManager.setBiometricsEnabled(isChecked)
            }
        }
    }

    private fun handleItemClick(id: Int) {
        when (id) {
            3 -> showLanguageDialog()
            6 -> showDeviceManagementDialog()
            7 -> showFAQDialog()
            8 -> showSupportDialog()
            9 -> shareApp()
            10 -> showAgreementDialog()
            11 -> showLegalWarningDialog()
            12 -> {
                val navController = androidx.navigation.fragment.NavHostFragment.findNavController(this)
                navController.navigate(R.id.navigation_alerts)
            }
            13 -> openDeveloperApps()
        }
    }

    private fun showLanguageDialog() {
        val languages = arrayOf(getString(R.string.label_turkish), getString(R.string.label_english))
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_language)
            .setItems(languages) { _, which ->
                val lang = if (which == 0) "tr" else "en"
                prefManager.setLanguage(lang)
                requireActivity().recreate()
            }
            .show()
    }

    private fun showDeviceManagementDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.reset_warning_title)
            .setMessage(R.string.reset_warning_message)
            .setPositiveButton(R.string.settings_account_reset) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    portfolioRepository.clearAllData()
                    prefManager.resetPreferences()
                    homeViewModel.resetState()
                    showToast(R.string.toast_account_reset_success)
                    requireActivity().recreate()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showFAQDialog() {
        val faqContent = "<b>${getString(R.string.faq_q1)}</b><br/>" +
                "${getString(R.string.faq_a1)}<br/><br/>" +
                "<b>${getString(R.string.faq_q2)}</b><br/>" +
                "${getString(R.string.faq_a2)}"
                
        AgreementBottomSheet.newInstance(
            title = getString(R.string.faq_title),
            content = faqContent,
            isReadOnly = true
        ).show(parentFragmentManager, "FAQ")
    }

    private fun showSupportDialog() {
        val dialog = BottomSheetDialog(requireContext())
        val binding = DialogSupportBinding.inflate(layoutInflater)
        dialog.setContentView(binding.root)

        binding.buttonSend.setOnClickListener {
            val email = binding.editEmail.text.toString()
            val message = binding.editMessage.text.toString()
            
            if (email.isNotEmpty() && message.isNotEmpty()) {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:admin@cuzdan.com")
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.support_subject))
                    putExtra(Intent.EXTRA_TEXT, "From: $email\n\nMessage: $message")
                }
                startActivity(Intent.createChooser(intent, getString(R.string.support_email_chooser)))
                showToast(R.string.toast_support_sent)
                dialog.dismiss()
            } else {
                showToast(R.string.toast_fill_all_fields)
            }
        }
        dialog.show()
    }

    private fun shareApp() {
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, getString(R.string.support_share_text, getString(R.string.play_store_link)))
            type = "text/plain"
        }
        startActivity(Intent.createChooser(sendIntent, null))
    }

    private fun openDeveloperApps() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(getString(R.string.play_store_developer_link))
        }
        startActivity(intent)
    }

    private fun showAgreementDialog() {
        AgreementBottomSheet.newInstance(
            title = getString(R.string.settings_agreement),
            content = getString(R.string.user_agreement_text),
            isReadOnly = false,
            onAccepted = {
                showToast(R.string.toast_agreement_accepted)
            }
        ).show(parentFragmentManager, "Agreement")
    }

    private fun showLegalWarningDialog() {
        AgreementBottomSheet.newInstance(
            title = getString(R.string.settings_legal),
            content = getString(R.string.legal_warning_text),
            isReadOnly = true
        ).show(parentFragmentManager, "Legal")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}