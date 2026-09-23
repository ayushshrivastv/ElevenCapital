package com.elevencapital.app.wallet

import java.util.UUID

@JvmInline
value class TransferOperationId private constructor(val value: String) {
    init { require(operationIdPattern.matches(value)) }

    companion object {
        private val operationIdPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        fun create(): TransferOperationId = TransferOperationId(UUID.randomUUID().toString())
        internal fun fromPersisted(value: String): TransferOperationId = TransferOperationId(value)
    }
}

/** Identity captured before review. A later account/wallet change must invalidate this draft. */
class TransferSessionIdentity private constructor(
    val userId: String,
    val walletId: String,
    val walletAddress: ValidatedWalletAddress,
) {
    val networkId: WalletNetworkId get() = walletAddress.networkId

    companion object {
        fun create(
            userId: String,
            walletId: String,
            networkId: WalletNetworkId,
            walletAddress: String,
        ): TransferSessionIdentity {
            if (!safeIdentity(userId) || !safeIdentity(walletId)) {
                transferValidationFailure(WalletTransferErrorCode.INVALID_SESSION,
                    "The secure wallet session is unavailable. Sign in again.")
            }
            return TransferSessionIdentity(userId, walletId, ValidatedWalletAddress.wallet(networkId, walletAddress))
        }

        private fun safeIdentity(value: String): Boolean =
            value.length in 1..200 && value.none(Char::isWhitespace) && value.none(Char::isISOControl)
    }
}

/** Validated preparation state. It carries no signing material and cannot broadcast a transaction. */
class TransferDraft private constructor(
    val operationId: TransferOperationId,
    val session: TransferSessionIdentity,
    val asset: WalletAsset,
    val recipient: ValidatedWalletAddress,
    val amount: TransferAmount,
    val createdAtEpochMillis: Long,
) {
    fun review(): TransferReviewSnapshot = TransferReviewSnapshot(
        operationId = operationId,
        userId = session.userId,
        walletId = session.walletId,
        sender = session.walletAddress,
        recipient = recipient,
        assetId = asset.id,
        assetSymbol = asset.symbol,
        networkId = asset.network.id,
        networkCaip2 = asset.network.caip2,
        contractOrMint = asset.contractOrMint,
        displayAmount = amount.displayAmount,
        baseUnits = amount.baseUnits.toString(),
        decimals = amount.decimals,
        createdAtEpochMillis = createdAtEpochMillis,
    )

    companion object {
        fun create(
            session: TransferSessionIdentity,
            assetId: WalletAssetId,
            recipient: String,
            amount: String,
            createdAtEpochMillis: Long = System.currentTimeMillis(),
        ): TransferDraft {
            if (createdAtEpochMillis < 0) {
                transferValidationFailure(WalletTransferErrorCode.INVALID_SESSION,
                    "The secure wallet session is unavailable. Sign in again.")
            }
            val asset = WalletAssetRegistry.asset(assetId)
            if (session.networkId != asset.network.id) {
                transferValidationFailure(WalletTransferErrorCode.WRONG_NETWORK,
                    "Select a wallet on the same network as this asset.")
            }
            val validatedRecipient = ValidatedWalletAddress.recipient(asset.network.id, recipient)
            if (validatedRecipient == session.walletAddress) {
                transferValidationFailure(WalletTransferErrorCode.SAME_SENDER_AND_RECIPIENT,
                    "Choose a recipient address different from the sending wallet.")
            }
            return TransferDraft(
                operationId = TransferOperationId.create(),
                session = session,
                asset = asset,
                recipient = validatedRecipient,
                amount = TransferAmount.parse(amount, asset),
                createdAtEpochMillis = createdAtEpochMillis,
            )
        }
    }
}

/** Immutable values shown at confirmation time. Rebuilding a draft produces another operation ID. */
class TransferReviewSnapshot internal constructor(
    val operationId: TransferOperationId,
    val userId: String,
    val walletId: String,
    val sender: ValidatedWalletAddress,
    val recipient: ValidatedWalletAddress,
    val assetId: WalletAssetId,
    val assetSymbol: String,
    val networkId: WalletNetworkId,
    val networkCaip2: String,
    val contractOrMint: String?,
    val displayAmount: String,
    val baseUnits: String,
    val decimals: Int,
    val createdAtEpochMillis: Long,
)
