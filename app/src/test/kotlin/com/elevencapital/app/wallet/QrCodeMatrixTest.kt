package com.elevencapital.app.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class QrCodeMatrixTest {
    @Test
    fun addressQrUsesVersionSixAndHasThreeFinderPatterns() {
        val qr = QrCodeMatrix.encodeAddress("0x52908400098527886E0F7030069857D2E4169EE7")
        assertEquals(41, qr.size)
        assertFinder(qr, 3, 3)
        assertFinder(qr, qr.size - 4, 3)
        assertFinder(qr, 3, qr.size - 4)
        for (index in 8..32) {
            assertEquals(index % 2 == 0, qr[6, index])
            assertEquals(index % 2 == 0, qr[index, 6])
        }
        for (dy in -2..2) for (dx in -2..2) {
            assertEquals(maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1, qr[34 + dx, 34 + dy])
        }
        assertTrue(qr[8, qr.size - 8])
    }

    @Test
    fun encodingIsDeterministicAndAddressSpecific() {
        val first = QrCodeMatrix.encodeAddress("11111111111111111111111111111111").rows()
        val again = QrCodeMatrix.encodeAddress("11111111111111111111111111111111").rows()
        val other = QrCodeMatrix.encodeAddress("Vote111111111111111111111111111111111111111").rows()
        assertEquals(first, again)
        assertTrue(first != other)
    }

    @Test
    fun qrPayloadRejectsWhitespaceUnicodeAndOversizeValues() {
        assertEquals(41, QrCodeMatrix.encodeAddress("x".repeat(58)).size)
        for (value in listOf("", "0x12 34", "0x12\n34", "0x12​34", "x".repeat(59), "x".repeat(65))) {
            assertTrue(runCatching { QrCodeMatrix.encodeAddress(value) }.isFailure)
        }
    }

    @Test
    fun representativeEthereumAndSolanaAddressesDecodeWithValidReedSolomonParity() {
        val addresses = listOf(
            "0x52908400098527886E0F7030069857D2E4169EE7",
            "So11111111111111111111111111111111111111112",
        )
        addresses.forEach { address -> assertStandardsRoundTrip(QrCodeMatrix.encodeAddress(address), address) }
    }

    private fun assertFinder(qr: QrCodeMatrix, centerX: Int, centerY: Int) {
        for (dy in -3..3) for (dx in -3..3) {
            val distance = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
            if (distance == 2) assertFalse(qr[centerX + dx, centerY + dy])
            else assertTrue(qr[centerX + dx, centerY + dy])
        }
    }

    /**
     * A small spec-level decoder kept independent of the production encoder: it locates data
     * modules, removes mask 0, de-interleaves v6-H blocks, checks every RS syndrome, and parses
     * byte mode. This catches symbols that merely look like QR codes but are not decodable.
     */
    private fun assertStandardsRoundTrip(qr: QrCodeMatrix, expected: String) {
        val function = Array(qr.size) { BooleanArray(qr.size) }
        fun mark(x: Int, y: Int) {
            if (x in function.indices && y in function.indices) function[y][x] = true
        }
        for (index in function.indices) {
            mark(6, index)
            mark(index, 6)
        }
        for ((centerX, centerY) in listOf(3 to 3, qr.size - 4 to 3, 3 to qr.size - 4)) {
            for (dy in -4..4) for (dx in -4..4) mark(centerX + dx, centerY + dy)
        }
        for (y in 32..36) for (x in 32..36) mark(x, y)

        val formatLocations = buildList {
            for (index in 0..5) add(8 to index)
            add(8 to 7)
            add(8 to 8)
            add(7 to 8)
            for (index in 9..14) add((14 - index) to 8)
            for (index in 0..7) add((qr.size - 1 - index) to 8)
            for (index in 8..14) add(8 to (qr.size - 15 + index))
            add(8 to (qr.size - 8))
        }
        formatLocations.forEach { (x, y) -> mark(x, y) }

        // H/0 format bits: BCH(15,5), XORed with the required 0x5412 mask.
        val formatData = 2 shl 3
        var formatRemainder = formatData
        repeat(10) {
            formatRemainder = (formatRemainder shl 1) xor ((formatRemainder ushr 9) * 0x537)
        }
        val formatBits = ((formatData shl 10) or formatRemainder) xor 0x5412
        val firstCopy = formatLocations.take(15)
        val secondCopy = formatLocations.drop(15).take(15)
        for (bit in 0..14) {
            val expectedBit = ((formatBits ushr bit) and 1) != 0
            assertEquals(expectedBit, qr[firstCopy[bit].first, firstCopy[bit].second])
            assertEquals(expectedBit, qr[secondCopy[bit].first, secondCopy[bit].second])
        }
        assertTrue(qr[8, qr.size - 8]) // Fixed dark module, outside the second format copy.

        val payloadBits = ArrayList<Boolean>(1383)
        var right = qr.size - 1
        while (right >= 1) {
            if (right == 6) right--
            val upward = ((right + 1) and 2) == 0
            for (vertical in 0 until qr.size) {
                val y = if (upward) qr.size - 1 - vertical else vertical
                for (offset in 0..1) {
                    val x = right - offset
                    if (!function[y][x]) payloadBits += qr[x, y].xor((x + y) % 2 == 0)
                }
            }
            right -= 2
        }
        assertEquals(172 * 8 + 7, payloadBits.size)
        assertTrue(payloadBits.drop(172 * 8).none { it })

        val codewords = ByteArray(172) { byteIndex ->
            var value = 0
            repeat(8) { bit -> if (payloadBits[byteIndex * 8 + bit]) value = value or (1 shl (7 - bit)) }
            value.toByte()
        }
        val dataBlocks = Array(4) { ByteArray(15) }
        val eccBlocks = Array(4) { ByteArray(28) }
        var cursor = 0
        for (column in 0 until 15) for (block in 0 until 4) dataBlocks[block][column] = codewords[cursor++]
        for (column in 0 until 28) for (block in 0 until 4) eccBlocks[block][column] = codewords[cursor++]
        assertEquals(codewords.size, cursor)

        for (block in 0 until 4) {
            val fullBlock = dataBlocks[block] + eccBlocks[block]
            for (rootPower in 0 until 28) {
                val root = gfPowerOfTwo(rootPower)
                var syndrome = 0
                fullBlock.forEach { codeword ->
                    syndrome = gfMultiply(syndrome, root) xor (codeword.toInt() and 0xff)
                }
                assertEquals("RS syndrome $rootPower in block $block", 0, syndrome)
            }
        }

        val dataBits = dataBlocks.flatMap { block ->
            block.flatMap { byte -> (7 downTo 0).map { bit -> (byte.toInt() ushr bit) and 1 } }
        }
        var dataCursor = 0
        fun read(length: Int): Int {
            var value = 0
            repeat(length) { value = (value shl 1) or dataBits[dataCursor++] }
            return value
        }
        assertEquals(0b0100, read(4))
        val byteLength = read(8)
        val decoded = ByteArray(byteLength) { read(8).toByte() }
        assertEquals(expected, decoded.toString(StandardCharsets.US_ASCII))
    }

    private fun gfPowerOfTwo(power: Int): Int {
        var result = 1
        repeat(power) { result = gfMultiply(result, 2) }
        return result
    }

    private fun gfMultiply(left: Int, right: Int): Int {
        var x = left
        var y = right
        var result = 0
        while (y != 0) {
            if ((y and 1) != 0) result = result xor x
            y = y ushr 1
            x = (x shl 1) xor ((x ushr 7) * 0x11d)
        }
        return result
    }
}
