package com.elevencapital.app.tokenlaunch

import java.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenLaunchClientTest {
    private val client = TokenLaunchClient(
        "https://wallet.eleven.test",
        TokenLaunchAccessTokenProvider { "headerheader.payloadpayload.signaturesignature" },
    )

    @Test fun `all backend token launch errors map to bounded local failures`() {
        val cases = listOf(
            Triple("invalid_launch", 422, TokenLaunchFailureCode.INVALID_REQUEST),
            Triple("insufficient_mainnet_balance", 422, TokenLaunchFailureCode.INSUFFICIENT_BALANCE),
            Triple("launch_cost_too_high", 422, TokenLaunchFailureCode.COST_TOO_HIGH),
            Triple("simulation_failed", 422, TokenLaunchFailureCode.SIMULATION_FAILED),
            Triple("signed_transaction_invalid", 422, TokenLaunchFailureCode.SIGNED_TRANSACTION_INVALID),
            Triple("blockhash_expired", 422, TokenLaunchFailureCode.EXPIRED),
            Triple("mainnet_rpc_unavailable", 503, TokenLaunchFailureCode.SERVICE_UNAVAILABLE),
            Triple("launch_conflict", 409, TokenLaunchFailureCode.CONFLICT),
            Triple("unresolved_launch", 409, TokenLaunchFailureCode.CONFLICT),
            Triple("launch_not_found", 422, TokenLaunchFailureCode.NOT_FOUND),
            Triple("launch_state_invalid", 422, TokenLaunchFailureCode.STATE_INVALID),
            Triple("launch_storage_unavailable", 503, TokenLaunchFailureCode.STORAGE_UNAVAILABLE),
            Triple("metadata_unavailable", 503, TokenLaunchFailureCode.METADATA_UNAVAILABLE),
            Triple("authentication_unavailable", 503, TokenLaunchFailureCode.SERVICE_UNAVAILABLE),
            Triple("rate_limited", 429, TokenLaunchFailureCode.RATE_LIMITED),
        )
        cases.forEach { (wire, status, expected) ->
            val failure = client.serverFailure(
                status,
                JSONObject().put("error", wire).put("message", "untrusted backend detail"),
                submissionBoundary = false,
            )
            assertEquals(expected, failure.code)
            assertEquals(status, failure.statusCode)
            require(!failure.userMessage.contains("untrusted"))
            if (wire == "metadata_unavailable" || wire == "authentication_unavailable") {
                assertTrue(failure.retryable)
            }
        }
    }

    @Test fun `metadata upload is authenticated exact and strictly validates its response`() = runBlocking {
        MockWebServer().use { server ->
            val response = JSONObject()
                .put("metadataUri", "https://cdn.eleven.test/documents/${"a".repeat(64)}.json")
                .put("imageUri", "https://cdn.eleven.test/images/${"b".repeat(64)}.jpg")
                .put("contentHash", "a".repeat(64))
            server.enqueue(MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setHeader("Cache-Control", "no-store")
                .setBody(response.toString()))
            val local = TokenLaunchClient(
                server.url("/").toString(),
                TokenLaunchAccessTokenProvider { "headerheader.payloadpayload.signaturesignature" },
                allowLoopbackHttp = true,
            )
            val result = local.uploadMetadata("did:privy:test-user", metadataUpload())

            assertEquals(response.getString("metadataUri"), result.metadataUri)
            val sent = server.takeRequest()
            assertEquals("/v1/token-launches/metadata", sent.path)
            assertEquals("Bearer headerheader.payloadpayload.signaturesignature", sent.getHeader("Authorization"))
            val body = JSONObject(sent.body.readUtf8())
            assertEquals(
                setOf("name", "symbol", "description", "imageBase64", "imageMediaType", "websiteUrl", "xUrl"),
                body.keys().asSequence().toSet(),
            )
            assertEquals("image/jpeg", body.getString("imageMediaType"))
            assertTrue(!body.has("telegramUrl"))
        }
    }

    @Test fun `metadata response rejects extra fields and malformed hashes`() {
        val base = JSONObject()
            .put("metadataUri", "https://cdn.eleven.test/documents/token.json")
            .put("imageUri", "https://cdn.eleven.test/images/token.jpg")
            .put("contentHash", "a".repeat(64))

        listOf(
            JSONObject(base.toString()).put("unexpected", true),
            JSONObject(base.toString()).put("contentHash", "not-a-hash"),
            JSONObject(base.toString()).put("metadataUri", "http://127.0.0.1/token.json"),
            JSONObject(base.toString()).put("metadataUri", "ipfs://${"a".repeat(32)}/token.json"),
        ).forEach { response ->
            val failure = runCatching { parseUploadedTokenMetadata(response) }.exceptionOrNull()
            assertTrue(failure is TokenLaunchException)
            assertEquals(TokenLaunchFailureCode.PROTOCOL_ERROR, (failure as TokenLaunchException).code)
        }
    }

    private fun metadataUpload() = TokenLaunchMetadataUpload(
        name = "Race",
        symbol = "RACE",
        description = "A community token for racing fans.",
        artwork = EncodedTokenArtwork(Base64.getEncoder().encodeToString(
            byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()),
        )),
        websiteUrl = "https://race.example/token",
        xUrl = "https://x.com/race_token",
        telegramUrl = null,
    )
}
