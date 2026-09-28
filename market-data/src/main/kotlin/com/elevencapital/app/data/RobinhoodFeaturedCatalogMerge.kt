package com.elevencapital.app.data

import com.elevencapital.core.stock.StockId
import com.elevencapital.core.stock.StockMarketActivity
import com.elevencapital.core.stock.isReferenceDataFresh
import java.time.Instant
import java.util.Locale

data class RobinhoodCatalogMerge(val catalog: LiveCatalog, val cachedIds: Set<StockId>)

private val robinhoodDeploymentAddress = Regex("0x[0-9a-fA-F]{40}")
private val robinhoodFeaturedSymbolSet = RobinhoodFeaturedSymbols.toSet()

/** A partial source response can recover independently without removing public startup references. */
fun mergeRobinhoodFeaturedCatalog(
    full: LiveCatalog,
    featured: LiveCatalog?,
    featuredCached: Boolean,
    now: Instant,
): RobinhoodCatalogMerge {
    if (featured == null) return RobinhoodCatalogMerge(full, emptySet())
    val selected = linkedMapOf<StockId, LiveInstrument>()
    val selectedIdentities = mutableMapOf<StockId, String>()
    for (instrument in featured.instruments) {
        if (instrument.provider != "robinhood" || instrument.stock.symbol !in robinhoodFeaturedSymbolSet) continue
        val identity = robinhoodDeploymentIdentity(instrument) ?: continue
        selected[instrument.stock.id] = instrument
        selectedIdentities[instrument.stock.id] = identity
    }
    if (selected.isEmpty()) return RobinhoodCatalogMerge(full, emptySet())
    val providerReady = full.providers.firstOrNull { it.id == "robinhood" }?.status == "ok"
    val replacements = mutableMapOf<StockId, LiveInstrument>()
    val presentIds = mutableSetOf<StockId>()
    val presentRobinhoodSymbols = mutableSetOf<String>()
    for (incoming in full.instruments) {
        presentIds.add(incoming.stock.id)
        if (incoming.provider != "robinhood") continue
        presentRobinhoodSymbols.add(incoming.stock.symbol)
        val fallback = selected[incoming.stock.id] ?: continue
        if (incoming.stock.symbol != fallback.stock.symbol ||
            robinhoodDeploymentIdentity(incoming) != selectedIdentities[fallback.stock.id]) continue
        val incomingFresh = incoming.stock.quote.price != null &&
            isReferenceDataFresh(incoming.quoteReceivedAt, now, 0)
        val fallbackFresh = fallback.stock.quote.price != null &&
            isReferenceDataFresh(fallback.quoteReceivedAt, now, 0)
        val fallbackNewer = incoming.stock.quote.price == null || fallback.quoteReceivedAt?.let { receipt ->
            incoming.quoteReceivedAt?.let { receipt > it } ?: true
        } == true
        val useFeaturedQuote = fallback.stock.quote.price != null && fallbackNewer &&
            (fallbackFresh || featuredCached && !providerReady && !incomingFresh)
        val incomingActivity = incoming.stock.activity
        val fallbackActivity = fallback.stock.activity
        val fallbackActivityAt = fallbackActivity?.receivedAt
        val incomingActivityAt = incomingActivity?.receivedAt
        val useFeaturedActivity = fallbackActivity != null && freshRobinhoodActivity(fallbackActivity, now) &&
            (incomingActivity == null || !freshRobinhoodActivity(incomingActivity, now) ||
                fallbackActivityAt != null && incomingActivityAt != null &&
                    (fallbackActivityAt > incomingActivityAt ||
                        fallbackActivityAt == incomingActivityAt &&
                            (fallbackActivity.volume24h != null && incomingActivity.volume24h == null ||
                                fallbackActivity.netVolume24h != null && incomingActivity.netVolume24h == null)))
        if (!useFeaturedQuote && !useFeaturedActivity) continue
        val quoteSource = if (useFeaturedQuote) fallback else incoming
        val activity = if (useFeaturedActivity) fallbackActivity else incomingActivity
        replacements[incoming.stock.id] = incoming.copy(
            stock = incoming.stock.copy(quote = quoteSource.stock.quote,
                robinhoodReference = quoteSource.stock.robinhoodReference, activity = activity),
            quoteReceivedAt = quoteSource.quoteReceivedAt,
            quoteBasis = quoteSource.quoteBasis,
            currencyBasis = quoteSource.currencyBasis,
            volume24h = activity?.volume24h,
            marketState = quoteSource.marketState,
            marketStateReason = quoteSource.marketStateReason,
        )
    }
    val candidates = selected.values.filter {
        it.stock.id !in presentIds && it.stock.symbol !in presentRobinhoodSymbols
    }
    // Only missing symbols need a deployment collision check. Index Robinhood rows
    // once instead of rescanning the entire multi-provider catalog for each symbol.
    val presentRobinhoodIdentities = if (candidates.isEmpty()) emptySet() else full.instruments
        .asSequence().filter { it.provider == "robinhood" }.mapNotNull(::robinhoodDeploymentIdentity).toSet()
    val addedBySymbol = candidates.filter { selectedIdentities[it.stock.id] !in presentRobinhoodIdentities }
        .groupBy { it.stock.symbol }
    val added = RobinhoodFeaturedSymbols.flatMap { addedBySymbol[it].orEmpty() }
    val fallbackIds = replacements.keys + added.map { it.stock.id }
    if (fallbackIds.isEmpty()) return RobinhoodCatalogMerge(full, emptySet())
    val cachedIds = if (featuredCached) fallbackIds else emptySet()
    val featuredProvider = featured.providers.firstOrNull { it.id == "robinhood" }
    val providers = full.providers.map { status ->
        if (status.id != "robinhood") status
        else if (cachedIds.isNotEmpty()) status.copy(status = "stale")
        else if (!providerReady) featuredProvider ?: status else status
    }
    return RobinhoodCatalogMerge(full.copy(
        instruments = full.instruments.map { replacements[it.stock.id] ?: it } + added,
        providers = providers,
        // Composite time describes the latest contributing snapshot, never a new observation.
        receivedAt = maxOf(full.receivedAt, featured.receivedAt),
    ), cachedIds)
}

