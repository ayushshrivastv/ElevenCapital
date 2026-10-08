package com.elevencapital.app.auth

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.fragment.app.Fragment

enum class DeviceCredentialResult { SUCCEEDED, CANCELLED, UNAVAILABLE }

interface DeviceCredentialOwner {
    fun isCurrentDeviceAttempt(attempt: DeviceUnlockAttempt): Boolean
    fun onDeviceCredentialResult(attempt: DeviceUnlockAttempt, result: DeviceCredentialResult)
}

/** Each launcher owns an immutable result identity; the prompt has its own Activity owner. */
class DeviceCredentialFragment : Fragment() {
    val attempt: DeviceUnlockAttempt get() = requireArguments().let {
        DeviceUnlockAttempt(requireNotNull(it.getString("user")), it.getLong("attempt"), requireNotNull(it.getString("epoch")))
    }
    val startedAtMillis: Long get() = requireArguments().getLong("startedAt")
    private var started = false
    private var retired = false
    private val credentialLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val reply = DeviceCredentialActivity.readReply(result.data)
        deliver(credentialReplyForAttempt(attempt, reply, result.resultCode == Activity.RESULT_OK))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        started = savedInstanceState?.getBoolean("started") ?: false
    }

    override fun onResume() {
        super.onResume()
        if (retired || started || (activity as? DeviceCredentialOwner)?.isCurrentDeviceAttempt(attempt) != true) return
        started = true
        try {
            DeviceCredentialActivity.allow(attempt)
            // Both activities show the same centered mark. A sliding Activity transition makes
            // that mark appear to jump sideways before Android presents its credential UI.
            credentialLauncher.launch(
                DeviceCredentialActivity.intent(requireContext(), attempt),
                ActivityOptionsCompat.makeCustomAnimation(requireContext(), 0, 0),
            )
        } catch (_: RuntimeException) { deliver(DeviceCredentialResult.CANCELLED) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("started", started)
        super.onSaveInstanceState(outState)
    }

    fun cancelPrompt() {
        if (retired) return
        // Retire the launcher before closing its Activity, which may return a result immediately.
        retired = true
        DeviceCredentialActivity.retire(attempt)
    }

    private fun deliver(result: DeviceCredentialResult) {
        if (retired) return
        (activity as? DeviceCredentialOwner)?.onDeviceCredentialResult(attempt, result)
    }

    companion object {
        fun forAttempt(attempt: DeviceUnlockAttempt) = DeviceCredentialFragment().apply {
            arguments = Bundle().apply {
                putString("user", attempt.userId); putLong("attempt", attempt.id); putString("epoch", attempt.epoch)
                putLong("startedAt", SystemClock.elapsedRealtime())
            }
        }
        fun tag(attempt: DeviceUnlockAttempt): String = "eleven-device-${attempt.epoch}-${attempt.id}"
    }
}
