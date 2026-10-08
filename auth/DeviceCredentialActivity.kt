package com.elevencapital.app.auth

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.elevencapital.app.ElevenCapitalApplication
import com.elevencapital.app.screens.SecureSessionScreen
import java.lang.ref.WeakReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One Activity-scoped AndroidX prompt owner for one immutable, process-local attempt. */
class DeviceCredentialActivity : FragmentActivity() {
    private var attempt: DeviceUnlockAttempt? = null
    private var started = false
    private var completed = false
    private var startedAtMillis = 0L
    private var prompt: BiometricPrompt? = null
    private val legacyLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        complete(if (result.resultCode == Activity.RESULT_OK) DeviceCredentialResult.SUCCEEDED else DeviceCredentialResult.CANCELLED)
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val boundAttempt = readAttempt(intent) ?: run { finish(); return }
        attempt = boundAttempt
        // Reattach to this Activity's retained prompt before retiring a restored, expired owner.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        complete(DeviceCredentialResult.SUCCEEDED)
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        complete(if (errorCode == BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL)
                            DeviceCredentialResult.UNAVAILABLE else DeviceCredentialResult.CANCELLED)
                    }
                })
        }
        // A restored Activity from a dead process has no permit and cannot reopen phone security.
        if (boundAttempt !in permits) { complete(DeviceCredentialResult.CANCELLED); return }
        owners[boundAttempt] = WeakReference(this)
        setResult(Activity.RESULT_CANCELED, replyIntent(boundAttempt, DeviceCredentialResult.CANCELLED))
        started = savedInstanceState?.getBoolean("started") ?: false
        startedAtMillis = savedInstanceState?.getLong("startedAt") ?: SystemClock.elapsedRealtime()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent { SecureSessionScreen() }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { complete(DeviceCredentialResult.CANCELLED) }
        })
        val auth = (application as ElevenCapitalApplication).auth
        lifecycleScope.launch {
            auth.state.collect { state ->
                if (!state.authenticated || state.userId != boundAttempt.userId) complete(DeviceCredentialResult.CANCELLED)
            }
        }
        lifecycleScope.launch {
            val watchdog = DevicePromptWatchdog(startedAtMillis)
            while (isActive && !completed) {
                val visible = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && window.decorView.hasWindowFocus()
                if (watchdog.missingPrompt(SystemClock.elapsedRealtime(), visible)) {
                    complete(DeviceCredentialResult.CANCELLED)
                    return@launch
                }
                delay(250)
            }
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        window.decorView.post(::startPromptIfReady)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.post(::startPromptIfReady)
    }

    private fun startPromptIfReady() {
        val boundAttempt = attempt ?: return
        if (completed || started || boundAttempt !in permits ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !window.decorView.hasWindowFocus() ||
            supportFragmentManager.isStateSaved) return
        val auth = (application as ElevenCapitalApplication).auth.state.value
        if (!auth.authenticated || auth.userId != boundAttempt.userId) { complete(DeviceCredentialResult.CANCELLED); return }
        if (!getSystemService(KeyguardManager::class.java).isDeviceSecure) { complete(DeviceCredentialResult.UNAVAILABLE); return }
        started = true
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                requireNotNull(prompt).authenticate(BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Unlock Eleven Capital")
                    .setSubtitle("Use your phone PIN, pattern, or password")
                    .setAllowedAuthenticators(DEVICE_CREDENTIAL).build())
            } else {
                @Suppress("DEPRECATION")
                val credential = getSystemService(KeyguardManager::class.java)
                    .createConfirmDeviceCredentialIntent("Unlock Eleven Capital", "Use your phone PIN, pattern, or password")
                if (credential == null) complete(DeviceCredentialResult.UNAVAILABLE) else legacyLauncher.launch(credential)
            }
        } catch (_: RuntimeException) { complete(DeviceCredentialResult.CANCELLED) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("started", started)
        outState.putLong("startedAt", startedAtMillis)
        super.onSaveInstanceState(outState)
    }

    private fun complete(result: DeviceCredentialResult) {
        if (completed || isDestroyed || isChangingConfigurations) return
        completed = true
        val boundAttempt = attempt
        if (boundAttempt != null) {
            permits.remove(boundAttempt)
            if (owners[boundAttempt]?.get() === this) owners.remove(boundAttempt)
            setResult(if (result == DeviceCredentialResult.SUCCEEDED) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                replyIntent(boundAttempt, result))
        }
        // Do not let cancellation callbacks change a terminal result.
        try { prompt?.cancelAuthentication() } catch (_: RuntimeException) {
            // A platform cancellation failure must not strand this terminal credential owner.
        } finally { finish() }
    }

    override fun onDestroy() {
        attempt?.let { boundAttempt ->
            if (owners[boundAttempt]?.get() === this) owners.remove(boundAttempt)
            if (isFinishing) permits.remove(boundAttempt)
        }
        super.onDestroy()
    }

    companion object {
        // Main-thread only; no credential or personal data is persisted across process death.
        private val permits = mutableSetOf<DeviceUnlockAttempt>()
        private val owners = mutableMapOf<DeviceUnlockAttempt, WeakReference<DeviceCredentialActivity>>()
        private const val USER = "eleven.credential.user"
        private const val ID = "eleven.credential.id"
        private const val EPOCH = "eleven.credential.epoch"
        private const val RESULT = "eleven.credential.result"

        internal fun allow(attempt: DeviceUnlockAttempt) { permits.add(attempt) }

        internal fun retire(attempt: DeviceUnlockAttempt) {
            permits.remove(attempt)
            owners.remove(attempt)?.get()?.complete(DeviceCredentialResult.CANCELLED)
        }

        internal fun intent(context: Context, attempt: DeviceUnlockAttempt): Intent =
            putAttempt(Intent(context, DeviceCredentialActivity::class.java), attempt)

        internal fun readReply(data: Intent?): DeviceCredentialReply? {
            val attempt = readAttempt(data) ?: return null
            val result = data?.getStringExtra(RESULT)?.let { value -> DeviceCredentialResult.entries.firstOrNull { it.name == value } }
                ?: return null
            return DeviceCredentialReply(attempt, result)
        }

        private fun readAttempt(data: Intent?): DeviceUnlockAttempt? {
            val payload = data ?: return null
            val user = payload.getStringExtra(USER)?.takeIf(String::isNotBlank) ?: return null
            val id = payload.getLongExtra(ID, 0L).takeIf { it > 0 } ?: return null
            val epoch = payload.getStringExtra(EPOCH)?.takeIf(String::isNotBlank) ?: return null
            return DeviceUnlockAttempt(user, id, epoch)
        }

        private fun replyIntent(attempt: DeviceUnlockAttempt, result: DeviceCredentialResult): Intent =
            putAttempt(Intent(), attempt).putExtra(RESULT, result.name)

        private fun putAttempt(intent: Intent, attempt: DeviceUnlockAttempt): Intent =
            intent.putExtra(USER, attempt.userId).putExtra(ID, attempt.id).putExtra(EPOCH, attempt.epoch)
    }
}
