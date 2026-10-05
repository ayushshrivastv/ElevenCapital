package com.elevencapital.app.screens

import android.content.Context
import java.security.MessageDigest

/** Durable, account-scoped preview accounting. No wallet key or token is stored here. */
internal class PredictionLedgerStore(context: Context, private val preview: Boolean = false) {
    private val preferences = context.applicationContext.getSharedPreferences(
        if (preview) "eleven-prediction-ledger-preview" else "eleven-prediction-ledger",
        Context.MODE_PRIVATE)

    private fun key(userId: String, walletId: String): String {
        require(userId.isNotBlank() && walletId.isNotBlank())
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$userId:$walletId".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun load(userId: String, walletId: String): PredictionWalletLedger =
        preferences.getString(key(userId, walletId), null)?.let(PredictionWalletLedger::fromJson)
            ?: PredictionWalletLedger.empty()

    /** Commit before updating UI state so a process exit cannot erase a stake debit. */
    fun save(userId: String, walletId: String, ledger: PredictionWalletLedger): Boolean =
        preferences.edit().putString(key(userId, walletId), ledger.toJson()).commit()

    /** Reset only the local preview ledger; a verified transfer ledger is never cleared here. */
    fun clear(userId: String, walletId: String): Boolean =
        preview && preferences.edit().remove(key(userId, walletId)).commit()
}
