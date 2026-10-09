package com.elevencapital.app.wallet

import java.math.BigInteger
import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TransferStatusClientTest {
    private val accessToken = "headerheader.payloadpayload.signaturesignature"
    private val tokenProvider = TransferAccessTokenProvider { accessToken }
    private val now = Instant.parse("2026-09-22T12:00:00Z")
    private val solSender = "4vJ9JU1bJJE96FWSJKvHsmmFADCg4gpZQff4P3bkLKi"
    private val solRecipient = "8qbHbw2BbbTHBW1sbeqakYXVKRQM8Ne7pLK7m6CVfeR"
    private val signature = base58(ByteArray(64) { 5 })

    @Test fun `Solana status binds canonical signature and keeps unknown pending confirmed finalized and failed separate`() {
        val review = solanaReview()
        val expected = mapOf(
            "unknown" to TransferLifecycleStatus.UNKNOWN,
            "pending" to TransferLifecycleStatus.PENDING,
            "confirmed" to TransferLifecycleStatus.CONFIRMED,
            "finalized" to TransferLifecycleStatus.FINALIZED,
            "failed" to TransferLifecycleStatus.FAILED,
        )
        expected.forEach { (wire, state) ->
            val result = TransferStatusParser.parse(solanaStatus(review, wire), review, signature, now)
                as SolanaTransferStatus
            assertEquals(state, result.status)
            assertEquals(state == TransferLifecycleStatus.FAILED, result.hasFailed)
            assertFalse(result.permitsAutomaticResend)
            if (state == TransferLifecycleStatus.UNKNOWN) {
                assertNull(result.slot)
                assertNull(result.confirmationStatus)
            } else {
                assertEquals(990L, result.slot)
            }
        }
    }

    @Test fun `Solana rejects malformed signature slot overflow and contradictory status metadata`() {
        val review = solanaReview()
        for (invalid in listOf("1".repeat(64), "not-a-signature", signature.dropLast(1) + "0")) {
            assertProtocol { TransferStatusParser.parse(solanaStatus(review, "unknown"), review, invalid, now) }
        }
        for (change in listOf<(JSONObject) -> Unit>(
            { it.put("observedSlot", BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE).toString()) },
            { it.put("slot", "1001") },
            { it.put("confirmationStatus", "finalized") },
            { it.put("confirmations", JSONObject.NULL) },
            { it.put("failureCode", "transaction_error") },
            { it.put("senderVerified", false) },
            { it.put("senderVerified", JSONObject.NULL) },
        )) assertProtocol { TransferStatusParser.parse(solanaStatus(review, "confirmed").also(change),
            review, signature, now) }

        val nonfinalFailure = solanaStatus(review, "failed")
            .put("confirmationStatus", "confirmed")
            .put("confirmations", "10")
            .put("isFinalized", false)
        val observedNonfinal = TransferStatusParser.parse(nonfinalFailure, review, signature, now)
            as SolanaTransferStatus
        assertEquals(TransferLifecycleStatus.FAILED, observedNonfinal.status)
        assertFalse(observedNonfinal.isFinalized)
    }

    @Test fun `status client posts only bound identity and parses no-store response`() = runBlocking {
        val review = solanaReview()
        val (http, requests) = fakeHttp(FakeResponse(200, solanaStatus(review, "confirmed").toString()))
        val result = TransferStatusClient("https://wallet.eleven.test", tokenProvider, http, now = { now })
            .status(review, signature)
        assertEquals(TransferLifecycleStatus.CONFIRMED, result.status)
        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/wallet/transfer/status", request.url.encodedPath)
        assertEquals("no-store", request.header("Cache-Control"))
        assertEquals("Bearer $accessToken", request.header("Authorization"))
        val buffer = Buffer().also { requireNotNull(request.body).writeTo(it) }
        val sent = JSONObject(buffer.readUtf8())
        assertEquals(setOf("schemaVersion", "operationId", "walletId", "chain", "sender", "recipient", "assetId", "baseUnits", "transactionId"),
            sent.keys().asSequence().toSet())
        assertEquals(signature, sent.getString("transactionId"))
        assertEquals(review.operationId.value, sent.getString("operationId"))
        assertEquals(review.walletId, sent.getString("walletId"))
        assertEquals(review.recipient.value, sent.getString("recipient"))
        assertEquals("SOLANA:native", sent.getString("assetId"))
        assertEquals(review.baseUnits, sent.getString("baseUnits"))
    }

    @Test fun `status mismatch is typed without exposing upstream detail`() = runBlocking {
        val review = solanaReview()
        val (http, _) = fakeHttp(
            FakeResponse(422, """{"error":"transaction_mismatch","message":"sensitive wallet detail"}"""),
        )
        val failure = runCatching {
            TransferStatusClient("https://wallet.eleven.test", tokenProvider, http, now = { now }).status(review, signature)
        }.exceptionOrNull() as TransferPreparationException
        assertEquals(TransferPreparationFailureCode.TRANSACTION_MISMATCH, failure.code)
        assertFalse(failure.retryable)
        assertFalse(failure.message.orEmpty().contains("sensitive"))
    }

    private fun solanaReview(): TransferReviewSnapshot {
        val session = TransferSessionIdentity.create("privy-user", "privy-wallet", WalletNetworkId.SOLANA_MAINNET,
            solSender)
        return TransferDraft.create(session, WalletAssetId.SOLANA_SOL, solRecipient, "1", now.toEpochMilli()).review()
    }

    private fun solanaStatus(review: TransferReviewSnapshot, state: String): JSONObject {
        val visible = state != "unknown"
        val confirmation = when (state) {
            "pending" -> "processed"
            "confirmed" -> "confirmed"
            "finalized", "failed" -> "finalized"
            else -> null
        }
        return JSONObject()
            .put("schemaVersion", 1).put("operationId", review.operationId.value).put("chain", "SOLANA")
            .put("caip2", "solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp").put("transactionId", signature)
            .put("sender", review.sender.value)
            .put("senderVerified", if (state in setOf("confirmed", "finalized", "failed")) true else JSONObject.NULL)
            .put("status", state).put("confirmations", if (state in setOf("pending", "confirmed")) "10" else JSONObject.NULL)
            .put("isFinalized", state in setOf("finalized", "failed"))
            .put("observedAt", "2026-09-22T12:00:00.000Z").put("observedSlot", "1000")
            .put("slot", if (visible) "990" else JSONObject.NULL)
            .put("blockTime", if (state in setOf("confirmed", "finalized", "failed"))
                "2026-09-22T11:59:30.000Z" else JSONObject.NULL)
            .put("confirmationStatus", confirmation ?: JSONObject.NULL)
            .put("failureCode", if (state == "failed") "transaction_error" else JSONObject.NULL)
    }

    private fun base58(raw: ByteArray): String {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        val leading = raw.takeWhile { it == 0.toByte() }.size
        var integer = BigInteger(1, raw)
        val encoded = StringBuilder()
        while (integer.signum() > 0) {
            val parts = integer.divideAndRemainder(BigInteger.valueOf(58))
            encoded.append(alphabet[parts[1].toInt()])
            integer = parts[0]
        }
        return "1".repeat(leading) + encoded.reverse().toString()
    }

    private data class FakeResponse(val status: Int, val body: String)

    private fun fakeHttp(vararg responses: FakeResponse): Pair<OkHttpClient, MutableList<Request>> {
        val queue = ArrayDeque(responses.toList())
        val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            val item = queue.removeFirst()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(item.status).message("test")
                .header("Content-Type", "application/json").header("Cache-Control", "no-store")
                .body(item.body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return http to requests
    }

    private fun assertProtocol(block: () -> Unit) {
        val failure = assertThrows(TransferPreparationException::class.java, block)
        assertEquals(TransferPreparationFailureCode.PROTOCOL_ERROR, failure.code)
    }
}
