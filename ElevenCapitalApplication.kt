package com.elevencapital.app

import android.app.Application
import com.elevencapital.app.auth.PrivyAuthController
import com.elevencapital.app.wallet.WalletTransferCoordinator
import com.elevencapital.app.wallet.TransferJournal
import com.elevencapital.app.purchase.PurchaseCoordinator
import com.elevencapital.app.purchase.PurchaseJournal
import com.elevencapital.app.tokenlaunch.TokenLaunchReceiptStore
import com.elevencapital.app.communitybuy.CommunityPurchasedTokenStore
import com.elevencapital.app.data.LiveMarketDataClient
import com.elevencapital.app.data.MeteoraMarketSnapshotCache
import com.elevencapital.app.data.MeteoraMarketWarmup
import com.elevencapital.app.data.MeteoraChartSnapshotCache
import com.elevencapital.app.data.MeteoraTokenFeed
import com.elevencapital.app.data.RobinhoodFeaturedCatalogCache
import com.elevencapital.app.data.RobinhoodFeaturedClient
import com.elevencapital.app.data.RobinhoodMarketWarmup
import com.elevencapital.app.data.StartupMarketCache
import com.elevencapital.app.ui.StockArtworkCache
import java.io.File
import com.elevencapital.core.stock.ChartRange
import org.json.JSONObject

class ElevenCapitalApplication : Application() {
    lateinit var auth: PrivyAuthController
        private set
    lateinit var transferJournal: TransferJournal
        private set
    var transfers: WalletTransferCoordinator? = null
        private set
    internal lateinit var purchaseJournal: PurchaseJournal
        private set
    var purchases: PurchaseCoordinator? = null
        private set
    lateinit var tokenLaunchReceipts: TokenLaunchReceiptStore
        private set
    lateinit var communityPurchasedTokens: CommunityPurchasedTokenStore
        private set
    var robinhoodMarketWarmup: RobinhoodMarketWarmup? = null
        private set
    lateinit var meteoraTokenFeed: MeteoraTokenFeed
        private set
    lateinit var meteoraMarketWarmup: MeteoraMarketWarmup
        private set

    override fun onCreate() {
        super.onCreate()
        StockArtworkCache.warm(this)
        // Public token discovery begins before login and phone unlock; no wallet data is read.
        meteoraTokenFeed = MeteoraTokenFeed()
        meteoraMarketWarmup = MeteoraMarketWarmup(
            MeteoraMarketSnapshotCache(File(filesDir, "public-markets")),
            meteoraTokenFeed::topTokens,
            chartCache = MeteoraChartSnapshotCache(File(filesDir, "public-markets")),
            fetchChart = { token -> meteoraTokenFeed.chartFor(token, ChartRange.ONE_DAY) },
        ).also { if (BuildConfig.LIVE_MARKET_DATA) it.start() }
        // Public metadata restoration and revalidation run while Privy restores the login session.
        robinhoodMarketWarmup = BuildConfig.MARKET_DATA_URL.takeIf(String::isNotBlank)?.let { endpoint ->
            val publicClient = RobinhoodFeaturedClient(endpoint)
            val charts = LiveMarketDataClient(endpoint)
            RobinhoodMarketWarmup(
                RobinhoodFeaturedCatalogCache(File(filesDir, "public-markets"), endpoint),
                StartupMarketCache(File(filesDir, "public-markets/robinhood-charts"), endpoint),
                fetchCatalog = publicClient::catalog, fetchChart = charts::chart,
                seedCatalog = {
                    try { assets.open("robinhood-featured-seed.json").bufferedReader().use { JSONObject(it.readText()) } }
                    catch (_: Exception) { null }
                },
            ).also { it.start() }
        }
        // Application.onCreate runs on the main thread, as required by Privy's Android SDK.
        transferJournal = TransferJournal(this)
        purchaseJournal = PurchaseJournal(this)
        tokenLaunchReceipts = TokenLaunchReceiptStore(this)
        communityPurchasedTokens = CommunityPurchasedTokenStore(this)
        auth = PrivyAuthController(this, transferJournal)
        transfers = BuildConfig.MARKET_DATA_URL.takeIf(String::isNotBlank)
            ?.let { WalletTransferCoordinator(it, auth, transferJournal, allowLoopbackHttp = BuildConfig.DEBUG) }
        purchases = BuildConfig.MARKET_DATA_URL.takeIf(String::isNotBlank)
            ?.let { PurchaseCoordinator(it, auth, purchaseJournal, allowLoopbackHttp = BuildConfig.DEBUG) }
    }
}
