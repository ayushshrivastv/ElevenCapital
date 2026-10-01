package com.elevencapital.app.communitybuy

import com.elevencapital.app.tokenlaunch.WRAPPED_SOL_MINT
import com.elevencapital.app.wallet.canonicalSolanaPublicKey
import com.elevencapital.app.wallet.encodeBase58
import java.math.BigInteger
import java.security.MessageDigest

/** Small, dependency-free PDA derivation for independent transaction review on Android. */
internal object SolanaBuyAddresses {
    const val SYSTEM = "11111111111111111111111111111111"
    const val TOKEN = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    const val ATA = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
    const val DBC_POOL_AUTHORITY = "FhVo3mqL8PW5pH5U2CN4XE33DokiyZnUwuGpH2hmHLuM"
    const val DBC_EVENT_AUTHORITY = "8Ks12pbrD6PXxfty1hVQiE9sc289zgU1zHkvXhrSdriF"
    const val METADATA_PROGRAM = "metaqbxxUerdq28cj1RbAWkYQm3ybzjb6a8bt518x1s"
    const val SYSVAR_INSTRUCTIONS = "Sysvar1nstructions1111111111111111111111111"

    fun associatedToken(mint: String, owner: String): String = pda(
        listOf(decode(owner), decode(TOKEN), decode(mint)), ATA,
    )

    fun dbcPool(mint: String, config: String): String {
        val base = decode(mint)
        val quote = decode(WRAPPED_SOL_MINT)
        val order = if (compareUnsigned(quote, base) > 0) listOf(quote, base) else listOf(base, quote)
        return pda(listOf("pool".toByteArray(Charsets.US_ASCII), decode(config)) + order, BUY_DBC_PROGRAM)
    }

    fun dbcVault(pool: String, mint: String): String = pda(listOf(
        "token_vault".toByteArray(Charsets.US_ASCII), decode(mint), decode(pool),
    ), BUY_DBC_PROGRAM)

    fun mintMetadata(mint: String): String = pda(listOf(
        "metadata".toByteArray(Charsets.US_ASCII), decode(METADATA_PROGRAM), decode(mint),
    ), METADATA_PROGRAM)

    private fun pda(seeds: List<ByteArray>, program: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val programBytes = decode(program)
        for (bump in 255 downTo 0) {
            digest.reset()
            seeds.forEach(digest::update)
            digest.update(bump.toByte())
            digest.update(programBytes)
            digest.update("ProgramDerivedAddress".toByteArray(Charsets.US_ASCII))
            val candidate = digest.digest()
            if (!isEd25519Point(candidate)) return encodeBase58(candidate)
        }
        error("Unable to derive Solana program address")
    }

    private fun compareUnsigned(first: ByteArray, second: ByteArray): Int {
        for (index in first.indices) {
            val difference = (first[index].toInt() and 0xff) - (second[index].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return 0
    }

    /** Compressed Edwards-Y decompression check used by Solana PDA bump search. */
    private fun isEd25519Point(compressed: ByteArray): Boolean {
        val yBytes = compressed.copyOf()
        val sign = yBytes[31].toInt() and 0x80 != 0
        yBytes[31] = (yBytes[31].toInt() and 0x7f).toByte()
        val y = BigInteger(1, yBytes.reversedArray())
        if (y >= P) return false
        val y2 = y.multiply(y).mod(P)
        val denominator = D.multiply(y2).add(BigInteger.ONE).mod(P)
        if (denominator == BigInteger.ZERO) return false
        val x2 = y2.subtract(BigInteger.ONE).mod(P)
            .multiply(denominator.modInverse(P)).mod(P)
        if (x2 == BigInteger.ZERO) return !sign
        return x2.modPow(P_MINUS_ONE_OVER_TWO, P) == BigInteger.ONE
    }

    private fun decode(value: String): ByteArray {
        require(canonicalSolanaPublicKey(value) == value)
        var number = BigInteger.ZERO
        value.forEach { character ->
            val digit = ALPHABET.indexOf(character)
            require(digit >= 0)
            number = number.multiply(FIFTY_EIGHT).add(BigInteger.valueOf(digit.toLong()))
        }
        val magnitude = if (number == BigInteger.ZERO) ByteArray(0) else number.toByteArray().let {
            if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
        }
        require(magnitude.size <= 32)
        return ByteArray(32 - magnitude.size) + magnitude
    }

    private val P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val P_MINUS_ONE_OVER_TWO = P.subtract(BigInteger.ONE).shiftRight(1)
    private val FIFTY_EIGHT = BigInteger.valueOf(58)
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
}
