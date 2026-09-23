package com.elevencapital.app.wallet

import java.nio.charset.StandardCharsets
import java.util.Locale

/** A chain-bound, canonical public address. */
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
            val canonical = when (networkId) {
                WalletNetworkId.ETHEREUM_MAINNET -> canonicalEthereumAddress(input)
                WalletNetworkId.SOLANA_MAINNET -> canonicalSolanaPublicKey(input)
            } ?: transferValidationFailure(code, message)
            if (recipient && isBlockedRecipient(networkId, canonical)) {
                transferValidationFailure(WalletTransferErrorCode.INVALID_RECIPIENT,
                    "This address cannot receive transfers in Eleven Capital.")
            }
            return ValidatedWalletAddress(networkId, canonical)
        }
    }
}

internal const val ETHEREUM_ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"
internal const val ETHEREUM_DEAD_ADDRESS = "0x000000000000000000000000000000000000dEaD"
internal val SOLANA_BLOCKED_RECIPIENTS: Set<String> = setOf(
    "11111111111111111111111111111111", // System Program
    "1nc1nerator11111111111111111111111111111111",
    "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA", // SPL Token Program
    "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb", // Token-2022 Program
    "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL", // Associated Token Program
)

private val ethereumAddressPattern = Regex("0x[0-9a-fA-F]{40}")
private val solanaAlphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
private val solanaDigit = IntArray(128) { -1 }.also { table ->
    solanaAlphabet.forEachIndexed { index, character -> table[character.code] = index }
}

/** Lowercase and uppercase EVM addresses are accepted; mixed case must be a valid EIP-55 checksum. */
internal fun canonicalEthereumAddress(input: String): String? {
    if (!ethereumAddressPattern.matches(input)) return null
    val body = input.substring(2)
    val lower = body.lowercase(Locale.ROOT)
    val upper = body.uppercase(Locale.ROOT)
    val hasLetters = body.any { it in 'a'..'f' || it in 'A'..'F' }
    val isUniformCase = !hasLetters || body == lower || body == upper
    val checksum = eip55Checksum(lower)
    if (!isUniformCase && input != checksum) return null
    return checksum
}

private fun isBlockedRecipient(networkId: WalletNetworkId, canonical: String): Boolean = when (networkId) {
    WalletNetworkId.ETHEREUM_MAINNET -> canonical.equals(ETHEREUM_ZERO_ADDRESS, ignoreCase = true) ||
        canonical.equals(ETHEREUM_DEAD_ADDRESS, ignoreCase = true)
    WalletNetworkId.SOLANA_MAINNET -> canonical in SOLANA_BLOCKED_RECIPIENTS
}

private fun eip55Checksum(lowercaseHex: String): String {
    // Privy carries Bouncy Castle only on its runtime variant. Keeping this implementation local
    // avoids compiling against an undeclared transitive dependency that can disappear on upgrade.
    val digest = keccak256(lowercaseHex.toByteArray(StandardCharsets.US_ASCII))
    val result = StringBuilder(42).append("0x")
    lowercaseHex.forEachIndexed { index, character ->
        if (character in 'a'..'f') {
            val hashNibble = if (index % 2 == 0) (digest[index / 2].toInt() ushr 4) and 0x0f
            else digest[index / 2].toInt() and 0x0f
            result.append(if (hashNibble >= 8) character.uppercaseChar() else character)
        } else result.append(character)
    }
    return result.toString()
}

private val keccakRotations = intArrayOf(
    0, 1, 62, 28, 27,
    36, 44, 6, 55, 20,
    3, 10, 43, 25, 39,
    41, 45, 15, 21, 8,
    18, 2, 61, 56, 14,
)
private val keccakRoundConstants = longArrayOf(
    0x0000000000000001uL.toLong(), 0x0000000000008082uL.toLong(), 0x800000000000808auL.toLong(),
    0x8000000080008000uL.toLong(), 0x000000000000808buL.toLong(), 0x0000000080000001uL.toLong(),
    0x8000000080008081uL.toLong(), 0x8000000000008009uL.toLong(), 0x000000000000008auL.toLong(),
    0x0000000000000088uL.toLong(), 0x0000000080008009uL.toLong(), 0x000000008000000auL.toLong(),
    0x000000008000808buL.toLong(), 0x800000000000008buL.toLong(), 0x8000000000008089uL.toLong(),
    0x8000000000008003uL.toLong(), 0x8000000000008002uL.toLong(), 0x8000000000000080uL.toLong(),
    0x000000000000800auL.toLong(), 0x800000008000000auL.toLong(), 0x8000000080008081uL.toLong(),
    0x8000000000008080uL.toLong(), 0x0000000080000001uL.toLong(), 0x8000000080008008uL.toLong(),
)

/** Ethereum uses original Keccak-256 padding (0x01), not FIPS SHA3-256 padding (0x06). */
private fun keccak256(input: ByteArray): ByteArray {
    val state = LongArray(25)
    val rateBytes = 136
    var offset = 0
    while (input.size - offset >= rateBytes) {
        absorbKeccakBlock(state, input, offset, rateBytes)
        keccakPermutation(state)
        offset += rateBytes
    }
    val finalBlock = ByteArray(rateBytes)
    input.copyInto(finalBlock, endIndex = input.size, destinationOffset = 0, startIndex = offset)
    finalBlock[input.size - offset] = 0x01
    finalBlock[rateBytes - 1] = (finalBlock[rateBytes - 1].toInt() or 0x80).toByte()
    absorbKeccakBlock(state, finalBlock, 0, rateBytes)
    keccakPermutation(state)
    return ByteArray(32) { index ->
        ((state[index / 8] ushr ((index % 8) * 8)) and 0xff).toByte()
    }
}

private fun absorbKeccakBlock(state: LongArray, input: ByteArray, offset: Int, size: Int) {
    for (index in 0 until size) {
        state[index / 8] = state[index / 8] xor
            ((input[offset + index].toLong() and 0xffL) shl ((index % 8) * 8))
    }
}

private fun keccakPermutation(state: LongArray) {
    val column = LongArray(5)
    val rotated = LongArray(25)
    for (roundConstant in keccakRoundConstants) {
        for (x in 0 until 5) {
            column[x] = state[x] xor state[x + 5] xor state[x + 10] xor state[x + 15] xor state[x + 20]
        }
        for (x in 0 until 5) {
            val delta = column[(x + 4) % 5] xor java.lang.Long.rotateLeft(column[(x + 1) % 5], 1)
            for (y in 0 until 5) state[x + 5 * y] = state[x + 5 * y] xor delta
        }
        for (x in 0 until 5) for (y in 0 until 5) {
            rotated[y + 5 * ((2 * x + 3 * y) % 5)] =
                java.lang.Long.rotateLeft(state[x + 5 * y], keccakRotations[x + 5 * y])
        }
        for (x in 0 until 5) for (y in 0 until 5) {
            state[x + 5 * y] = rotated[x + 5 * y] xor
                (rotated[(x + 1) % 5 + 5 * y].inv() and rotated[(x + 2) % 5 + 5 * y])
        }
        state[0] = state[0] xor roundConstant
    }
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
