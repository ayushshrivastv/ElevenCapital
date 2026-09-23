package com.elevencapital.app.auth

import kotlinx.coroutines.CancellationException

internal enum class WalletProvisioningStage { REFRESH, CREATE }

internal class WalletProvisioningFailure(
    val stage: WalletProvisioningStage,
    val chain: WalletChain?,
    cause: Exception,
) : Exception("Wallet setup step failed", cause)

private suspend fun <T> walletStep(stage: WalletProvisioningStage, chain: WalletChain?, action: suspend () -> T): T =
    try {
        action()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        throw WalletProvisioningFailure(stage, chain, failure)
    }

/** The SDK adapter supplies account-scoped reads and guarded, non-additional wallet creation. */
internal interface WalletAccountSession {
    val id: String
    val wallets: List<UserWallet>
    suspend fun refresh()
    suspend fun create(chain: WalletChain): UserWallet
}

/**
 * Refresh once per reconciliation attempt, including retries after an ambiguous creation response.
 * Successful SDK creation already refreshes the linked wallet list; read that list again for each
 * chain without another account refresh. The adapter always passes allowAdditional=false.
 */
internal suspend fun provisionWallets(
    account: WalletAccountSession,
    isCurrentAccount: suspend (String) -> Boolean,
    onWallets: (List<UserWallet>) -> Unit,
) {
    suspend fun requireCurrent() {
        if (!isCurrentAccount(account.id)) throw CancellationException("Authenticated account changed")
    }
    requireCurrent()
    walletStep(WalletProvisioningStage.REFRESH, null) { account.refresh() }
    requireCurrent()
    onWallets(account.wallets)
    for (chain in WalletChain.entries) {
        requireCurrent()
        if (account.wallets.any { it.chain == chain }) continue
        val created = walletStep(WalletProvisioningStage.CREATE, chain) { account.create(chain) }
        requireCurrent()
        onWallets((account.wallets + created).distinctBy { it.chain to it.address })
    }
}
