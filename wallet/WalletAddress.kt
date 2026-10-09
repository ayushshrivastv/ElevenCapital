package com.elevencapital.app.wallet

/** A chain-bound, canonical Solana public address. */
class ValidatedWalletAddress private constructor(
    val networkId: WalletNetworkId,
    val value: String,
) {
    override fun equals(other: Any?): Boolean = other is ValidatedWalletAddress &&
        networkId == other.networkId && value == other.value
    override fun hashCode(): Int = 31 * networkId.hashCode() + value.hashCode()
    override fun toString(): String = value

    companion object {
        fun recipient(networkId: WalletNetworkId, input: String): ValidatedWalletAddress =
            validate(networkId, input, WalletTransferErrorCode.INVALID_RECIPIENT,
                "Enter a valid recipient address for the selected network.", recipient = true)

        fun wallet(networkId: WalletNetworkId, input: String): ValidatedWalletAddress =
            validate(networkId, input, WalletTransferErrorCode.INVALID_WALLET,
                "The connected wallet address is invalid for this network.", recipient = false)

        private fun validate(
            networkId: WalletNetworkId,
            input: String,
            code: WalletTransferErrorCode,
            message: String,
            recipient: Boolean,
        ): ValidatedWalletAddress {
            val canonical = canonicalSolanaPublicKey(input) ?: transferValidationFailure(code, message)
            if (recipient && canonical in SOLANA_BLOCKED_RECIPIENTS) {
                transferValidationFailure(WalletTransferErrorCode.INVALID_RECIPIENT,
                    "This address cannot receive transfers in Eleven Capital.")
            }
            return ValidatedWalletAddress(networkId, canonical)
        }
    }
}

internal val SOLANA_BLOCKED_RECIPIENTS: Set<String> = setOf(
    "11111111111111111111111111111111", // System Program
    "1nc1nerator11111111111111111111111111111111",
    "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA", // SPL Token Program
    "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb", // Token-2022 Program
    "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL", // Associated Token Program
)

private const val solanaAlphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
private val solanaDigit = IntArray(128) { -1 }.also { table ->
    solanaAlphabet.forEachIndexed { index, character -> table[character.code] = index }
}

/** Base58 decode must produce exactly one 32-byte Solana public key. */
internal fun isSolanaPublicKey(input: String): Boolean = canonicalSolanaPublicKey(input) != null

internal fun canonicalSolanaPublicKey(input: String): String? =
    decodeCanonicalBase58(input, 32, 32..44)?.let { input }

internal fun isCanonicalSolanaSignature(input: String): Boolean =
    decodeCanonicalBase58(input, 64, 80..90)?.any { it != 0.toByte() } == true

private fun decodeCanonicalBase58(input: String, byteCount: Int, validLength: IntRange): ByteArray? {
    if (input.length !in validLength || input.any { it.code >= solanaDigit.size || solanaDigit[it.code] < 0 }) return null
    val decoded = ByteArray(byteCount)
    var decodedLength = 0
    for (character in input) {
        var carry = solanaDigit[character.code]
        var index = 0
        while (index < decodedLength) {
            carry += (decoded[index].toInt() and 0xff) * 58
            decoded[index] = (carry and 0xff).toByte()
            carry = carry ushr 8
            index++
        }
        while (carry > 0) {
            if (decodedLength == decoded.size) return null
            decoded[decodedLength++] = (carry and 0xff).toByte()
            carry = carry ushr 8
        }
    }
    val leadingZeroBytes = input.takeWhile { it == '1' }.length
    if (decodedLength + leadingZeroBytes != byteCount) return null
    val output = ByteArray(byteCount)
    for (index in 0 until decodedLength) output[output.lastIndex - index] = decoded[index]
    return output.takeIf { encodeBase58(it) == input }
}

internal fun encodeBase58(input: ByteArray): String {
    require(input.isNotEmpty())
    val leadingZeroBytes = input.takeWhile { it == 0.toByte() }.size
    if (leadingZeroBytes == input.size) return "1".repeat(input.size)
    val digits = IntArray(input.size * 138 / 100 + 1)
    var digitLength = 0
    for (offset in leadingZeroBytes until input.size) {
        var carry = input[offset].toInt() and 0xff
        for (index in 0 until digitLength) {
            carry += digits[index] shl 8
            digits[index] = carry % 58
            carry /= 58
        }
        while (carry > 0) {
            digits[digitLength++] = carry % 58
            carry /= 58
        }
    }
    return buildString(leadingZeroBytes + digitLength) {
        repeat(leadingZeroBytes) { append('1') }
        for (index in digitLength - 1 downTo 0) append(solanaAlphabet[digits[index]])
    }
}
