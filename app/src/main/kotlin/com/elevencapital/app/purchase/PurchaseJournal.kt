package com.elevencapital.app.purchase

import android.content.Context
import com.elevencapital.core.stock.flow.OrderSide
import org.json.JSONArray
import org.json.JSONObject

enum class PurchaseJournalPhase { PLANNED, COMMITTED, INVOKING, PROVIDER_INVOKED, RELEASING, BROADCAST, TRACKING }

data class PurchaseJournalRecord(
    val userId: String,
    val operationId: String,
    val quoteId: String,
    val stockId: String,
    val phase: PurchaseJournalPhase,
    val actionIndex: Int,
    val actionId: String?,
    val transactionIds: List<String>,
    val side: OrderSide = OrderSide.BUY,
) {
    init {
        require(userId.isSafeOpaqueId(256) && operationId.isSafeProtocolId() && quoteId.isSafeProtocolId())
        require(stockId.isSafeStockId() && actionIndex in 0..4)
        require(actionId == null || actionId.isSafeProtocolId())
        require(transactionIds.size <= 4)
    }
}

internal class PurchaseJournal(context: Context) : PurchaseExecutionJournalStore {
    private val preferences = context.applicationContext.getSharedPreferences("eleven-purchase-journal-v1", Context.MODE_PRIVATE)
    @Volatile var storageHealthy: Boolean = true
        private set

    @Synchronized
    fun activeForUser(userId: String): PurchaseJournalRecord? = read()?.takeIf { it.userId == userId }

    @Synchronized
    override fun begin(quote: PurchaseQuote) {
        val active = read()
        if (active != null) {
            throw PurchaseException(PurchaseFailureCode.UNRESOLVED_PURCHASE,
                "This wallet has an order in progress. Check its status before trying again.", retryable = false)
        }
        persist(PurchaseJournalRecord(quote.binding.userId, quote.binding.operationId, quote.id,
            quote.binding.stockId, PurchaseJournalPhase.PLANNED, 0, null, emptyList(), quote.binding.side))
    }

    @Synchronized
    override fun markCommitted(quote: PurchaseQuote) = update(quote.id) { it.copy(phase = PurchaseJournalPhase.COMMITTED) }

    /** Persist the exact action before asking the backend for its cross-device invocation lock. */
    @Synchronized
    override fun markInvoking(quote: PurchaseQuote, action: PurchaseAction) = update(quote.id) {
        if (action.index != it.actionIndex) purchaseProtocolFailure()
        it.copy(phase = PurchaseJournalPhase.INVOKING, actionId = action.id)
    }

    /** Must be durable after the server lock and immediately before invoking Privy's provider. */
    @Synchronized
    override fun markProviderInvoked(quote: PurchaseQuote, action: PurchaseAction) = update(quote.id) {
        if (it.phase != PurchaseJournalPhase.INVOKING || action.index != it.actionIndex || it.actionId != action.id) {
            purchaseProtocolFailure()
        }
        it.copy(phase = PurchaseJournalPhase.PROVIDER_INVOKED)
    }

    /** Session/network selection failed before eth_sendTransaction/signAndSendTransaction. */
    @Synchronized
    override fun markProviderNotInvoked(quote: PurchaseQuote, action: PurchaseAction) = update(quote.id) {
        if (it.phase != PurchaseJournalPhase.PROVIDER_INVOKED || it.actionId != action.id ||
            it.actionIndex != action.index) purchaseProtocolFailure()
        it.copy(phase = PurchaseJournalPhase.RELEASING)
    }

    @Synchronized
    override fun markBroadcast(quote: PurchaseQuote, submission: PurchaseActionSubmission) = update(quote.id) {
        if (it.phase != PurchaseJournalPhase.PROVIDER_INVOKED || it.actionId != submission.actionId ||
            it.transactionIds.size != it.actionIndex) purchaseProtocolFailure()
        it.copy(phase = PurchaseJournalPhase.BROADCAST,
            transactionIds = it.transactionIds + submission.transactionId)
    }

