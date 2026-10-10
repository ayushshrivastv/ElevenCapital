package com.elevencapital.app.purchase

import com.elevencapital.app.auth.UserWallet
import com.elevencapital.app.auth.WalletChain
import com.elevencapital.app.wallet.encodeBase58
import com.elevencapital.core.stock.flow.OrderSide
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.util.Base64
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
    private val wallet = "4vJ9JU1bJJE96FWSJKvHsmmFADCg4gpZQff4P3bkLKi"
    private val destination = PurchaseDestination(
        "nvda-solana", PurchaseNetwork.SOLANA,
        "So11111111111111111111111111111111111111112", "NVDAx", 8, true,
    )
    private val binding = PurchaseQuoteBinding(
        operationId = "123e4567-e89b-42d3-a456-426614174000",
        userId = "did:privy:user-1", stockId = stockId, fromAssetId = "SOLANA:USDC",
        fromNetwork = PurchaseNetwork.SOLANA, inputDecimals = 6,
        requestedDestinationId = null, inputBaseUnits = BigInteger("100000000"),
        slippageBps = 50, walletAddresses = setOf(wallet),
    )

    @Test
    fun optionsRequireExactBalancesAndVerifiedSolanaDestinations() {
        val parsed = PurchaseProtocol.options(validOptions(), stockId)
        assertEquals("SOLANA:USDC", parsed.defaultPaymentAssetId)
        assertEquals("1.5", parsed.paymentAssets.single().balance.toPlainString())
        assertEquals(PurchaseNetwork.SOLANA, parsed.destinations.single().network)
    }

    @Test
    fun robinhoodListingIdentityDoesNotEnableAnUnsupportedSigningNetwork() {
        assertTrue("robinhood:MSFT".isSafeStockId())
        assertThrows(PurchaseException::class.java) { PurchaseNetwork.parse("ROBINHOOD_CHAIN", "4663") }
        assertThrows(PurchaseException::class.java) { PurchaseNetwork.parse("UNKNOWN", "1") }
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
        val scaledDestination = destination.copy(uiMultiplier = multiplier)
        val scaledBinding = binding.copy(inputDecimals = 8, inputBaseUnits = BigInteger("100000000"),
            inputUiMultiplier = multiplier)
        val source = validQuote()
            .put("inputAmount", "1.00590339")
            .put("outputDecimals", 8)
            .put("outputUiMultiplier", multiplier.toPlainString())
            .put("estimatedOutputAmount", "1.00590339")
            .put("estimatedOutputBaseUnits", "100000000")
            .put("minimumReceived", "0.99584435")
            .put("minimumReceivedBaseUnits", "99000000")

        val quote = PurchaseProtocol.quote(source, scaledBinding, listOf(scaledDestination), NOW)

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
            PurchaseProtocol.quote(validQuote().put("outputDecimals", 8), binding, listOf(destination), NOW)
        }
        assertThrows(PurchaseException::class.java) {
            PurchaseProtocol.quote(validQuote().put("outputDecimals", 8).put("outputUiMultiplier", "1.01"),
                binding, listOf(destination), NOW)
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
            server.enqueue(jsonResponse(validOptions()))
            val client = client(server)

            client.options(binding.userId, stockId, wallets(), OrderSide.SELL)

            val sent = JSONObject(requireNotNull(server.takeRequest().body.readUtf8()))
            assertEquals("SELL", sent.getString("side"))
            assertEquals(1, sent.getJSONArray("wallets").length())
        }
    }

    @Test
    fun quoteRequestBindsAndSendsExplicitSellSide() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(validQuote()))

            val quote = client(server).quote(PurchaseQuoteRequest(
                operationId = binding.operationId,
                userId = binding.userId,
                stockId = stockId,
                fromAssetId = binding.fromAssetId,
                fromNetwork = binding.fromNetwork,
                inputDecimals = binding.inputDecimals,
                destinationId = null,
                amountBaseUnits = binding.inputBaseUnits,
                slippageBps = binding.slippageBps,
                wallets = wallets(),
                destinations = listOf(destination),
                side = OrderSide.SELL,
            ))

            val sent = JSONObject(requireNotNull(server.takeRequest().body.readUtf8()))
            assertEquals("SELL", sent.getString("side"))
            assertEquals(OrderSide.SELL, quote.binding.side)
        }
    }

    @Test
    fun quoteBindsOperationWalletAmountsAndSolanaActionPlan() {
        val quote = PurchaseProtocol.quote(validQuote(), binding, listOf(destination), NOW)
        assertEquals(binding.operationId, quote.binding.operationId)
        assertEquals(1, quote.walletConfirmations)
        assertEquals(PurchaseActionKind.SOLANA_ROUTE, quote.actions.single().kind)
        assertEquals("0.5", quote.minimumReceived.toPlainString())
    }

    @Test
    fun quoteRejectsChangedBindingsUnknownFieldsAndForeignNetworks() {
        assertThrows(PurchaseException::class.java) {
            PurchaseProtocol.quote(validQuote().put("operationId", "123e4567-e89b-42d3-a456-426614174001"),
                binding, listOf(destination), NOW)
        }
        assertThrows(PurchaseException::class.java) {
            PurchaseProtocol.quote(validQuote().put("rawRouterPayload", "must not pass through"),
                binding, listOf(destination), NOW)
        }
        val action = validQuote().getJSONArray("actions").getJSONObject(0)
            .put("network", "UNKNOWN").put("chainId", "1")
        assertThrows(PurchaseException::class.java) {
            PurchaseProtocol.quote(validQuote().put("actions", JSONArray().put(action)), binding,
                listOf(destination), NOW)
        }
    }

    @Test
    fun quoteRejectsDisplayAmountsAndSlippageThatDoNotMatchTheRequest() {
        assertThrows(PurchaseException::class.java) {
            PurchaseProtocol.quote(validQuote().put("inputAmount", "99"), binding, listOf(destination), NOW)
        }
        assertThrows(PurchaseException::class.java) {
            PurchaseProtocol.quote(validQuote().put("estimatedOutputAmount", "0.52"), binding,
                listOf(destination), NOW)
        }
        assertThrows(PurchaseException::class.java) {
            PurchaseProtocol.quote(validQuote().put("slippageBps", 500), binding, listOf(destination), NOW)
        }
    }

    @Test
    fun statusAcceptsOnlyCanonicalSolanaTransactionIdentifiers() {
        val invalid = status("EXECUTING", JSONArray().put("not-a-signature"))
        assertThrows(PurchaseException::class.java) { PurchaseProtocol.status(invalid, "quote-1") }

        val signature = encodeBase58(ByteArray(64) { (it + 1).toByte() })
        val completed = status("COMPLETED", JSONArray().put(signature))
            .put("step", 1).put("receivedAmount", "0.001")
        assertEquals(null, PurchaseProtocol.status(completed, "quote-1").solanaTransactionSignature)
        completed.put("solanaTransactionSignature", signature)
        assertEquals(signature, PurchaseProtocol.status(completed, "quote-1").solanaTransactionSignature)
        completed.put("state", "EXECUTING")
        assertThrows(PurchaseException::class.java) { PurchaseProtocol.status(completed, "quote-1") }
    }

    @Test
    fun routeCapacityAndTemporaryServiceFailuresRemainRetryable() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(errorResponse(429, "quote_capacity"))
            server.enqueue(errorResponse(503, "busy"))
            val client = client(server)

            val capacity = runCatching { client.options(binding.userId, stockId, wallets()) }.exceptionOrNull()
                as PurchaseException
            assertEquals(PurchaseFailureCode.RATE_LIMITED, capacity.code)
            assertTrue(capacity.retryable)

            val busy = runCatching { client.options(binding.userId, stockId, wallets()) }.exceptionOrNull()
                as PurchaseException
            assertEquals(PurchaseFailureCode.SERVICE_UNAVAILABLE, busy.code)
            assertTrue(busy.retryable)
        }
    }

    private fun validOptions() = JSONObject()
        .put("schemaVersion", 1).put("stockId", stockId).put("purchasable", true)
        .put("reason", JSONObject.NULL)
        .put("executionEnabled", false).put("executionReason", "Live execution is not enabled yet.")
        .put("paymentAssets", JSONArray().put(JSONObject()
            .put("id", "SOLANA:USDC").put("symbol", "USDC").put("name", "USD Coin")
            .put("network", "SOLANA").put("chainId", PurchaseNetwork.SOLANA.chainId)
            .put("address", "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v")
            .put("decimals", 6).put("balanceBaseUnits", "1500000").put("balance", "1.5")
            .put("usdValue", "1.5").put("enabled", true)))
        .put("destinations", JSONArray().put(JSONObject()
            .put("id", destination.id).put("network", "SOLANA").put("chainId", PurchaseNetwork.SOLANA.chainId)
            .put("address", destination.address).put("symbol", destination.symbol)
            .put("decimals", destination.decimals).put("enabled", true)))
        .put("defaultPaymentAssetId", "SOLANA:USDC")
        .put("defaultDestinationId", destination.id)

    private fun validQuote(): JSONObject {
        val action = JSONObject()
            .put("id", "route-1").put("index", 0).put("type", "SOLANA_ROUTE")
            .put("network", "SOLANA").put("chainId", PurchaseNetwork.SOLANA.chainId)
            .put("walletAddress", wallet)
            .put("transactionBase64", Base64.getEncoder().encodeToString(ByteArray(64) { 1 }))
            .put("minContextSlot", JSONObject.NULL).put("lastValidBlockHeight", JSONObject.NULL)
        return JSONObject()
            .put("schemaVersion", 1).put("quoteId", "quote-1").put("operationId", binding.operationId)
            .put("stockId", stockId).put("fromAssetId", binding.fromAssetId).put("destinationId", destination.id)
            .put("inputAmount", "100").put("inputBaseUnits", "100000000")
            .put("estimatedOutputAmount", "0.51").put("estimatedOutputBaseUnits", "51000000")
            .put("executableUnitPriceUsd", "196.0784").put("feesUsd", "0.31")
            .put("priceImpactPercent", "0.12").put("slippageBps", 50)
            .put("minimumReceived", "0.5").put("minimumReceivedBaseUnits", "50000000")
            .put("expiresAt", "2026-09-22T10:10:00Z").put("walletConfirmations", 1)
            .put("executionEnabled", false).put("executionReason", "Live execution is not enabled yet.")
            .put("actions", JSONArray().put(action))
    }

    private fun status(state: String, ids: JSONArray) = JSONObject()
        .put("schemaVersion", 1).put("quoteId", "quote-1").put("state", state)
        .put("step", 0).put("stepCount", 1).put("transactionIds", ids)
        .put("receivedAmount", JSONObject.NULL).put("message", JSONObject.NULL)
        .put("updatedAt", "2026-09-22T10:00:01Z")

    private fun wallets() = listOf(UserWallet(WalletChain.SOLANA, wallet))

    private fun client(server: MockWebServer) = PurchaseClient(
        server.url("/").toString(),
        PurchaseAccessTokenProvider { "header0123456789.payload0123456789.signature0123456789" },
        now = { NOW },
        allowLoopbackHttp = true,
    )

    private fun jsonResponse(body: JSONObject) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/json").setBody(body.toString())

    private fun errorResponse(status: Int, code: String) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"error":"$code","message":"Temporary service failure."}""")

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-22T10:00:00Z")
    }
}
