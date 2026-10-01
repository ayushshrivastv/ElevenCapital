package com.elevencapital.app.auth

import com.elevencapital.app.communitybuy.CommunityBuyException
import com.elevencapital.app.communitybuy.CommunityBuyFailure
import com.elevencapital.app.communitybuy.CommunityBuyProtocol
import com.elevencapital.app.communitybuy.PreparedCommunityBuy
import com.elevencapital.app.tokenlaunch.PreparedTokenLaunch
import com.elevencapital.app.tokenlaunch.TokenLaunchException
import com.elevencapital.app.tokenlaunch.TokenLaunchFailureCode
import com.elevencapital.app.tokenlaunch.TokenLaunchProtocol
import com.elevencapital.app.wallet.TransferPreparationException
import com.elevencapital.app.wallet.ValidatedWalletAddress
import com.elevencapital.app.wallet.WalletNetworkId
import io.privy.auth.AuthState
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Opt-in Mainnet launch/buy signer. The installable UI build excludes this source file. */
internal class PrivyRealExecutionSigner(private val auth: PrivyAuthController) {
    suspend fun freshTokenForTokenLaunch(expectedUserId: String): String = try {
        auth.freshAccessToken(expectedUserId)
    } catch (_: TransferPreparationException) {
        throw TokenLaunchException(
            TokenLaunchFailureCode.AUTHENTICATION_REQUIRED,
            "Your secure wallet session expired. Sign in again.",
            retryable = false,
            providerInvoked = false,
        )
    }

    suspend fun freshTokenForCommunityBuy(expectedUserId: String): String = try {
        auth.freshAccessToken(expectedUserId)
    } catch (_: TransferPreparationException) {
        throw CommunityBuyException(CommunityBuyFailure.AUTHENTICATION_REQUIRED,
            "Your secure wallet session expired. Sign in again.")
    }

    /** Signs one validated mainnet DBC buy; Privy's signTransaction does not broadcast it. */
    suspend fun signPreparedCommunityBuy(prepared: PreparedCommunityBuy): String =
        auth.realExecutionSigningMutex.withLock {
            val request = prepared.requireUsable(Instant.now()).request
            val privy = auth.realExecutionPrivy ?: throw CommunityBuyException(
                CommunityBuyFailure.AUTHENTICATION_REQUIRED,
                "Wallet signing is not configured on this device.")
            if (!auth.realExecutionSessionMatches(request.userId)) {
                throw CommunityBuyException(CommunityBuyFailure.AUTHENTICATION_REQUIRED,
                    "Your secure wallet session changed. Review this buy again.")
            }
            val verified = try { privy.getAuthState() } catch (_: Exception) { null }
            val user = try { privy.getUser() } catch (_: Exception) { null }
            if (verified !is AuthState.Authenticated || verified.user.id != request.userId ||
                user?.id != request.userId) {
                throw CommunityBuyException(CommunityBuyFailure.AUTHENTICATION_REQUIRED,
                    "Your secure wallet session could not be verified. Nothing was signed.")
            }
            val wallet = user.embeddedSolanaWallets.singleOrNull { candidate ->
                candidate.id == request.walletId && runCatching {
                    ValidatedWalletAddress.wallet(WalletNetworkId.SOLANA_MAINNET, candidate.address).value
                }.getOrNull() == request.walletAddress
            } ?: throw CommunityBuyException(CommunityBuyFailure.AUTHENTICATION_REQUIRED,
                "The reviewed Solana wallet is no longer available. Nothing was signed.")
            prepared.requireUsable(Instant.now())
            val signed = try {
                withContext(NonCancellable) {
                    wallet.provider.signTransaction(transaction = prepared.unsignedTransactionBytes())
                }.getOrElse {
                    throw CommunityBuyException(CommunityBuyFailure.SIGNING_FAILED,
                        "The wallet did not sign this buy. Nothing was submitted.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: CommunityBuyException) {
                throw failure
            } catch (_: Exception) {
                throw CommunityBuyException(CommunityBuyFailure.SIGNING_FAILED,
                    "The wallet could not sign this buy. Nothing was submitted.")
            }
            CommunityBuyProtocol.walletSignedTransaction(signed, prepared)
        }

    /**
     * Signs, but never broadcasts, one independently validated Meteora Mainnet preparation.
     * Privy 0.15 returns a Base64 serialized one-signature transaction; the launch backend verifies
     * that signature and merges it with its persisted mint/config signatures before broadcasting.
     */
    suspend fun signPreparedTokenLaunch(prepared: PreparedTokenLaunch): String =
        auth.realExecutionSigningMutex.withLock {
            val request = prepared.requireUsable(Instant.now()).request
            val privy = auth.realExecutionPrivy ?: throw tokenLaunchAuthFailure(
                "Wallet signing is not configured on this device.")
            if (!auth.realExecutionSessionMatches(request.userId)) {
                throw tokenLaunchAuthFailure("Your secure wallet session changed. Check this launch before retrying.")
            }
            val verified = try { privy.getAuthState() } catch (_: Exception) { null }
            if (verified !is AuthState.Authenticated || verified.user.id != request.userId) {
                throw tokenLaunchAuthFailure("Your secure wallet session changed. Check this launch before retrying.")
            }
            val user = try { privy.getUser() } catch (_: Exception) { null }
            if (user == null || user.id != request.userId) {
                throw tokenLaunchAuthFailure("Your secure wallet session could not be verified. Nothing was signed.")
            }
            val wallet = user.embeddedSolanaWallets.singleOrNull { candidate ->
                candidate.id == request.walletId && runCatching {
                    ValidatedWalletAddress.wallet(WalletNetworkId.SOLANA_MAINNET, candidate.address).value
                }.getOrNull() == request.walletAddress
            } ?: throw tokenLaunchAuthFailure(
                "The reviewed Solana wallet is no longer available. Nothing was signed.",
            )

            prepared.requireUsable(Instant.now())
            val encoded = try {
                withContext(NonCancellable) {
                    wallet.provider.signTransaction(
                        transaction = prepared.partiallySignedTransactionBytes(),
                    )
                }.getOrElse {
                    throw TokenLaunchException(
                        TokenLaunchFailureCode.SIGNING_FAILED,
                        "The wallet could not sign this launch. Check its status before trying again.",
                        retryable = false,
                        providerInvoked = true,
                    )
                }
            } catch (cancelled: CancellationException) {
                // signTransaction cannot broadcast. The coordinator releases the committed operation
                // and does not continue to /submit with a late signature.
                throw cancelled
            } catch (failure: TokenLaunchException) {
                throw failure
            } catch (_: Exception) {
                throw TokenLaunchException(
                    TokenLaunchFailureCode.SIGNING_FAILED,
                    "The wallet signature result is uncertain. Check this launch before trying again.",
                    retryable = false,
                    providerInvoked = true,
                )
            }
            try {
                TokenLaunchProtocol.walletSignedTransaction(encoded, prepared)
            } catch (_: TokenLaunchException) {
                throw TokenLaunchException(
                    TokenLaunchFailureCode.SIGNING_FAILED,
                    "The wallet returned a signature that did not match this launch. Nothing was submitted.",
                    retryable = false,
                    providerInvoked = true,
                )
            }
        }
}

private fun tokenLaunchAuthFailure(message: String) = TokenLaunchException(
    TokenLaunchFailureCode.AUTHENTICATION_REQUIRED,
    message,
    retryable = false,
    providerInvoked = false,
)
