package com.elevencapital.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityTokenMintValidatorTest {
    private val mint = "AKnL4NNf3DGWZJS6cPknBuEGnVsV4A4m5tgebLHaRSZ9"
    private val otherMint = "9hSR6S7WPtxmTojgo6GG3k4yDPecgJY292j7xrsUGWBu"
    private val jwt = "a".repeat(40) + "." + "b".repeat(40) + "." + "c".repeat(40)
    private val userId = "did:privy:mint-validator"

    @Test fun `exact authenticated mainnet mint response is valid`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok(validBody(mint)))
            val validator = validator(server)
            val result = validator.validate(userId, mint)
            assertTrue(result.isValid)
            assertEquals(mint, result.mintAddress)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/v1/community-tokens/$mint/validate", request.path)
            assertEquals("Bearer $jwt", request.getHeader("Authorization"))
            assertEquals("no-store", request.getHeader("Cache-Control"))
        }
    }

    @Test fun `address syntax and nonexistent accounts are invalid`() = runBlocking {
        MockWebServer().use { server ->
            val validator = validator(server)
            assertEquals(CommunityTokenMintStatus.INVALID, validator.validate(userId, "SPXTR").status)
            assertEquals(0, server.requestCount)
            server.enqueue(error(404))
            assertEquals(CommunityTokenMintStatus.INVALID, validator.validate(userId, mint).status)
            server.enqueue(error(422))
            assertEquals(CommunityTokenMintStatus.INVALID, validator.validate(userId, mint).status)
        }
    }

    @Test fun `wrong mint and non mainnet success body fail closed`() = runBlocking {
        MockWebServer().use { server ->
            val validator = validator(server)
            server.enqueue(ok(validBody(otherMint)))
            val mismatched = validator.validate(userId, mint)
            assertEquals(CommunityTokenMintStatus.UNAVAILABLE, mismatched.status)
            assertFalse(mismatched.isValid)
            server.enqueue(ok(validBody(mint).replace("solana-mainnet", "solana-devnet")))
            assertEquals(CommunityTokenMintStatus.UNAVAILABLE, validator.validate(userId, mint).status)
            server.enqueue(ok(validBody(mint).replace("\"valid\":true", "\"valid\":false")))
            assertEquals(CommunityTokenMintStatus.UNAVAILABLE, validator.validate(userId, mint).status)
        }
    }

    @Test fun `unavailable and expired session remain distinct from invalid mint`() = runBlocking {
        MockWebServer().use { server ->
            val validator = validator(server)
            server.enqueue(error(503))
            assertEquals(CommunityTokenMintStatus.UNAVAILABLE, validator.validate(userId, mint).status)
            server.enqueue(error(429))
            assertEquals(CommunityTokenMintStatus.UNAVAILABLE, validator.validate(userId, mint).status)
            server.enqueue(error(401))
            assertEquals(CommunityTokenMintStatus.AUTH_REQUIRED, validator.validate(userId, mint).status)
            server.enqueue(MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(validBody(mint)))
            assertEquals(CommunityTokenMintStatus.UNAVAILABLE, validator.validate(userId, mint).status)
        }
    }

    @Test fun `missing Privy token never reaches backend`() = runBlocking {
        MockWebServer().use { server ->
            val validator = CommunityTokenMintValidator(server.url("/").toString(),
                CommunityTokenMintAccessTokenProvider { throw IllegalStateException("Expired") },
                allowLoopbackHttp = true)
            assertEquals(CommunityTokenMintStatus.AUTH_REQUIRED, validator.validate(userId, mint).status)
            assertEquals(0, server.requestCount)
        }
    }

    private fun validator(server: MockWebServer) = CommunityTokenMintValidator(server.url("/").toString(),
        CommunityTokenMintAccessTokenProvider { expected ->
            assertEquals(userId, expected)
            jwt
        }, allowLoopbackHttp = true)

    private fun validBody(address: String) =
        """{"schemaVersion":1,"network":"solana-mainnet","mintAddress":"$address","valid":true}"""

    private fun ok(body: String) = MockResponse().setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .addHeader("Cache-Control", "no-store")
        .setBody(body)

    private fun error(status: Int) = MockResponse().setResponseCode(status)
        .addHeader("Content-Type", "application/json")
        .addHeader("Cache-Control", "no-store")
        .setBody("""{"error":"rejected","message":"Request rejected"}""")
}
