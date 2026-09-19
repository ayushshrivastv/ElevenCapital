package com.elevencapital.app.wallet

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
import org.junit.Assert.assertThrows
import org.junit.Test

class TransferIntentClientTest {
    private val token = "headerheader.payloadpayload.signaturesignature"

    @Test fun `commit and release use fresh bearer and exact immutable identity`() = runBlocking {
        var tokenCalls = 0
        val provider = TransferAccessTokenProvider { tokenCalls++; token }
        val (http, requests) = fakeHttp(
            """{"schemaVersion":1,"operationId":"5a40fc8d-10d4-4da9-b44f-b4e28ef917cc","state":"committed","idempotent":false}""",
            """{"schemaVersion":1,"operationId":"5a40fc8d-10d4-4da9-b44f-b4e28ef917cc","state":"released","idempotent":false}""",
        )
        val review = review()
        val client = TransferIntentClient("https://wallet.eleven.test", provider, http)

        client.commit(review)
        client.releaseDefinitelyNotBroadcast(review)

        assertEquals(2, tokenCalls)
        assertEquals(listOf("/v1/wallet/transfer/commit", "/v1/wallet/transfer/release"), requests.map { it.url.encodedPath })
        requests.forEach { request ->
            assertEquals("Bearer $token", request.header("Authorization"))
            val body = Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8()
            val json = JSONObject(body)
            assertEquals(review.operationId.value, json.getString("operationId"))
            assertEquals(review.walletId, json.getString("walletId"))
            assertEquals(review.sender.value, json.getString("sender"))
            assertEquals(review.recipient.value, json.getString("recipient"))
            assertEquals(review.baseUnits, json.getString("baseUnits"))
        }
        assertEquals("provider_definitely_not_broadcast",
            JSONObject(Buffer().also { requireNotNull(requests[1].body).writeTo(it) }.readUtf8()).getString("reason"))
    }

    @Test fun `production client rejects cleartext including loopback unless debug explicitly allows it`() {
        val provider = TransferAccessTokenProvider { token }
        assertThrows(IllegalArgumentException::class.java) { TransferIntentClient("http://127.0.0.1:8787", provider) }
        assertThrows(IllegalArgumentException::class.java) { TransferIntentClient("http://example.com", provider, allowLoopbackHttp = true) }
        TransferIntentClient("http://127.0.0.1:8787", provider, allowLoopbackHttp = true)
    }

    private fun review(): TransferReviewSnapshot {
        val session = TransferSessionIdentity.create("did:privy:test-user", "wallet-one",
            WalletNetworkId.ETHEREUM_MAINNET, "0x1111111111111111111111111111111111111111")
        val draft = TransferDraft.create(session, WalletAssetId.ETHEREUM_ETH,
            "0x2222222222222222222222222222222222222222", "0.1", Instant.parse("2026-09-22T12:00:00Z").toEpochMilli())
        return TransferReviewSnapshot(
            TransferOperationId.fromPersisted("5a40fc8d-10d4-4da9-b44f-b4e28ef917cc"), draft.review().userId,
            draft.review().walletId, draft.review().sender, draft.review().recipient, draft.review().assetId,
            draft.review().assetSymbol, draft.review().networkId, draft.review().networkCaip2,
            draft.review().contractOrMint, draft.review().displayAmount, draft.review().baseUnits,
            draft.review().decimals, draft.review().createdAtEpochMillis,
        )
    }

    private fun fakeHttp(vararg bodies: String): Pair<OkHttpClient, MutableList<Request>> {
        val queue = ArrayDeque(bodies.toList()); val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request(); val body = queue.removeFirst()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .header("Content-Type", "application/json").header("Cache-Control", "no-store")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return client to requests
    }
}
