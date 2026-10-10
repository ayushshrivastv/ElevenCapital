package com.elevencapital.app

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.elevencapital.app.auth.AppLockViewModel
import com.elevencapital.app.auth.DeviceUnlockAttempt
import com.elevencapital.app.auth.DeviceUnlockPhase
import com.elevencapital.app.auth.DeviceCredentialFragment
import com.elevencapital.app.auth.DeviceCredentialOwner
import com.elevencapital.app.auth.DeviceCredentialResult
import com.elevencapital.app.auth.DevicePromptWatchdog
import com.elevencapital.app.auth.PendingCommunityTokenScanViewModel
import com.elevencapital.app.auth.introDeadlineReached
import com.elevencapital.app.auth.SocialLoginProvider
import com.elevencapital.app.data.PredictionPythFeed
import com.elevencapital.app.data.PredictionBtcFeed
import com.elevencapital.app.ui.BrandIntro
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity(), DeviceCredentialOwner {
    private companion object {
        // Reopening an Activity in the same process goes straight to the phone credential.
        var introPlayedInProcess = false
    }

    private val appLock: AppLockViewModel by viewModels()
    private val elevenModel: ElevenViewModel by viewModels()
    private val pendingCommunityScan: PendingCommunityTokenScanViewModel by viewModels()
    private var promptRecoveryJob: Job? = null
    private var introComplete by mutableStateOf(false)
    private var introFrameShown by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep BTC market prices and wallet quotes warm across tabs.
        if (BuildConfig.LIVE_MARKET_DATA) {
            lifecycleScope.launch {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    PredictionPythFeed.startForeground()
                    PredictionBtcFeed.startForeground()
                    try { awaitCancellation() } finally {
                        PredictionBtcFeed.stopForeground()
                        PredictionPythFeed.stopForeground()
                    }
                }
            }
        }
        introComplete = introPlayedInProcess
        introFrameShown = introComplete
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val introStartedAt = SystemClock.elapsedRealtime()
        lifecycleScope.launch {
            delay(3_500)
            if (!introComplete && introDeadlineReached(introStartedAt, SystemClock.elapsedRealtime())) {
                // Keep the static opening artwork from trapping the user if frames stall.
                introFrameShown = true
                finishIntro()
            }
        }
        val application = application as ElevenCapitalApplication
        val auth = application.auth
        setContent {
            LaunchedEffect(Unit) {
                // Prepare the locked app surface behind the static launcher artwork.
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
            val pendingScan by pendingCommunityScan.pending.collectAsStateWithLifecycle()
            val verifiedUserId = authState.userId?.takeIf { authState.authenticated }
            LaunchedEffect(verifiedUserId) {
                if (verifiedUserId != null) pendingCommunityScan.discardOtherOwner(verifiedUserId)
            }
            LaunchedEffect(verifiedUserId, introComplete, introFrameShown) {
                syncLockToSession()
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
            val confirmedTokenLaunches = remember(verifiedHistoryUser) {
                verifiedHistoryUser?.let(application.tokenLaunchReceipts::receiptsForVerifiedUser).orEmpty()
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
                        onRetrySessionRestore = auth::retrySessionRestore,
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
                        // Launch and creator-token Buy remain local UI flows in this APK.
                        // Real execution code is retained behind the opt-in build property.
                        confirmedTokenLaunches = confirmedTokenLaunches,
                        transferHistory = transferHistory,
                        transferHistoryStorageHealthy = application.transferJournal.storageHealthy,
                        pendingCommunityTokenScan = pendingScan?.takeIf { scan ->
                            scan.ownerUserId == verifiedUserId &&
                                deviceUnlockState.phase == DeviceUnlockPhase.UNLOCKED &&
                                deviceUnlockState.userId == verifiedUserId
                        },
                        onCommunityTokenScanned = { mint ->
                            val owner = verifiedUserId
                            // The verified account that opened the scanner owns this result.
                            // Auth may be briefly restoring while Play services returns; delivery
                            // still requires a verified matching account after device unlock.
                            if (owner != null) pendingCommunityScan.offer(owner, mint)
                        },
                        onPendingCommunityTokenConsumed = { scan -> pendingCommunityScan.consume(scan) },
                    )
                }
                if (!introComplete) BrandIntro(onFinished = ::finishIntro)
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
        appLock.gate.activeAttempt()?.let(::watchDevicePrompt)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && introComplete && introFrameShown) {
            window.decorView.post { requestDeviceUnlock() }
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            appLock.gate.onAppBackgrounded()
        }
        super.onStop()
    }

    private fun syncLockToSession() {
        val authState = (application as ElevenCapitalApplication).auth.state.value
        appLock.gate.onSessionChanged(authState.userId?.takeIf { authState.authenticated })
        val current = appLock.gate.activeAttempt()
        supportFragmentManager.fragments.filterIsInstance<DeviceCredentialFragment>()
            .filter { it.attempt != current }.forEach { retireDevicePrompt(it.attempt) }
    }

    private fun finishIntro() {
        introPlayedInProcess = true
        introComplete = true
        if (introFrameShown) window.decorView.post { requestDeviceUnlock() }
    }

    private fun requestDeviceUnlock(userRequested: Boolean = false) {
        if (!introComplete || !introFrameShown || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            !window.decorView.hasWindowFocus() || supportFragmentManager.isStateSaved) return
        syncLockToSession()
        if (appLock.gate.state.value.userId == null) return
        if (!isDeviceSecure()) {
            appLock.gate.onDeviceCredentialUnavailable()
            return
        }
        if (appLock.gate.state.value.phase == DeviceUnlockPhase.DEVICE_LOCK_REQUIRED) {
            appLock.gate.onDeviceCredentialAvailable()
        }
        val attempt = appLock.gate.beginPrompt(userRequested) ?: return
        try {
            supportFragmentManager.beginTransaction()
                .add(DeviceCredentialFragment.forAttempt(attempt), DeviceCredentialFragment.tag(attempt)).commitNow()
            watchDevicePrompt(attempt)
        } catch (_: RuntimeException) {
            appLock.gate.onAuthenticationCancelled(attempt, "Phone security could not open. Try unlocking again.")
            retireDevicePrompt(attempt)
        }
    }

    override fun isCurrentDeviceAttempt(attempt: DeviceUnlockAttempt): Boolean {
        syncLockToSession()
        return appLock.gate.activeAttempt() == attempt
    }

    override fun onDeviceCredentialResult(attempt: DeviceUnlockAttempt, result: DeviceCredentialResult) {
        if (!isCurrentDeviceAttempt(attempt)) { retireDevicePrompt(attempt); return }
        when (result) {
            DeviceCredentialResult.SUCCEEDED -> appLock.gate.onAuthenticationSucceeded(attempt)
            DeviceCredentialResult.CANCELLED -> appLock.gate.onAuthenticationCancelled(attempt)
            DeviceCredentialResult.UNAVAILABLE -> appLock.gate.onDeviceCredentialUnavailable()
        }
        promptRecoveryJob?.cancel()
        retireDevicePrompt(attempt)
    }

    private fun watchDevicePrompt(attempt: DeviceUnlockAttempt) {
        promptRecoveryJob?.cancel()
        val fragment = supportFragmentManager.findFragmentByTag(DeviceCredentialFragment.tag(attempt)) as? DeviceCredentialFragment
        val watchdog = DevicePromptWatchdog(fragment?.startedAtMillis ?: SystemClock.elapsedRealtime())
        promptRecoveryJob = lifecycleScope.launch {
            while (isActive && appLock.gate.activeAttempt() == attempt) {
                val visible = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && window.decorView.hasWindowFocus()
                if (watchdog.missingPrompt(SystemClock.elapsedRealtime(), visible)) {
                    // Invalidate this exact attempt before platform cancellation can deliver a late result.
                    appLock.gate.onAuthenticationCancelled(attempt, "Phone security did not open. Try unlocking again.")
                    retireDevicePrompt(attempt)
                    return@launch
                }
                delay(250)
            }
        }
    }

    private fun retireDevicePrompt(attempt: DeviceUnlockAttempt) {
        val fragment = supportFragmentManager.findFragmentByTag(DeviceCredentialFragment.tag(attempt)) as? DeviceCredentialFragment ?: return
        fragment.cancelPrompt()
        // A prompt callback can arrive during a Fragment transaction or while state is saved.
        window.decorView.post {
            if (!isDestroyed && fragment.isAdded) supportFragmentManager.beginTransaction().remove(fragment).commitAllowingStateLoss()
        }
    }

    private fun isDeviceSecure(): Boolean =
        getSystemService(KeyguardManager::class.java).isDeviceSecure

    private fun openDeviceSecuritySettings() {
        startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))
    }
}
