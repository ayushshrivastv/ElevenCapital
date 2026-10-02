package com.elevencapital.app.auth

import androidx.lifecycle.ViewModel
import com.elevencapital.app.wallet.canonicalSolanaPublicKey
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A QR result that belongs to the account which opened the scanner. */
internal data class PendingCommunityTokenScan(
    val id: Long,
    val ownerUserId: String,
    val mintAddress: String,
)

/**
 * Survives the device-lock screen replacing the app's Compose subtree while the external QR
 * scanner finishes. Only a successfully parsed mint is offered, and only its original account
 * may consume it after unlocking. No wallet credential or purchase is stored here.
 */
internal class PendingCommunityTokenScanViewModel : ViewModel() {
    private val nextId = AtomicLong()
    private val mutablePending = MutableStateFlow<PendingCommunityTokenScan?>(null)
    val pending: StateFlow<PendingCommunityTokenScan?> = mutablePending.asStateFlow()

    fun offer(ownerUserId: String, mintAddress: String): Boolean {
        if (ownerUserId.isBlank() || canonicalSolanaPublicKey(mintAddress) != mintAddress) {
            return false
        }
        mutablePending.value = PendingCommunityTokenScan(nextId.incrementAndGet(), ownerUserId, mintAddress)
        return true
    }

    fun consume(scan: PendingCommunityTokenScan): Boolean = mutablePending.compareAndSet(scan, null)

    fun discardOtherOwner(userId: String) {
        val current = mutablePending.value ?: return
        if (current.ownerUserId != userId) mutablePending.compareAndSet(current, null)
    }
}
