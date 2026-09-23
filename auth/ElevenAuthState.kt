package com.elevencapital.app.auth

enum class AuthPhase { UNCONFIGURED, RESTORING, SIGNED_OUT, SIGNING_IN, AUTHENTICATED, SESSION_UNVERIFIED }
enum class SocialLoginProvider { GOOGLE, APPLE }
enum class WalletChain { SOLANA, ETHEREUM }

/** Public SDK identity only. No signing keys, recovery material, or access tokens leave Privy. */
data class UserWallet(
    val chain: WalletChain,
    val address: String,
    val walletId: String = "",
) {
    init {
        require(address.isNotBlank())
        require(walletId.none(Char::isWhitespace) && walletId.none(Char::isISOControl))
    }
}

data class ElevenAuthState(
    val configured: Boolean,
    val phase: AuthPhase,
    val userId: String? = null,
    val displayName: String? = null,
    val wallets: List<UserWallet> = emptyList(),
    val provisioning: Boolean = false,
    val error: String? = null,
    // The pinned native Privy SDK has no Apple OAuthProvider. Do not simulate a working login.
    val appleAvailable: Boolean = false,
) {
    val authenticated: Boolean get() = phase == AuthPhase.AUTHENTICATED && !userId.isNullOrBlank()
    val walletsReady: Boolean get() = authenticated && WalletChain.entries.all { chain -> wallets.any { it.chain == chain } }
}
