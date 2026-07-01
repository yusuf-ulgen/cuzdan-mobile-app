package com.yusufulgen.cuzdan

import android.content.Context
import android.os.Bundle
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import androidx.appcompat.app.AlertDialog
import androidx.biometric.BiometricPrompt
import com.yusufulgen.cuzdan.util.showToast
import androidx.core.content.ContextCompat
import androidx.biometric.BiometricManager
import android.widget.Toast
import com.yusufulgen.cuzdan.databinding.ActivityMainBinding
import com.yusufulgen.cuzdan.ui.notifications.AgreementBottomSheet
import com.yusufulgen.cuzdan.util.PreferenceManager
import com.yusufulgen.cuzdan.util.PriceSyncManager
import com.yusufulgen.cuzdan.util.UpdateManager
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.Executor
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var prefManager: PreferenceManager

    @Inject
    lateinit var priceSyncManager: PriceSyncManager

    @Inject
    lateinit var updateManager: UpdateManager

    private lateinit var binding: ActivityMainBinding

    override fun onResume() {
        super.onResume()
        priceSyncManager.startPolling()

        // Biyometrik kontrol (Cooldown 30 saniye)
        if (prefManager.isAgreementAccepted() && prefManager.isBiometricsEnabled()) {
            val now = System.currentTimeMillis()
            val lastAuth = prefManager.getLastAuthTimestamp()
            if (now - lastAuth > 30_000) { // 30 saniye cooldown
                checkBiometrics()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        priceSyncManager.stopPolling()
    }
    
    override fun attachBaseContext(newBase: Context) {
        val prefManager = PreferenceManager(newBase)
        val lang = prefManager.getLanguage()
        super.attachBaseContext(com.yusufulgen.cuzdan.util.LocaleHelper.onAttach(newBase, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val themeMode = PreferenceManager(this).getThemeMode()
        if (themeMode == "light") {
            setTheme(R.style.Theme_Cuzdan_Light)
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
        } else {
            setTheme(R.style.Theme_Cuzdan_Dark)
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        }
        super.onCreate(savedInstanceState)

        val context = androidx.appcompat.view.ContextThemeWrapper(this, if (themeMode == "light") R.style.Theme_Cuzdan_Light else R.style.Theme_Cuzdan_Dark)
        binding = ActivityMainBinding.inflate(android.view.LayoutInflater.from(context))
        setContentView(binding.root)

        val navView: BottomNavigationView = binding.navView

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment_activity_main) as androidx.navigation.fragment.NavHostFragment
        val navController = navHostFragment.navController

        // Top-level tab IDs — switching between these should always pop sub-pages
        val topLevelIds = setOf(
            R.id.navigation_assets,
            R.id.navigation_reports,
            R.id.navigation_home,
            R.id.navigation_markets,
            R.id.navigation_settings
        )

        // Custom tab selection: pop back stack to tab root before navigating
        navView.setOnItemSelectedListener { item ->
            val currentDest = navController.currentDestination?.id
            if (currentDest == item.itemId) {
                // Aynı tab'a tıklanırsa — alt sayfalardan kök sayfaya dön
                navController.popBackStack(item.itemId, false)
                true
            } else {
                // Farklı bir tab — eski tab'ın alt sayfalarını temizle
                navController.navigate(item.itemId, null,
                    androidx.navigation.NavOptions.Builder()
                        .setPopUpTo(navController.graph.startDestinationId, false)
                        .setLaunchSingleTop(true)
                        .build()
                )
                true
            }
        }

        // Sync bottom nav selection with NavController destination changes
        navController.addOnDestinationChangedListener { _, destination, _ ->
            // Sadece top-level destinasyonlarda alt çubuğu güncelle
            if (destination.id in topLevelIds) {
                navView.menu.findItem(destination.id)?.isChecked = true
            }
            updateBottomNavIcons(navView, destination.id)
        }

        navView.itemIconTintList = null

        // Fix icon size for PNG-based navigation icons (square assets in drawable)
        navView.itemIconSize = resources.getDimensionPixelSize(R.dimen.nav_icon_size)

        // Servisi her açılışta tetikle (Görünürlüğü ve çalışmayı garanti altına alır)
        startPriceSyncService()

        if (!prefManager.isAgreementAccepted()) {
            checkUserAgreement()
        } else {
            checkNotificationPermission()
        }

        updateManager.checkForUpdates(this)
    }

    private fun startPriceSyncService() {
        val serviceIntent = Intent(this, PriceSyncService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun updateBottomNavIcons(navView: BottomNavigationView, selectedId: Int) {
        val menuView = navView.getChildAt(0) as? android.view.ViewGroup ?: return
        for (i in 0 until menuView.childCount) {
            val itemView = menuView.getChildAt(i) as? android.view.ViewGroup ?: continue
            val itemId = itemView.id
            
            // Find the icon ImageView inside the item view
            val iconView = itemView.findViewById<android.view.View>(com.google.android.material.R.id.navigation_bar_item_icon_view)
            
            if (iconView != null) {
                val scale = if (itemId == selectedId) 1.2f else 1.0f
                iconView.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .setDuration(200)
                    .start()
            }
        }
    }

    private fun checkUserAgreement() {
        if (!prefManager.isAgreementAccepted()) {
            AgreementBottomSheet.newInstance(
                title = getString(R.string.settings_agreement),
                content = getString(R.string.user_agreement_text),
                isReadOnly = false,
                isMandatory = true,
                onAccepted = {
                    prefManager.setAgreementAccepted(true)
                    checkBiometrics()
                }
            ).show(supportFragmentManager, "InitialAgreement")
        }
    }

    private fun checkBiometrics() {
        if (!prefManager.isBiometricsEnabled()) return

        val biometricManager = BiometricManager.from(this)
        val canAuthenticate = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)

        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            showToast(R.string.toast_biometric_error)
            prefManager.setBiometricsEnabled(false)
            return
        }

        showBiometricPrompt()
    }

    private fun showBiometricPrompt() {
        val executor: Executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    // Hata durumunda uygulamayı kapat veya tekrar dene
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        finish()
                    }
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    // Başarılı giriş, timestamp güncelle
                    prefManager.setLastAuthTimestamp(System.currentTimeMillis())
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // Başarısız deneme, kullanıcıya bilgi verilebilir
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.settings_biometrics))
            .setSubtitle(getString(R.string.login_description))
            .setNegativeButtonText(getString(R.string.dialog_cancel))
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    private fun checkNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    101
                )
            }
        }
    }
}