/** Issuer identity excludes product imagery and explorer URLs, which can change independently. */
private fun robinhoodDeploymentIdentity(instrument: LiveInstrument): String? {
    if (instrument.provider != "robinhood" || !instrument.stock.id.value.startsWith("robinhood:")) return null
    val deployment = instrument.stock.deployments.singleOrNull() ?: return null
    val decimals = deployment.decimals ?: return null
    if (deployment.network != "Robinhood Chain" || deployment.chainId != "4663" || decimals !in 0..36 ||
        !deployment.address.matches(robinhoodDeploymentAddress)) return null
    return "${deployment.network}:${deployment.chainId}:${deployment.address.lowercase(Locale.ROOT)}:${deployment.decimals}"
}

private fun freshRobinhoodActivity(activity: StockMarketActivity?, now: Instant): Boolean =
    activity != null && activity.source == "GeckoTerminal" && activity.scope == "robinhood_chain_token" &&
        activity.currencyCode == "USD" && (activity.volume24h != null || activity.netVolume24h != null) &&
        isReferenceDataFresh(activity.receivedAt, now, 0) &&
        (activity.updatedAt == null || isReferenceDataFresh(activity.updatedAt, now, 0))

/** Re-subscribing to an Application's retained snapshot cannot reset its observation age. */
fun robinhoodCatalogReceiptElapsed(snapshotAt: Instant, wallNowMillis: Long, elapsedNowMillis: Long): Long =
    elapsedNowMillis - (wallNowMillis - snapshotAt.toEpochMilli()).coerceAtLeast(0)

/** A later composite snapshot uses its own arrival; retained observations keep their original age. */
fun robinhoodCompositeReceiptElapsed(
    compositeAt: Instant,
    fullAt: Instant,
    fullReceivedElapsed: Long,
    featuredAt: Instant?,
    featuredReceivedElapsed: Long,
): Long = if (featuredAt != null && compositeAt == featuredAt && featuredAt > fullAt)
    featuredReceivedElapsed else fullReceivedElapsed