    @Synchronized
    override fun markReported(quote: PurchaseQuote, action: PurchaseAction) = update(quote.id) {
        if (it.phase != PurchaseJournalPhase.BROADCAST || action.index != it.actionIndex ||
            it.transactionIds.size != action.index + 1) purchaseProtocolFailure()
        it.copy(phase = PurchaseJournalPhase.TRACKING, actionIndex = action.index + 1, actionId = null)
    }

    @Synchronized
    fun markReported(record: PurchaseJournalRecord) = update(record.quoteId) {
        if (it.actionId != record.actionId || it.transactionIds != record.transactionIds ||
            it.phase != PurchaseJournalPhase.BROADCAST) purchaseProtocolFailure()
        it.copy(phase = PurchaseJournalPhase.TRACKING, actionIndex = it.actionIndex + 1, actionId = null)
    }

    @Synchronized
    override fun finish(quoteId: String) {
        if (read()?.quoteId != quoteId) return
        if (!preferences.edit().remove(RECORD).commit()) storageFailure()
    }

    @Synchronized
    private fun update(quoteId: String, block: (PurchaseJournalRecord) -> PurchaseJournalRecord) {
        val current = read()?.takeIf { it.quoteId == quoteId } ?: purchaseProtocolFailure()
        persist(block(current))
    }

    private fun persist(record: PurchaseJournalRecord) {
        if (!preferences.edit().putString(RECORD, encodePurchaseJournalRecord(record)).commit()) storageFailure()
    }

    private fun read(): PurchaseJournalRecord? {
        val raw = preferences.getString(RECORD, null) ?: return null
        return try {
            decodePurchaseJournalRecord(raw)
        } catch (_: Exception) {
            storageFailure()
        }
    }

    private fun storageFailure(): Nothing {
        storageHealthy = false
        throw PurchaseException(PurchaseFailureCode.UNRESOLVED_PURCHASE,
            "Purchase safety storage is unavailable. Nothing was sent.", retryable = false)
    }

    private companion object { const val RECORD = "active" }
}

internal fun encodePurchaseJournalRecord(record: PurchaseJournalRecord): String = JSONObject()
    .put("schemaVersion", 2)
    .put("userId", record.userId)
    .put("operationId", record.operationId)
    .put("quoteId", record.quoteId)
    .put("stockId", record.stockId)
    .put("phase", record.phase.name)
    .put("actionIndex", record.actionIndex)
    .put("actionId", record.actionId ?: JSONObject.NULL)
    .put("transactionIds", JSONArray(record.transactionIds))
    .put("side", record.side.name)
    .toString()

internal fun decodePurchaseJournalRecord(raw: String): PurchaseJournalRecord {
    val json = JSONObject(raw)
    val version = json.getInt("schemaVersion")
    val v1Keys = setOf("schemaVersion", "userId", "operationId", "quoteId", "stockId", "phase",
        "actionIndex", "actionId", "transactionIds")
    val expectedKeys = when (version) {
        1 -> v1Keys
        2 -> v1Keys + "side"
        else -> purchaseProtocolFailure()
    }
    if (json.keys().asSequence().toSet() != expectedKeys) purchaseProtocolFailure()
    val transactions = json.getJSONArray("transactionIds").let { array ->
        if (array.length() > 4) purchaseProtocolFailure()
        (0 until array.length()).map { array.getString(it) }
    }
    return PurchaseJournalRecord(
        userId = json.getString("userId"), operationId = json.getString("operationId"),
        quoteId = json.getString("quoteId"), stockId = json.getString("stockId"),
        phase = PurchaseJournalPhase.valueOf(json.getString("phase")),
        actionIndex = json.getInt("actionIndex"),
        actionId = if (json.isNull("actionId")) null else json.getString("actionId"),
        transactionIds = transactions,
        side = journalOrderSide(if (version == 1) null else json.getString("side"), version),
    )
}

/** Schema v1 was emitted before Sell existed, so its only truthful migration is Buy. */
internal fun journalOrderSide(raw: String?, schemaVersion: Int = 2): OrderSide = when (schemaVersion) {
    1 -> OrderSide.BUY
    2 -> OrderSide.entries.singleOrNull { it.name == raw } ?: purchaseProtocolFailure()
    else -> purchaseProtocolFailure()
}
