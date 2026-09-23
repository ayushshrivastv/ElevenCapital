package com.elevencapital.app.purchase

import com.elevencapital.app.auth.UserWallet
import com.elevencapital.app.auth.WalletChain
import com.elevencapital.app.wallet.encodeBase58
import com.elevencapital.core.stock.flow.OrderSide
import java.math.BigInteger
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseProtocolTest {
    private val stockId = "backed:asset-1"
    private val wallet = "0x1111111111111111111111111111111111111111"
    private val destination = PurchaseDestination(
        "nvda-ethereum", PurchaseNetwork.ETHEREUM,
        "0x2222222222222222222222222222222222222222", "NVDAx", 18, true,
    )
    private val binding = PurchaseQuoteBinding(
        operationId = "123e4567-e89b-42d3-a456-426614174000",
        userId = "did:privy:user-1", stockId = stockId, fromAssetId = "ethereum:USDC",
        fromNetwork = PurchaseNetwork.ETHEREUM, inputDecimals = 6,
        requestedDestinationId = null, inputBaseUnits = BigInteger("100000000"),
        slippageBps = 50,
        walletAddresses = setOf(wallet),
    )

    @Test
    fun optionsRequireExactBalancesAndVerifiedDestinations() {
        val parsed = PurchaseProtocol.options(validOptions(), stockId)
        assertEquals("ethereum:USDC", parsed.defaultPaymentAssetId)
        assertEquals("1.5", parsed.paymentAssets.single().balance.toPlainString())
        assertEquals(PurchaseNetwork.ETHEREUM, parsed.destinations.single().network)
    }

    @Test
    fun optionsValidateProviderUiMultiplierAgainstRawBalance() {
        val multiplier = BigDecimal("1.0059033904787456")
        val source = validOptions()
        source.getJSONArray("paymentAssets").getJSONObject(0)
            .put("decimals", 8)
            .put("balanceBaseUnits", "100000000")
            .put("balance", "1.00590339")
            .put("uiMultiplier", multiplier.toPlainString())
        source.getJSONArray("destinations").getJSONObject(0)
            .put("uiMultiplier", multiplier.toPlainString())

        val options = PurchaseProtocol.options(source, stockId)
        val asset = options.paymentAssets.single()

        assertEquals(multiplier, asset.uiMultiplier)
        assertEquals(BigDecimal("1.00590339"), asset.balance)
        assertEquals(asset.balance, displayAmountForRaw(asset.balanceBaseUnits, asset.decimals, asset.uiMultiplier))
        assertEquals(multiplier, options.destinations.single().uiMultiplier)
    }

    @Test
    fun quoteBindsNonUnitInputAndOutputDisplayScalesToRawAmounts() {
        val multiplier = BigDecimal("1.0059033904787456")
        val scaledDestination = destination.copy(decimals = 8, uiMultiplier = multiplier)
        val scaledBinding = binding.copy(inputDecimals = 8, inputBaseUnits = BigInteger("100000000"),
            inputUiMultiplier = multiplier)
        val source = validQuote()
            .put("inputAmount", "1.00590339")
            .put("inputBaseUnits", "100000000")
            .put("outputDecimals", 8)
            .put("outputUiMultiplier", multiplier.toPlainString())
            .put("estimatedOutputAmount", "1.00590339")
            .put("estimatedOutputBaseUnits", "100000000")
            .put("minimumReceived", "0.99584435")
            .put("minimumReceivedBaseUnits", "99000000")

        val quote = PurchaseProtocol.quote(source, scaledBinding, listOf(scaledDestination),
            Instant.parse("2026-09-22T10:00:00Z"))

        assertEquals(BigDecimal("1.00590339"), quote.inputAmount)
        assertEquals(BigDecimal("1.00590339"), quote.estimatedOutputAmount)
        assertEquals(BigDecimal("0.99584435"), quote.minimumReceived)
    }

    @Test
    fun sellDisplayQuantityFloorsToSafeRawSpend() {
        val multiplier = BigDecimal("1.0059033904787456")

        val raw = rawBaseUnitsForDisplay(BigDecimal.ONE, 8, multiplier)

        assertEquals(BigInteger("99413125"), raw)
        assertEquals(BigDecimal("0.99999999"), displayAmountForRaw(raw, 8, multiplier))
    }

    @Test
    fun quoteOutputScaleMetadataMustBeCompleteAndMatchDestination() {
        assertThrows(PurchaseException::class.java) {
            PurchaseProtocol.quote(validQuote().put("outputDecimals", 18), binding, listOf(destination),
                Instant.parse("2026-09-22T10:00:00Z"))
        }
        assertThrows(PurchaseException::class.java) {
            PurchaseProtocol.quote(
                validQuote().put("outputDecimals", 18).put("outputUiMultiplier", "1.01"),
                binding,
                listOf(destination),
                Instant.parse("2026-09-22T10:00:00Z"),
            )
        }
    }

    @Test
    fun stockIdsAcceptOnlySupportedProvidersAndSafeAsciiAssets() {
        assertTrue("backed:TSLAx".isSafeStockId())
        assertTrue("backpack:AAPL.US".isSafeStockId())
        assertTrue("prestocks:openai_1".isSafeStockId())
        assertFalse("unknown:TSLAx".isSafeStockId())
        assertFalse("backed:TSLA x".isSafeStockId())
        assertFalse("backed:tésla".isSafeStockId())
    }

    @Test
    fun optionsRequestSendsExplicitSellSide() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(validOptions().toString()))
            val client = PurchaseClient(server.url("/").toString(), PurchaseAccessTokenProvider {
                "header0123456789.payload0123456789.signature0123456789"
            }, allowLoopbackHttp = true)

            client.options("did:privy:user-1", stockId, listOf(
                UserWallet(WalletChain.SOLANA, "11111111111111111111111111111111"),
                UserWallet(WalletChain.ETHEREUM, wallet),
            ), OrderSide.SELL)

            val sent = JSONObject(requireNotNull(server.takeRequest().body.readUtf8()))
            assertEquals("SELL", sent.getString("side"))
        }
    }

    @Test
    fun quoteRequestBindsAndSendsExplicitSellSide() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(validQuote().toString()))
            val client = PurchaseClient(server.url("/").toString(), PurchaseAccessTokenProvider {
                "header0123456789.payload0123456789.signature0123456789"
            }, now = { Instant.parse("2026-09-22T10:00:00Z") }, allowLoopbackHttp = true)

            val quote = client.quote(PurchaseQuoteRequest(
                operationId = binding.operationId,
                userId = binding.userId,
                stockId = stockId,
                fromAssetId = binding.fromAssetId,
                fromNetwork = binding.fromNetwork,
                inputDecimals = binding.inputDecimals,
                destinationId = null,
                amountBaseUnits = binding.inputBaseUnits,
                slippageBps = binding.slippageBps,
                wallets = listOf(
                    UserWallet(WalletChain.ETHEREUM, wallet),
                    UserWallet(WalletChain.SOLANA, "11111111111111111111111111111111"),
                ),
                destinations = listOf(destination),
                side = OrderSide.SELL,
            ))

            val sent = JSONObject(requireNotNull(server.takeRequest().body.readUtf8()))
            assertEquals("SELL", sent.getString("side"))
            assertEquals(OrderSide.SELL, quote.binding.side)
        }
    }

    @Test
    fun quoteBindsOperationWalletAmountsAndActionPlan() {
        val quote = PurchaseProtocol.quote(validQuote(), binding, listOf(destination), Instant.parse("2026-09-22T10:00:00Z"))
        assertEquals(binding.operationId, quote.binding.operationId)
        assertEquals(1, quote.walletConfirmations)
        assertEquals(PurchaseActionKind.EVM_ROUTE, quote.actions.single().kind)
        assertEquals("0.5", quote.minimumReceived.toPlainString())
    }

    @Test
    fun quoteRejectsChangedOperationAndUnknownFields() {
        val changed = validQuote().put("operationId", "123e4567-e89b-42d3-a456-426614174001")
        assertThrows(PurchaseException::class.java) {
            PurchaseProtocol.quote(changed, binding, listOf(destination), Instant.parse("2026-09-22T10:00:00Z"))
        }
        val extra = validQuote().put("rawRouterPayload", "must not pass through")
        assertThrows(PurchaseException::class.java) {
            PurchaseProtocol.quote(extra, binding, listOf(destination), Instant.parse("2026-09-22T10:00:00Z"))
        }
    }

    @Test
    fun quoteRejectsFeeAboveIndependentClientCeiling() {
        val quote = validQuote()
        quote.getJSONArray("actions").getJSONObject(0).getJSONObject("transaction")
            .put("gas", "0x4c4b40") // five million
            .put("gasPrice", "0x1d1a94a2000") // two trillion
        assertThrows(PurchaseException::class.java) {
            PurchaseProtocol.quote(quote, binding, listOf(destination), Instant.parse("2026-09-22T10:00:00Z"))
        }
    }

    @Test
    fun quoteRejectsDisplayAmountsThatDoNotExactlyMatchBaseUnits() {
        assertThrows(PurchaseException::class.java) {
            PurchaseProtocol.quote(validQuote().put("inputAmount", "99"), binding,
                listOf(destination), Instant.parse("2026-09-22T10:00:00Z"))
        }
        assertThrows(PurchaseException::class.java) {
            PurchaseProtocol.quote(validQuote().put("estimatedOutputAmount", "0.52"), binding,
                listOf(destination), Instant.parse("2026-09-22T10:00:00Z"))
        }
    }

    @Test
    fun quoteRejectsActionOnDifferentSourceNetwork() {
        val action = validQuote().getJSONArray("actions").getJSONObject(0)
            .put("network", "BASE").put("chainId", "8453")
        assertThrows(PurchaseException::class.java) {
            PurchaseProtocol.quote(validQuote().put("actions", JSONArray().put(action)), binding,
                listOf(destination), Instant.parse("2026-09-22T10:00:00Z"))
        }
    }

    @Test
    fun quoteRejectsChangedSlippage() {
        assertThrows(PurchaseException::class.java) {
            PurchaseProtocol.quote(validQuote().put("slippageBps", 500), binding,
                listOf(destination), Instant.parse("2026-09-22T10:00:00Z"))
        }
    }

    @Test
    fun statusRejectsUnrecognizedTransactionIdentifier() {
        val status = JSONObject()
            .put("schemaVersion", 1).put("quoteId", "quote-1").put("state", "EXECUTING")
            .put("step", 0).put("stepCount", 1).put("transactionIds", JSONArray().put("not-a-hash"))
            .put("receivedAmount", JSONObject.NULL).put("message", JSONObject.NULL)
            .put("updatedAt", "2026-09-22T10:00:01Z")
        assertThrows(PurchaseException::class.java) { PurchaseProtocol.status(status, "quote-1") }
    }

    @Test
    fun completedStatusAcceptsFinalSolanaSignatureAndLegacyStatusWithoutIt() {
        val signature = encodeBase58(ByteArray(64) { (it + 1).toByte() })
        val status = JSONObject()
            .put("schemaVersion", 1).put("quoteId", "quote-1").put("state", "COMPLETED")
            .put("step", 1).put("stepCount", 1).put("transactionIds", JSONArray().put(signature))
            .put("receivedAmount", "0.001").put("message", JSONObject.NULL)
            .put("updatedAt", "2026-09-22T10:00:01Z")
        assertEquals(null, PurchaseProtocol.status(status, "quote-1").solanaTransactionSignature)
        status.put("solanaTransactionSignature", signature)
        assertEquals(signature, PurchaseProtocol.status(status, "quote-1").solanaTransactionSignature)
        status.put("state", "EXECUTING")
        assertThrows(PurchaseException::class.java) { PurchaseProtocol.status(status, "quote-1") }
    }

    @Test
    fun quoteCapacityIsReportedAsRetryableRateLimit() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"quote_capacity","message":"Wait for an earlier quote to expire."}"""))
            val client = PurchaseClient(server.url("/").toString(), PurchaseAccessTokenProvider {
                "header0123456789.payload0123456789.signature0123456789"
            },
                allowLoopbackHttp = true)
            val failure = runCatching {
                client.options("did:privy:user-1", stockId, listOf(
                    UserWallet(WalletChain.SOLANA, "11111111111111111111111111111111"),
                    UserWallet(WalletChain.ETHEREUM, wallet),
                ))
            }.exceptionOrNull()
            assertTrue(failure is PurchaseException)
            failure as PurchaseException
            assertEquals(PurchaseFailureCode.RATE_LIMITED, failure.code)
            assertTrue(failure.retryable)
        }
    }

    @Test
    fun authenticationUnavailableIsReportedAsRetryableServiceFailure() =
        assertRetryableServiceFailure("authentication_unavailable")

    @Test
    fun busyIsReportedAsRetryableServiceFailure() =
        assertRetryableServiceFailure("busy")

    private fun assertRetryableServiceFailure(serverError: String) = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"$serverError","message":"Temporary service failure."}"""))
            val client = PurchaseClient(server.url("/").toString(), PurchaseAccessTokenProvider {
                "header0123456789.payload0123456789.signature0123456789"
            }, allowLoopbackHttp = true)

            val failure = runCatching {
                client.options("did:privy:user-1", stockId, listOf(
                    UserWallet(WalletChain.SOLANA, "11111111111111111111111111111111"),
                    UserWallet(WalletChain.ETHEREUM, wallet),
                ))
            }.exceptionOrNull()

            assertTrue(failure is PurchaseException)
            failure as PurchaseException
            assertEquals(PurchaseFailureCode.SERVICE_UNAVAILABLE, failure.code)
            assertEquals("The route service is unavailable. Nothing was sent.", failure.userMessage)
            assertTrue(failure.retryable)
        }
    }

    private fun validOptions() = JSONObject()
        .put("schemaVersion", 1).put("stockId", stockId).put("purchasable", true)
        .put("reason", JSONObject.NULL)
        .put("executionEnabled", false).put("executionReason", "Live execution is not enabled yet.")
        .put("paymentAssets", JSONArray().put(JSONObject()
            .put("id", "ethereum:USDC").put("symbol", "USDC").put("name", "USD Coin")
            .put("network", "ETHEREUM").put("chainId", "1")
            .put("address", "0x3333333333333333333333333333333333333333")
            .put("decimals", 6).put("balanceBaseUnits", "1500000").put("balance", "1.5")
            .put("usdValue", "1.5").put("enabled", true)))
        .put("destinations", JSONArray().put(JSONObject()
            .put("id", destination.id).put("network", "ETHEREUM").put("chainId", "1")
            .put("address", destination.address).put("symbol", "NVDAx").put("decimals", 18).put("enabled", true)))
        .put("defaultPaymentAssetId", "ethereum:USDC")
        .put("defaultDestinationId", destination.id)

    private fun validQuote(): JSONObject {
        val transaction = JSONObject()
            .put("from", wallet).put("to", "0x4444444444444444444444444444444444444444")
            .put("data", "0x1234").put("value", "0x0").put("gas", "0x5208")
            .put("gasPrice", "0x1").put("maxFeePerGas", JSONObject.NULL)
            .put("maxPriorityFeePerGas", JSONObject.NULL).put("nonce", JSONObject.NULL)
        val action = JSONObject()
            .put("id", "route-1").put("index", 0).put("type", "EVM_ROUTE")
            .put("network", "ETHEREUM").put("chainId", "1").put("walletAddress", wallet)
            .put("transaction", transaction)
        return JSONObject()
            .put("schemaVersion", 1).put("quoteId", "quote-1").put("operationId", binding.operationId)
            .put("stockId", stockId).put("fromAssetId", binding.fromAssetId).put("destinationId", destination.id)
            .put("inputAmount", "100").put("inputBaseUnits", "100000000")
            .put("estimatedOutputAmount", "0.51").put("estimatedOutputBaseUnits", "510000000000000000")
            .put("executableUnitPriceUsd", "196.0784").put("feesUsd", "0.31")
            .put("priceImpactPercent", "0.12").put("slippageBps", 50)
            .put("minimumReceived", "0.5").put("minimumReceivedBaseUnits", "500000000000000000")
            .put("expiresAt", "2026-09-22T10:10:00Z").put("walletConfirmations", 1)
            .put("executionEnabled", false).put("executionReason", "Live execution is not enabled yet.")
            .put("actions", JSONArray().put(action))
    }
}
