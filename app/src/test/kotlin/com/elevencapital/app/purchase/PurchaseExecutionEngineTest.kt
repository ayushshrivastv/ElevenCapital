package com.elevencapital.app.purchase

import com.elevencapital.core.stock.flow.OrderSide
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseExecutionEngineTest {
    private val now = Instant.parse("2026-09-22T10:00:00Z")

    @Test
    fun `compiled gate blocks server-enabled quote before commit and broadcaster`() = runBlocking {
        val events = mutableListOf<String>()
        val engine = engine(events, compiledEnabled = false)

        val failure = assertThrows(PurchaseException::class.java) {
            runBlocking { engine.execute(USER, quote(), PurchaseNetwork.ETHEREUM) {} }
        }

        assertEquals(PurchaseFailureCode.NOT_PURCHASABLE, failure.code)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `server disabled quote blocks before commit and broadcaster`() = runBlocking {
        val events = mutableListOf<String>()
        val engine = engine(events, compiledEnabled = true)

        val failure = assertThrows(PurchaseException::class.java) {
            runBlocking { engine.execute(USER, quote(executionEnabled = false), PurchaseNetwork.ETHEREUM) {} }
        }

        assertEquals(PurchaseFailureCode.NOT_PURCHASABLE, failure.code)
        assertEquals("Provider route is preview only.", failure.userMessage)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `validated Solana route reaches broadcaster when quote enables execution`() = runBlocking {
        val events = mutableListOf<String>()

        engine(events, compiledEnabled = true)
            .execute(USER, quote(network = PurchaseNetwork.SOLANA), PurchaseNetwork.SOLANA) {}

        assertTrue("provider:route-1" in events)
        assertTrue("backend:submitted:route-1" in events)
    }

    @Test
    fun `invoking lock is acknowledged before provider and broadcast is reported in order`() = runBlocking {
        val events = mutableListOf<String>()
        val engine = engine(events, compiledEnabled = true)

        engine.execute(USER, quote(), PurchaseNetwork.ETHEREUM) {}

        assertEquals(listOf(
            "journal:begin", "backend:commit", "journal:committed",
            "journal:invoking:route-1", "backend:invoking:route-1",
            "journal:provider:route-1", "provider:route-1", "journal:broadcast:route-1",
            "backend:submitted:route-1", "journal:reported:route-1",
        ), events)
    }

    @Test
    fun `definite failure before provider is released and never reported as submitted`() = runBlocking {
        val events = mutableListOf<String>()
        val engine = engine(events, compiledEnabled = true, providerFailure = PurchaseException(
            PurchaseFailureCode.WALLET_REJECTED, "Nothing was submitted.", true, providerInvoked = false))

        assertThrows(PurchaseException::class.java) {
            runBlocking { engine.execute(USER, quote(), PurchaseNetwork.ETHEREUM) {} }
        }

        assertTrue(events.indexOf("backend:invoking:route-1") < events.indexOf("provider:route-1"))
        assertTrue("journal:releasing:route-1" in events)
        assertTrue("backend:release:route-1" in events)
        assertTrue("journal:finish" in events)
        assertFalse(events.any { it.startsWith("backend:submitted") })
    }

    @Test
    fun `uncertain provider result stays unresolved without release or retry`() = runBlocking {
        val events = mutableListOf<String>()
        val engine = engine(events, compiledEnabled = true, providerFailure = PurchaseException(
            PurchaseFailureCode.SUBMISSION_UNCERTAIN, "Status only.", false, providerInvoked = true))

        val failure = assertThrows(PurchaseException::class.java) {
            runBlocking { engine.execute(USER, quote(), PurchaseNetwork.ETHEREUM) {} }
        }

        assertTrue(failure.providerInvoked)
        assertEquals(1, events.count { it == "provider:route-1" })
        assertFalse(events.any { it.contains("release") || it.startsWith("backend:submitted") })
    }

    @Test
    fun `approval must be confirmed before dependent route reaches provider`() = runBlocking {
        val events = mutableListOf<String>()
        val engine = engine(events, compiledEnabled = true, confirmApprovalOnStatus = true)

        engine.execute(USER, quote(actionCount = 2), PurchaseNetwork.ETHEREUM) {}

        assertTrue(events.indexOf("backend:status") < events.indexOf("backend:invoking:route-2"))
        assertTrue(events.indexOf("backend:status") < events.indexOf("provider:route-2"))
    }

    @Test
    fun `restart boundaries never reinterpret a hashless provider call as retryable`() {
        assertEquals(PurchaseRecoveryDirective.STATUS_ONLY,
            recoveryDirective(record(PurchaseJournalPhase.PROVIDER_INVOKED)))
        val broadcast = record(PurchaseJournalPhase.BROADCAST, HASH)
        assertEquals(PurchaseRecoveryDirective.REPORT_SAVED_HASH, recoveryDirective(broadcast))
        assertEquals(PurchaseActionSubmission("route-1", HASH), savedSubmissionForRecovery(broadcast))
        assertEquals(null, savedSubmissionForRecovery(record(PurchaseJournalPhase.PROVIDER_INVOKED)))
        assertEquals(PurchaseRecoveryDirective.RELEASE_INVOCATION,
            recoveryDirective(record(PurchaseJournalPhase.INVOKING)))
        assertEquals(PurchaseRecoveryDirective.STATUS_THEN_CLEAR,
            recoveryDirective(record(PurchaseJournalPhase.COMMITTED)))
    }

    @Test
    fun `same stock state cannot be reused after account switch`() {
        assertTrue(canReusePurchaseState(USER, USER, STOCK, STOCK, PurchasePhase.REVIEW))
        assertFalse(canReusePurchaseState(USER, "did:privy:other", STOCK, STOCK, PurchasePhase.REVIEW))
        assertFalse(canReusePurchaseState(USER, USER, STOCK, STOCK, PurchasePhase.REVIEW,
            OrderSide.BUY, OrderSide.SELL))
    }

    private fun engine(
        events: MutableList<String>,
        compiledEnabled: Boolean = false,
        providerFailure: PurchaseException? = null,
        confirmApprovalOnStatus: Boolean = false,
    ): PurchaseExecutionEngine {
        val backend = FakeBackend(events, confirmApprovalOnStatus)
        val journal = FakeJournal(events)
        return PurchaseExecutionEngine(
            backend = backend,
            journal = journal,
            broadcaster = PurchaseActionBroadcaster { _, action ->
                events += "provider:${action.id}"
                providerFailure?.let { throw it }
                PurchaseActionSubmission(action.id, HASH)
            },
            sessionIsCurrent = { it == USER },
            now = { now },
            wait = {},
            compiledExecutionEnabled = compiledEnabled,
        )
    }

    private fun quote(
        actionCount: Int = 1,
        network: PurchaseNetwork = PurchaseNetwork.ETHEREUM,
        executionEnabled: Boolean = true,
    ): PurchaseQuote {
        require(network == PurchaseNetwork.ETHEREUM || actionCount == 1)
        val actions = (0 until actionCount).map { index ->
            val id = if (index == 0 && actionCount == 1) "route-1" else if (index == 0) "approval-1" else "route-2"
            if (network == PurchaseNetwork.SOLANA) {
                SolanaPurchaseAction(id, index, PurchaseActionKind.SOLANA_ROUTE, network,
                    SOLANA_WALLET, ByteArray(64), null, null)
            } else {
                EvmPurchaseAction(
                    id = id,
                    index = index,
                    kind = if (index == 0 && actionCount == 2) PurchaseActionKind.EVM_APPROVAL else PurchaseActionKind.EVM_ROUTE,
                    network = network,
                    walletAddress = WALLET,
                    transaction = EvmPurchaseTransaction(WALLET, TO, "0x", "0x0", "0x5208", "0x1", null, null, null),
                )
            }
        }
        return PurchaseQuote(
            id = "quote-1",
            binding = PurchaseQuoteBinding(OPERATION, USER, STOCK, "ethereum:USDC",
                network, 6, null, BigInteger("100000000"), 50,
                setOf(if (network == PurchaseNetwork.SOLANA) SOLANA_WALLET else WALLET)),
            destination = PurchaseDestination("nvda-destination", network,
                if (network == PurchaseNetwork.SOLANA) SOLANA_DESTINATION else DESTINATION, "NVDAx", 18, true),
            inputAmount = BigDecimal("100"),
            estimatedOutputAmount = BigDecimal("0.5"),
            executableUnitPriceUsd = BigDecimal("200"),
            feesUsd = BigDecimal("0.5"),
            priceImpactPercent = BigDecimal("0.1"),
            slippageBps = 50,
            minimumReceived = BigDecimal("0.49"),
            minimumReceivedBaseUnits = BigInteger("490000000000000000"),
            expiresAt = now.plusSeconds(600),
            walletConfirmations = actionCount,
            actions = actions,
            executionEnabled = executionEnabled,
            executionReason = if (executionEnabled) null else "Provider route is preview only.",
        )
    }

    private fun record(phase: PurchaseJournalPhase, transactionId: String? = null) = PurchaseJournalRecord(
        USER, OPERATION, "quote-1", STOCK, phase, 0, "route-1", listOfNotNull(transactionId))

    private class FakeBackend(
        private val events: MutableList<String>,
        private val confirmApprovalOnStatus: Boolean,
    ) : PurchaseExecutionBackend {
        override suspend fun commit(userId: String, quote: PurchaseQuote): Boolean {
            events += "backend:commit"; return false
        }
        override suspend fun invoking(userId: String, quote: PurchaseQuote, action: PurchaseAction): Boolean {
            events += "backend:invoking:${action.id}"; return false
        }
        override suspend fun releaseDefinitelyNotInvoked(userId: String, quoteId: String, actionId: String): PurchaseStatus {
            events += "backend:release:$actionId"; return status(0)
        }
        override suspend fun submitted(
            userId: String,
            quote: PurchaseQuote,
            submission: PurchaseActionSubmission,
        ): PurchaseStatus {
            events += "backend:submitted:${submission.actionId}"
            return status(if (submission.actionId == "route-2") 1 else 0, quote.actions.size)
        }
        override suspend fun status(userId: String, quoteId: String): PurchaseStatus {
            events += "backend:status"
            return status(if (confirmApprovalOnStatus) 1 else 0, 2)
        }
        private fun status(step: Int, count: Int = 1) = PurchaseStatus(
            "quote-1", PurchaseRouteState.EXECUTING, step, count, listOf(HASH), null, null,
            Instant.parse("2026-09-22T10:00:01Z"))
    }

    private class FakeJournal(private val events: MutableList<String>) : PurchaseExecutionJournalStore {
        override fun begin(quote: PurchaseQuote) { events += "journal:begin" }
        override fun markCommitted(quote: PurchaseQuote) { events += "journal:committed" }
        override fun markInvoking(quote: PurchaseQuote, action: PurchaseAction) { events += "journal:invoking:${action.id}" }
        override fun markProviderInvoked(quote: PurchaseQuote, action: PurchaseAction) { events += "journal:provider:${action.id}" }
        override fun markProviderNotInvoked(quote: PurchaseQuote, action: PurchaseAction) { events += "journal:releasing:${action.id}" }
        override fun markBroadcast(quote: PurchaseQuote, submission: PurchaseActionSubmission) { events += "journal:broadcast:${submission.actionId}" }
        override fun markReported(quote: PurchaseQuote, action: PurchaseAction) { events += "journal:reported:${action.id}" }
        override fun finish(quoteId: String) { events += "journal:finish" }
    }

    private companion object {
        const val USER = "did:privy:user-1"
        const val STOCK = "backed:nvda"
        const val OPERATION = "123e4567-e89b-42d3-a456-426614174000"
        const val WALLET = "0x1111111111111111111111111111111111111111"
        const val TO = "0x2222222222222222222222222222222222222222"
        const val DESTINATION = "0x3333333333333333333333333333333333333333"
        const val SOLANA_WALLET = "11111111111111111111111111111111"
        const val SOLANA_DESTINATION = "So11111111111111111111111111111111111111112"
        const val HASH = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
