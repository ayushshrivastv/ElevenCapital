package com.elevencapital.app.wallet

import java.nio.charset.StandardCharsets
import kotlin.math.abs
import kotlin.math.max

/**
 * Small, dependency-free QR encoder for wallet receive addresses.
 *
 * Eleven Capital only encodes plain ASCII Ethereum/Solana addresses. A fixed
 * version-6, error-correction-H symbol leaves enough recovery capacity for the
 * small centered logo used by the supplied receive reference.
 */
internal class QrCodeMatrix private constructor(
    val size: Int,
    private val modules: Array<BooleanArray>,
) {
    operator fun get(x: Int, y: Int): Boolean {
        require(x in 0 until size && y in 0 until size)
        return modules[y][x]
    }

    fun rows(): List<List<Boolean>> = modules.map { row -> row.toList() }

    companion object {
        private const val VERSION = 6
        private const val SIZE = VERSION * 4 + 17
        private const val DATA_CODEWORDS = 60
        private const val BLOCKS = 4
        private const val DATA_CODEWORDS_PER_BLOCK = 15
        private const val ECC_CODEWORDS_PER_BLOCK = 28
        private const val MASK = 0
        // Version 6-H has 60 data codewords. Byte mode consumes 12 header bits and up to
        // four terminator bits, leaving 58 complete payload bytes.
        private const val MAX_PAYLOAD_BYTES = 58

        fun encodeAddress(address: String): QrCodeMatrix {
            require(address.isNotEmpty() && address.length <= MAX_PAYLOAD_BYTES)
            require(address.all { it.code in 0x21..0x7E }) { "QR address must be printable ASCII." }
            val data = encodeData(address.toByteArray(StandardCharsets.US_ASCII))
            val codewords = addErrorCorrection(data)
            return buildSymbol(codewords)
        }

        private fun encodeData(bytes: ByteArray): ByteArray {
            val bits = BitBuffer(DATA_CODEWORDS * 8)
            bits.append(0b0100, 4) // Byte mode.
            bits.append(bytes.size, 8) // Version 1-9 byte-mode character count.
            bytes.forEach { bits.append(it.toInt() and 0xFF, 8) }
            bits.append(0, minOf(4, bits.remaining))
            while (bits.size % 8 != 0) bits.append(0, 1)
            var pad = 0
            while (bits.size < DATA_CODEWORDS * 8) {
                bits.append(if (pad++ % 2 == 0) 0xEC else 0x11, 8)
            }
            return bits.toByteArray()
        }

        private fun addErrorCorrection(data: ByteArray): ByteArray {
            require(data.size == DATA_CODEWORDS)
            val divisor = reedSolomonDivisor(ECC_CODEWORDS_PER_BLOCK)
            val blocks = Array(BLOCKS) { index ->
                data.copyOfRange(
                    index * DATA_CODEWORDS_PER_BLOCK,
                    (index + 1) * DATA_CODEWORDS_PER_BLOCK,
                )
            }
            val ecc = blocks.map { reedSolomonRemainder(it, divisor) }
            return ByteArray(DATA_CODEWORDS + BLOCKS * ECC_CODEWORDS_PER_BLOCK).also { result ->
                var cursor = 0
                for (column in 0 until DATA_CODEWORDS_PER_BLOCK) {
                    for (block in blocks) result[cursor++] = block[column]
                }
                for (column in 0 until ECC_CODEWORDS_PER_BLOCK) {
                    for (block in ecc) result[cursor++] = block[column]
                }
            }
        }

        private fun buildSymbol(codewords: ByteArray): QrCodeMatrix {
            val modules = Array(SIZE) { BooleanArray(SIZE) }
            val function = Array(SIZE) { BooleanArray(SIZE) }

            fun setFunction(x: Int, y: Int, black: Boolean) {
                if (x in 0 until SIZE && y in 0 until SIZE) {
                    modules[y][x] = black
                    function[y][x] = true
                }
            }

            // Timing first; finder and alignment patterns intentionally overwrite it.
            for (i in 0 until SIZE) {
                setFunction(6, i, i % 2 == 0)
                setFunction(i, 6, i % 2 == 0)
            }
            fun finder(cx: Int, cy: Int) {
                for (dy in -4..4) for (dx in -4..4) {
                    val distance = max(abs(dx), abs(dy))
                    setFunction(cx + dx, cy + dy, distance != 2 && distance != 4)
                }
            }
            finder(3, 3)
            finder(SIZE - 4, 3)
            finder(3, SIZE - 4)

            fun alignment(cx: Int, cy: Int) {
                for (dy in -2..2) for (dx in -2..2) {
                    setFunction(cx + dx, cy + dy, max(abs(dx), abs(dy)) != 1)
                }
            }
            // Version 6 alignment centers are [6, 34]; the three finder overlaps are omitted.
            alignment(34, 34)

            // Reserve both copies of the format string before placing payload bits.
            drawFormat(modules, function, 0)

            var bitIndex = 0
            var right = SIZE - 1
            while (right >= 1) {
                if (right == 6) right--
                val upward = ((right + 1) and 2) == 0
                for (vertical in 0 until SIZE) {
                    val y = if (upward) SIZE - 1 - vertical else vertical
                    for (offset in 0..1) {
                        val x = right - offset
                        if (function[y][x]) continue
                        val raw = bitIndex < codewords.size * 8 &&
                            ((codewords[bitIndex ushr 3].toInt() ushr (7 - (bitIndex and 7))) and 1) != 0
                        modules[y][x] = raw.xor((x + y) % 2 == 0) // Mask pattern 0.
                        bitIndex++
                    }
                }
                right -= 2
            }
            require(bitIndex >= codewords.size * 8)
            drawFormat(modules, function, MASK)
            return QrCodeMatrix(SIZE, modules)
        }

        /** Error-correction level H has format selector 2. */
        private fun drawFormat(modules: Array<BooleanArray>, function: Array<BooleanArray>, mask: Int) {
            val data = (2 shl 3) or mask
            var remainder = data
            repeat(10) { remainder = (remainder shl 1) xor ((remainder ushr 9) * 0x537) }
            val bits = ((data shl 10) or remainder) xor 0x5412
            fun bit(index: Int) = ((bits ushr index) and 1) != 0
            fun set(x: Int, y: Int, black: Boolean) {
                modules[y][x] = black
                function[y][x] = true
            }
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6))
            set(8, 8, bit(7))
            set(7, 8, bit(8))
            for (i in 9..14) set(14 - i, 8, bit(i))
            for (i in 0..7) set(SIZE - 1 - i, 8, bit(i))
            for (i in 8..14) set(8, SIZE - 15 + i, bit(i))
            set(8, SIZE - 8, true)
        }

        private fun reedSolomonDivisor(degree: Int): ByteArray {
            val result = ByteArray(degree)
            result[degree - 1] = 1
            var root = 1
            repeat(degree) {
                for (j in result.indices) {
                    result[j] = multiply(result[j].toInt() and 0xFF, root).toByte()
                    if (j + 1 < result.size) result[j] =
                        ((result[j].toInt() and 0xFF) xor (result[j + 1].toInt() and 0xFF)).toByte()
                }
                root = multiply(root, 0x02)
            }
            return result
        }

        private fun reedSolomonRemainder(data: ByteArray, divisor: ByteArray): ByteArray {
            val result = ByteArray(divisor.size)
            for (value in data) {
                val factor = (value.toInt() xor result[0].toInt()) and 0xFF
                result.copyInto(result, 0, 1, result.size)
                result[result.lastIndex] = 0
                for (i in result.indices) {
                    result[i] = ((result[i].toInt() and 0xFF) xor
                        multiply(divisor[i].toInt() and 0xFF, factor)).toByte()
                }
            }
            return result
        }

        private fun multiply(left: Int, right: Int): Int {
            var x = left
            var y = right
            var result = 0
            while (y != 0) {
                if ((y and 1) != 0) result = result xor x
                y = y ushr 1
                x = (x shl 1) xor ((x ushr 7) * 0x11D)
            }
            return result
        }
    }

    private class BitBuffer(private val capacity: Int) {
        private val values = BooleanArray(capacity)
        var size: Int = 0
            private set
        val remaining: Int get() = capacity - size

        fun append(value: Int, length: Int) {
            require(length in 0..31 && length <= remaining)
            for (i in length - 1 downTo 0) values[size++] = ((value ushr i) and 1) != 0
        }

        fun toByteArray(): ByteArray {
            require(size % 8 == 0)
            return ByteArray(size / 8) { index ->
                var value = 0
                repeat(8) { bit -> if (values[index * 8 + bit]) value = value or (1 shl (7 - bit)) }
                value.toByte()
            }
        }
    }
}
