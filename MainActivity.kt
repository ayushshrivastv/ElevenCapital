package com.elevencapital.app

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.elevencapital.app.auth.AppLockViewModel
import com.elevencapital.app.auth.DeviceUnlockAttempt
import com.elevencapital.app.auth.DeviceUnlockPhase
import com.elevencapital.app.auth.SocialLoginProvider
import com.elevencapital.app.ui.BrandIntro
import kotlinx.coroutines.flow.collect

class MainActivity : FragmentActivity() {
    private companion object {
        // Reopening an Activity in the same process goes straight to the phone credential.
        var introPlayedInProcess = false
    }

    private val appLock: AppLockViewModel by viewModels()
    private val elevenModel: ElevenViewModel by viewModels()
    private var activeUnlockAttempt: DeviceUnlockAttempt? = null
    private lateinit var deviceCredentialPrompt: BiometricPrompt
    private var introComplete by mutableStateOf(false)
    private var introFrameShown by mutableStateOf(false)

    private val legacyCredentialLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val attempt = activeUnlockAttempt ?: appLock.gate.activeAttempt()
            ?: return@registerForActivityResult
        activeUnlockAttempt = null
        if (result.resultCode == Activity.RESULT_OK) {
            appLock.gate.onAuthenticationSucceeded(attempt)
        } else {
            appLock.gate.onAuthenticationCancelled(attempt)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        introComplete = introPlayedInProcess
        introFrameShown = introComplete
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        deviceCredentialPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val attempt = activeUnlockAttempt ?: appLock.gate.activeAttempt() ?: return
                    activeUnlockAttempt = null
                    appLock.gate.onAuthenticationSucceeded(attempt)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val attempt = activeUnlockAttempt ?: appLock.gate.activeAttempt() ?: return
                    activeUnlockAttempt = null
                    if (errorCode == BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL) {
                        appLock.gate.onDeviceCredentialUnavailable()
                    } else {
                        appLock.gate.onAuthenticationCancelled(attempt)
                    }
                }
            },
        )
        val application = application as ElevenCapitalApplication
        val auth = application.auth
        setContent {
            LaunchedEffect(Unit) {
                // Let the first Compose frame display the pixel intro before fixture parsing.
                withFrameNanos { }
                withFrameNanos { }
                introFrameShown = true
            }
            LaunchedEffect(introFrameShown) {
                if (!introFrameShown) return@LaunchedEffect
                // This collector calls the ViewModel directly. Recomposition can pause beneath
                // Android's full-screen PIN prompt, but a verified Privy wallet must still begin
                // its balance request before the user returns to Home.
                auth.state.collect { state ->
                    val verified = state.takeIf { it.authenticated }
                    elevenModel.setWalletSession(
                        verified?.userId,
                        verified?.takeIf { it.walletsReady }?.wallets.orEmpty(),
                    )
                }
            }
            // Privy can finish restoring wallets while Android's credential prompt has stopped
            // this Activity. Keep observing the verified identity so its public balance can warm
            // behind the lock instead of waiting until after the PIN is entered.
            val authState by auth.state.collectAsState()
            val deviceUnlockState by appLock.gate.state.collectAsStateWithLifecycle()
            val verifiedUserId = authState.userId?.takeIf { authState.authenticated }
            LaunchedEffect(verifiedUserId, introComplete, introFrameShown) {
                appLock.gate.onSessionChanged(verifiedUserId)
                if (verifiedUserId != null && introComplete && introFrameShown) {
                    // The same Activity can return from Home without recomposing this effect.
                    // Observe every foreground transition so a cancelled PIN prompt is offered
                    // again only after the user has actually left and reopened the app.
                    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        requestDeviceUnlock()
                    }
                }
            }
            val historyRevision by application.transferJournal.revision.collectAsStateWithLifecycle()
            val verifiedHistoryUser = verifiedUserId?.takeIf { deviceUnlockState.contentAllowed }
            val transferHistory = remember(historyRevision, verifiedHistoryUser) {
                verifiedHistoryUser?.let(application.transferJournal::recordsForVerifiedUser).orEmpty()
            }
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                if (introFrameShown) {
                    ElevenApp(
                        model = elevenModel,
                        // Keep the explicit offline design fixture, while allowing Privy to operate
                        // independently of whether a market-data endpoint was compiled into the APK.
                        authState = authState.takeIf { it.configured || BuildConfig.LIVE_MARKET_DATA },
                        deviceUnlockState = deviceUnlockState,
                        onUnlock = { requestDeviceUnlock(userRequested = true) },
                        onOpenSecuritySettings = ::openDeviceSecuritySettings,
                        onSignIn = { auth.signIn(this@MainActivity, SocialLoginProvider.GOOGLE) },
                        onLogout = auth::logout,
                        onPrepareTransfer = { draft ->
                            requireNotNull(application.transfers) { "Transfer backend is unavailable." }.prepare(draft)
                        },
                        onSubmitTransfer = { prepared ->
                            requireNotNull(application.transfers) { "Transfer backend is unavailable." }.submit(prepared)
                        },
                        onCheckTransferStatus = { prepared, submission ->
                            requireNotNull(application.transfers) { "Transfer backend is unavailable." }
                                .status(prepared, submission)
                        },
                        transferHistory = transferHistory,
                        transferHistoryStorageHealthy = application.transferJournal.storageHealthy,
                    )
                }
                if (!introComplete) BrandIntro(onFinished = {
                    introPlayedInProcess = true
                    introComplete = true
                })
            }
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        syncLockToSession()
        // On later launcher entries the ViewModel already owns this verified Privy wallet. Its
        // bounded balance request can start before the full-screen device credential appears.
        if (introFrameShown && appLock.gate.state.value.phase != DeviceUnlockPhase.UNLOCKED) {
            val verified = (application as ElevenCapitalApplication).auth.state.value
                .takeIf { it.authenticated && it.walletsReady }
            if (verified != null) {
                elevenModel.setWalletSession(verified.userId, verified.wallets)
                elevenModel.ensureWalletWarmup()
            }
        }
        if (isDeviceSecure() && appLock.gate.state.value.phase == DeviceUnlockPhase.DEVICE_LOCK_REQUIRED) {
            appLock.gate.onDeviceCredentialAvailable()
        }
        if (introComplete && introFrameShown) requestDeviceUnlock()
    }

    override fun onStop() {
        if (!isChangingConfigurations) appLock.gate.onAppBackgrounded()
        super.onStop()
    }

    private fun syncLockToSession() {
        val authState = (application as ElevenCapitalApplication).auth.state.value
        appLock.gate.onSessionChanged(authState.userId?.takeIf { authState.authenticated })
    }

    private fun requestDeviceUnlock(userRequested: Boolean = false) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        syncLockToSession()
        if (!isDeviceSecure()) {
            appLock.gate.onDeviceCredentialUnavailable()
            return
        }
        if (appLock.gate.state.value.phase == DeviceUnlockPhase.DEVICE_LOCK_REQUIRED) {
            appLock.gate.onDeviceCredentialAvailable()
        }
        val attempt = appLock.gate.beginPrompt(userRequested) ?: return
        activeUnlockAttempt = attempt
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val prompt = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Unlock Eleven Capital")
                    .setSubtitle("Use your phone PIN, pattern, or password")
                    .setAllowedAuthenticators(DEVICE_CREDENTIAL)
                    .build()
                deviceCredentialPrompt.authenticate(prompt)
            } else {
                @Suppress("DEPRECATION")
                val intent = getSystemService(KeyguardManager::class.java)
                    .createConfirmDeviceCredentialIntent(
                        "Unlock Eleven Capital",
                        "Use your phone PIN, pattern, or password",
                    )
                if (intent == null) {
                    activeUnlockAttempt = null
                    appLock.gate.onDeviceCredentialUnavailable()
                } else {
                    legacyCredentialLauncher.launch(intent)
                }
            }
        } catch (_: RuntimeException) {
            activeUnlockAttempt = null
            appLock.gate.onAuthenticationCancelled(
                attempt,
                "Phone security could not open. Tap the logo to try again.",
            )
        }
    }

    private fun isDeviceSecure(): Boolean =
        getSystemService(KeyguardManager::class.java).isDeviceSecure

    private fun openDeviceSecuritySettings() {
        startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))
    }
}
