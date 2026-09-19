package com.elevencapital.app.wallet

import java.math.BigInteger
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferPreparationClientTest {
    private val accessToken = "headerheader.payloadpayload.signaturesignature"
    private val tokenProvider = TransferAccessTokenProvider { accessToken }
    private val now = Instant.parse("2026-09-22T12:00:00Z")
    private val ethereumSender = "0x1111111111111111111111111111111111111111"
    private val ethereumRecipient = "0x2222222222222222222222222222222222222222"
    private val solanaSender = "4vJ9JU1bJJE96FWSJKvHsmmFADCg4gpZQff4P3bkLKi"
    private val solanaRecipient = "8qbHbw2BbbTHBW1sbeqakYXVKRQM8Ne7pLK7m6CVfeR"
    private val blockhash = "CktRuQ2mttgRGkXJtyksdKHjUdc2C4TgDzyB98oEzy8"

    @Test fun `Ethereum preparation is bound exactly and Privy JSON uses gasLimit`() {
        val review = ethereumReview(WalletAssetId.ETHEREUM_ETH, "0.1")
        val prepared = PreparedTransferParser.parse(ethereumResponse(review), review, now)
            as EthereumPreparedTransfer

        assertEquals(review, prepared.review)
        assertEquals(BigInteger("1000000000000000000"), prepared.assetBalanceBaseUnits)
        assertEquals(BigInteger("63000000000000"), prepared.estimatedFeeBaseUnits)
        assertEquals(BigInteger("84000000000000"), prepared.maxFeeBaseUnits)
        assertEquals(BigInteger("2748"), prepared.observedBlock)
        val privy = JSONObject(prepared.transaction.rpcJson())
        assertEquals(setOf("chainId", "from", "to", "nonce", "gasLimit", "value", "data", "type",
            "maxFeePerGas", "maxPriorityFeePerGas"), privy.keys().asSequence().toSet())
        assertEquals("0x5208", privy.getString("gasLimit"))
        assertFalse(privy.has("gas"))
        assertEquals(ethereumRecipient, privy.getString("to"))
        assertEquals("0x16345785d8a0000", privy.getString("value"))
        assertEquals("0x", privy.getString("data"))
    }

    @Test fun `Ethereum USDC preparation pins contract zero value recipient and exact calldata`() {
        val review = ethereumReview(WalletAssetId.ETHEREUM_USDC, "0.123456")
        val response = ethereumResponse(review, usdc = true)
        val prepared = PreparedTransferParser.parse(response, review, now) as EthereumPreparedTransfer
        assertEquals(WalletAssetRegistry.ethereumUsdc, prepared.asset)
        assertEquals(WalletAssetRegistry.ethereumUsdc.contractOrMint, prepared.transaction.to)
        assertEquals("0x0", prepared.transaction.value)
        val expectedData = "0xa9059cbb" + ethereumRecipient.substring(2).padStart(64, '0') +
            BigInteger("123456").toString(16).padStart(64, '0')
        assertEquals(expectedData, prepared.transaction.data)

        for (mutate in listOf<(JSONObject) -> Unit>(
            { it.getJSONObject("transaction").put("to", ethereumRecipient) },
            { it.getJSONObject("transaction").put("value", "0x1") },
            { it.getJSONObject("transaction").put("data", expectedData.dropLast(1) + "1") },
            { it.getJSONObject("asset").put("address", ethereumRecipient) },
            { it.getJSONObject("asset").put("decimals", 18) },
        )) {
            val tampered = ethereumResponse(review, usdc = true).also(mutate)
            assertProtocolFailure { PreparedTransferParser.parse(tampered, review, now) }
        }
    }

    @Test fun `response cannot change operation chain asset parties amount base units or schema`() {
        val review = ethereumReview(WalletAssetId.ETHEREUM_ETH, "0.1")
        val changes = listOf<(JSONObject) -> Unit>(
            { it.put("schemaVersion", "1") },
            { it.put("operationId", "5a40fc8d-10d4-4da9-b44f-b4e28ef917cc") },
            { it.put("caip2", "eip155:137") },
            { it.getJSONObject("asset").put("id", "ETHEREUM:USDC") },
            { it.put("sender", ethereumRecipient) },
            { it.put("recipient", ethereumSender) },
            { it.put("amount", "0.10") },
            { it.put("baseUnits", "100000000000000001") },
            { it.put("unexpected", true) },
            { it.getJSONObject("transaction").put("unexpected", "0x0") },
        )
        changes.forEach { change ->
            assertProtocolFailure { PreparedTransferParser.parse(ethereumResponse(review).also(change), review, now) }
        }
    }

    @Test fun `noncanonical integers hex fees and expired preparations fail closed`() {
        val review = ethereumReview(WalletAssetId.ETHEREUM_ETH, "0.1")
        for (change in listOf<(JSONObject) -> Unit>(
            { it.put("balanceBaseUnits", "01000000000000000000") },
            { it.put("estimatedFeeBaseUnits", "0") },
            { it.put("maxFeeBaseUnits", "62999999999999") },
            { it.getJSONObject("transaction").put("gas", "0x05208") },
            { it.getJSONObject("transaction").put("maxFeePerGas", "0xEE6B2800") },
        )) assertProtocolFailure { PreparedTransferParser.parse(ethereumResponse(review).also(change), review, now) }

        val expired = ethereumResponse(review).put("preparedAt", "2026-09-22T11:58:59.000Z")
            .put("expiresAt", "2026-09-22T11:59:59.000Z")
        assertCode(TransferPreparationFailureCode.EXPIRED) {
            PreparedTransferParser.parse(expired, review, now)
        }
    }

    @Test fun `client rejects backend fee and gas values above product safety ceilings`() {
        val review = ethereumReview(WalletAssetId.ETHEREUM_ETH, "0.1")
        for (change in listOf<(JSONObject) -> Unit>(
            { response ->
                val gas = BigInteger("50001")
                val price = BigInteger("4000000000")
                response.getJSONObject("transaction").put("gas", "0x${gas.toString(16)}")
                response.put("estimatedFeeBaseUnits", (gas * BigInteger("3000000000")).toString())
                response.put("maxFeeBaseUnits", (gas * price).toString())
            },
            { response ->
                val gas = BigInteger("21000")
                val price = BigInteger("2000000000001")
                response.getJSONObject("transaction").put("maxFeePerGas", "0x${price.toString(16)}")
                response.put("maxFeeBaseUnits", (gas * price).toString())
            },
            { response ->
                val gas = BigInteger("50000")
                val price = BigInteger("1100000000000")
                response.getJSONObject("transaction").put("gas", "0x${gas.toString(16)}")
                    .put("maxFeePerGas", "0x${price.toString(16)}")
                response.put("estimatedFeeBaseUnits", (gas * BigInteger("3000000000")).toString())
                response.put("maxFeeBaseUnits", (gas * price).toString())
            },
        )) assertProtocolFailure { PreparedTransferParser.parse(ethereumResponse(review).also(change), review, now) }

        val solanaReview = solanaReview()
        val encoded = solanaTransaction(solanaReview.sender.value, solanaReview.recipient.value, blockhash,
            BigInteger(solanaReview.baseUnits))
        assertProtocolFailure {
            PreparedTransferParser.parse(solanaResponse(solanaReview, encoded)
                .put("estimatedFeeBaseUnits", "5000001").put("maxFeeBaseUnits", "5000001"), solanaReview, now)
        }
    }

    @Test fun `Solana unsigned transaction is decoded and bound before bytes are exposed`() {
        val review = solanaReview()
        val encoded = solanaTransaction(review.sender.value, review.recipient.value, blockhash,
            BigInteger(review.baseUnits))
        val response = solanaResponse(review, encoded)
        val prepared = PreparedTransferParser.parse(response, review, now) as SolanaPreparedTransfer
        assertEquals(BigInteger("9001"), prepared.observedSlot)
        assertEquals(blockhash, prepared.recentBlockhash)
        val first = prepared.unsignedTransactionBytes()
        val second = prepared.unsignedTransactionBytes()
        assertNotEquals(first, second)
        first[1] = 1
        assertEquals(0, prepared.unsignedTransactionBytes()[1].toInt())

        val signedPlaceholder = encoded.copyOf().also { it[1] = 1 }
        assertProtocolFailure {
            PreparedTransferParser.parse(solanaResponse(review, signedPlaceholder), review, now)
        }
        val wrongRecipient = solanaTransaction(review.sender.value, blockhash, blockhash,
            BigInteger(review.baseUnits))
        assertProtocolFailure {
            PreparedTransferParser.parse(solanaResponse(review, wrongRecipient), review, now)
        }
        val noncanonicalBase64 = Base64.getEncoder().withoutPadding().encodeToString(encoded)
        assertProtocolFailure {
            PreparedTransferParser.parse(solanaResponse(review, Base64.getDecoder().decode(noncanonicalBase64))
                .put("transactionBase64", noncanonicalBase64), review, now)
        }
    }

    @Test fun `client posts only the immutable review and accepts a no-store JSON preparation`() = runBlocking {
        val review = ethereumReview(WalletAssetId.ETHEREUM_ETH, "0.1")
        val (http, requests) = fakeHttp(FakeResponse(200, ethereumResponse(review).toString()))
        val prepared = TransferPreparationClient("https://wallet.eleven.test", tokenProvider, http, now = { now }).prepare(review)
        assertTrue(prepared is EthereumPreparedTransfer)
        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/wallet/transfer/prepare", request.url.encodedPath)
        assertEquals("no-store", request.header("Cache-Control"))
        assertEquals("Bearer $accessToken", request.header("Authorization"))
        val buffer = Buffer().also { requireNotNull(request.body).writeTo(it) }
        val sent = JSONObject(buffer.readUtf8())
        assertEquals(setOf("schemaVersion", "operationId", "walletId", "chain", "sender", "recipient", "assetId", "amount"),
            sent.keys().asSequence().toSet())
        assertEquals(review.operationId.value, sent.getString("operationId"))
        assertEquals(review.walletId, sent.getString("walletId"))
        assertEquals(review.sender.value, sent.getString("sender"))
        assertEquals(review.recipient.value, sent.getString("recipient"))
        assertEquals("ETHEREUM:native", sent.getString("assetId"))
    }

    @Test fun `typed server failures reveal no body details and redirects are never followed`() = runBlocking {
        val review = ethereumReview(WalletAssetId.ETHEREUM_ETH, "0.1")
        val (http, requests) = fakeHttp(
            FakeResponse(422, """{"error":"insufficient_fee_balance","message":"secret upstream detail"}"""),
            FakeResponse(307, """{"error":"invalid_request","message":"redirect"}"""),
        )
        val client = TransferPreparationClient("https://wallet.eleven.test", tokenProvider, http, now = { now })
        val insufficient = runCatching { client.prepare(review) }.exceptionOrNull()
            as TransferPreparationException
        assertEquals(TransferPreparationFailureCode.INSUFFICIENT_FEE_BALANCE, insufficient.code)
        assertEquals(422, insufficient.statusCode)
        assertFalse(insufficient.retryable)
        assertFalse(insufficient.message.orEmpty().contains("secret"))
        assertCode(TransferPreparationFailureCode.PROTOCOL_ERROR) { runBlocking { client.prepare(review) } }
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.url.encodedPath == "/v1/wallet/transfer/prepare" })
    }

    @Test fun `success requires bounded JSON and no-store and remote cleartext is rejected`() = runBlocking {
        val review = ethereumReview(WalletAssetId.ETHEREUM_ETH, "0.1")
        assertThrows(IllegalArgumentException::class.java) { TransferPreparationClient("http://example.com", tokenProvider) }
        assertThrows(IllegalArgumentException::class.java) { TransferPreparationClient("https://user:pass@example.com", tokenProvider) }
        assertThrows(IllegalArgumentException::class.java) { TransferPreparationClient("http://127.0.0.1:8787", tokenProvider) }
        TransferPreparationClient("http://127.0.0.1:8787", tokenProvider, allowLoopbackHttp = true)
        val (http, _) = fakeHttp(
            FakeResponse(200, ethereumResponse(review).toString(), noStore = false),
            FakeResponse(200, " ".repeat(32 * 1024 + 1)),
        )
        val client = TransferPreparationClient("https://wallet.eleven.test", tokenProvider, http, now = { now })
        repeat(2) {
            val failure = runCatching { client.prepare(review) }.exceptionOrNull()
            assertTrue(failure is TransferPreparationException)
            assertEquals(TransferPreparationFailureCode.PROTOCOL_ERROR,
                (failure as TransferPreparationException).code)
        }
    }

    @Test fun `wire JSON must contain exactly one object`() {
        assertEquals(1, parseSingleJsonObject("{\"schemaVersion\":1}".toByteArray()).getInt("schemaVersion"))
        assertThrows(JSONException::class.java) {
            parseSingleJsonObject("{\"schemaVersion\":1} trailing".toByteArray())
        }
        assertThrows(JSONException::class.java) { parseSingleJsonObject("[]".toByteArray()) }
    }

    private fun ethereumReview(assetId: WalletAssetId, amount: String): TransferReviewSnapshot {
        val session = TransferSessionIdentity.create("privy-user", "privy-wallet", WalletNetworkId.ETHEREUM_MAINNET,
            ethereumSender)
        return TransferDraft.create(session, assetId, ethereumRecipient, amount, now.toEpochMilli()).review()
    }

    private fun solanaReview(): TransferReviewSnapshot {
        val session = TransferSessionIdentity.create("privy-user", "privy-wallet", WalletNetworkId.SOLANA_MAINNET,
            solanaSender)
        return TransferDraft.create(session, WalletAssetId.SOLANA_SOL, solanaRecipient, "1.000000001",
            now.toEpochMilli()).review()
    }

    private fun ethereumResponse(review: TransferReviewSnapshot, usdc: Boolean = false): JSONObject {
        val gas = if (usdc) BigInteger("70000") else BigInteger("21000")
        val maxPrice = BigInteger("4000000000")
        val estimatedPrice = BigInteger("3000000000")
        val baseUnits = BigInteger(review.baseUnits)
        val asset = if (usdc) WalletAssetRegistry.ethereumUsdc else WalletAssetRegistry.ethereumEth
        val data = if (usdc) "0xa9059cbb" + ethereumRecipient.substring(2).padStart(64, '0') +
            baseUnits.toString(16).padStart(64, '0') else "0x"
        return JSONObject()
            .put("schemaVersion", 1)
            .put("operationId", review.operationId.value)
            .put("chain", "ETHEREUM")
            .put("caip2", "eip155:1")
            .put("asset", JSONObject().put("id", if (usdc) "ETHEREUM:USDC" else "ETHEREUM:native")
                .put("symbol", asset.symbol).put("kind", if (usdc) "erc20" else "native")
                .put("address", asset.contractOrMint?.lowercase() ?: JSONObject.NULL).put("decimals", asset.decimals))
            .put("sender", review.sender.value)
            .put("recipient", review.recipient.value)
            .put("amount", review.displayAmount)
            .put("baseUnits", review.baseUnits)
            .put("balanceBaseUnits", if (usdc) "1000000" else "1000000000000000000")
            .put("estimatedFeeBaseUnits", (gas * estimatedPrice).toString())
            .put("maxFeeBaseUnits", (gas * maxPrice).toString())
            .put("preparedAt", "2026-09-22T12:00:00.000Z")
            .put("expiresAt", "2026-09-22T12:01:00.000Z")
            .put("observedBlock", "2748")
            .put("transaction", JSONObject().put("chainId", "0x1").put("from", review.sender.value)
                .put("to", if (usdc) requireNotNull(asset.contractOrMint).lowercase() else review.recipient.value)
                .put("nonce", "0x5").put("gas", "0x${gas.toString(16)}")
                .put("value", if (usdc) "0x0" else "0x${baseUnits.toString(16)}").put("data", data)
                .put("type", "0x2").put("maxFeePerGas", "0xee6b2800")
                .put("maxPriorityFeePerGas", "0x77359400"))
    }

    private fun solanaResponse(review: TransferReviewSnapshot, transaction: ByteArray): JSONObject = JSONObject()
        .put("schemaVersion", 1).put("operationId", review.operationId.value).put("chain", "SOLANA")
        .put("caip2", WalletAssetRegistry.solanaMainnet.caip2)
        .put("asset", JSONObject().put("id", "SOLANA:native").put("symbol", "SOL").put("kind", "native")
            .put("address", JSONObject.NULL).put("decimals", 9))
        .put("sender", review.sender.value).put("recipient", review.recipient.value).put("amount", review.displayAmount)
        .put("baseUnits", review.baseUnits).put("balanceBaseUnits", "2000000000")
        .put("estimatedFeeBaseUnits", "5000").put("maxFeeBaseUnits", "5000")
        .put("preparedAt", "2026-09-22T12:00:00.000Z").put("expiresAt", "2026-09-22T12:00:45.000Z")
        .put("observedSlot", "9001").put("recentBlockhash", blockhash).put("lastValidBlockHeight", "12345")
        .put("transactionBase64", Base64.getEncoder().encodeToString(transaction)).put("encoding", "base64")

    private fun solanaTransaction(sender: String, recipient: String, recentBlockhash: String, amount: BigInteger): ByteArray {
        fun compact(value: Int) = byteArrayOf(value.toByte())
        fun key(value: String): ByteArray {
            var integer = BigInteger.ZERO
            val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
            value.forEach { integer = integer * BigInteger.valueOf(58) + BigInteger.valueOf(alphabet.indexOf(it).toLong()) }
            val magnitude = if (integer == BigInteger.ZERO) byteArrayOf() else integer.toByteArray()
                .let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
            val leading = value.takeWhile { it == '1' }.length
            return ByteArray(leading) + ByteArray(32 - leading - magnitude.size) + magnitude
        }
        val data = ByteArray(12)
        data[0] = 2
        var remaining = amount
        repeat(8) { index -> data[index + 4] = remaining.and(BigInteger.valueOf(255)).toByte(); remaining = remaining.shiftRight(8) }
        val message = byteArrayOf(1, 0, 1) + compact(3) + key(sender) + key(recipient) +
            key("11111111111111111111111111111111") + key(recentBlockhash) + compact(1) +
            byteArrayOf(2) + compact(2) + byteArrayOf(0, 1) + compact(12) + data
        return compact(1) + ByteArray(64) + message
    }

    private data class FakeResponse(val status: Int, val body: String, val noStore: Boolean = true)

    private fun fakeHttp(vararg responses: FakeResponse): Pair<OkHttpClient, MutableList<Request>> {
        val queue = ArrayDeque(responses.toList())
        val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            val item = queue.removeFirst()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(item.status).message("test")
                .header("Content-Type", "application/json")
                .also { if (item.noStore) it.header("Cache-Control", "no-store") }
                .body(item.body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return http to requests
    }

    private fun assertProtocolFailure(block: () -> Unit) = assertCode(TransferPreparationFailureCode.PROTOCOL_ERROR, block)

    private fun assertCode(expected: TransferPreparationFailureCode, block: () -> Unit) {
        val failure = assertThrows(TransferPreparationException::class.java, block)
        assertEquals(expected, failure.code)
    }
}
