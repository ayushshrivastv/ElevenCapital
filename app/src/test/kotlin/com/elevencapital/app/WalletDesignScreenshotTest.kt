package com.elevencapital.app

import android.app.Application
import android.content.SharedPreferences
import android.view.ViewGroup.LayoutParams
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.NightMode
import com.elevencapital.app.auth.UserWallet
import com.elevencapital.app.auth.WalletChain
import com.elevencapital.app.screens.HomeScreen
import com.elevencapital.app.screens.ReceiveWalletScreen
import com.elevencapital.app.screens.SendWalletScreen
import com.elevencapital.app.ui.LocalReferenceScale
import com.elevencapital.app.ui.WalletStyle
import com.elevencapital.app.wallet.TransferDraft
import com.elevencapital.app.wallet.TransferJournalRecord
import com.elevencapital.app.wallet.TransferJournalState
import com.elevencapital.app.wallet.TransferSessionIdentity
import com.elevencapital.app.wallet.WalletAssetId
import com.elevencapital.app.wallet.WalletNetworkId
import java.math.BigDecimal
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Native render QA for the supplied wallet references at the reference, S22, and narrow widths.
 * Wallet identities, amounts, and journal rows are explicit test fixtures; no SDK or network
 * session is created. These stable screens produce ordinary PNGs rather than animation frames.
 * Recording produces review candidates, not proof of pixel parity with the supplied images.
 */
@RunWith(Parameterized::class)
class WalletDesignScreenshotTest(private val viewportWidth: Int, private val viewportHeight: Int) {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenWidth = viewportWidth,
            screenHeight = viewportHeight,
            xdpi = 160,
            ydpi = 160,
            density = Density.MEDIUM,
            nightMode = NightMode.NIGHT,
            fontScale = 1f,
            locale = "en-rUS",
            softButtons = false,
        ),
        theme = "Theme.ElevenCapital",
        showSystemUi = true,
        useDeviceResolution = true,
        maxPercentDifference = 0.0,
    )

    @Test
    fun connectedHomeWithTransactions() = capture("home_connected") {
        HomeScreen(
            balance = BigDecimal("4535.13"),
            walletAddress = solanaWallet.address,
            walletConnected = true,
            displayName = "Shawn Example",
            transactions = sampleTransfers(),
            historyStorageHealthy = true,
            onStocks = {},
            onReceive = {},
            onSend = {},
            onManage = {},
            onHistory = {},
            greeting = "Good morning",
        )
        StockDock(RootTab.Home, {}, Modifier.align(Alignment.BottomCenter))
    }

    @Test
    fun homeWhileWalletConnects() = capture("home_connecting") {
        HomeScreen(
            balance = null,
            walletAddress = null,
            walletConnected = false,
            displayName = null,
            transactions = emptyList(),
            historyStorageHealthy = true,
            onStocks = {},
            onReceive = {},
            onSend = {},
            onManage = {},
            onHistory = {},
            balancePlaceholder = "—",
            greeting = "Good morning",
        )
        StockDock(RootTab.Home, {}, Modifier.align(Alignment.BottomCenter))
    }

    @Test
    fun receiveDefaultsToEthereumWithBothWallets() = capture("receive_ethereum") {
        ReceiveWalletScreen(wallets = listOf(solanaWallet, ethereumWallet), onBack = {})
    }

    @Test
    fun receiveSolanaWallet() = capture("receive_solana") {
        ReceiveWalletScreen(wallets = listOf(solanaWallet), onBack = {})
    }

    @Test
    fun receiveWithoutConnectedWallet() = capture("receive_empty") {
        ReceiveWalletScreen(wallets = emptyList(), onBack = {})
    }

    @Test
    fun sendRecipientForm() = capture("send_recipient") {
        SendWalletScreen(
            userId = "privy:wallet-design-fixture",
            wallets = listOf(ethereumWallet, solanaWallet),
            onBack = {},
            onPrepare = { error("Render fixtures cannot prepare transfers") },
            onSubmit = { error("Render fixtures cannot submit transfers") },
            onCheckStatus = { _, _ -> error("Render fixtures cannot query transfer status") },
        )
    }

    @Test
    fun verifiedAccountEntryOpensHomeWithoutResettingAnUnchangedSession() {
        assumeTrue("Live endpoint is required to construct the live view model", BuildConfig.MARKET_DATA_URL.isNotBlank())
        val application = object : Application() {
            init { attachBaseContext(paparazzi.context) }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                // Layoutlib's editor is a stub. Seed the empty-watchlist condition so this
                // navigation regression exercises no unsupported disk-persistence behavior.
                val preferences = paparazzi.context.getSharedPreferences(name, mode)
                return object : SharedPreferences by preferences {
                    override fun contains(key: String?): Boolean = key == "stockIds"
                }
            }
        }
        val model = ElevenViewModel(application, isLive = true)
        assertEquals(RootTab.Home, model.tab.value)
        model.selectTab(RootTab.Markets)
        model.setAuthenticatedUser("privy:wallet-design-first")
        assertEquals(RootTab.Home, model.tab.value)
        model.selectTab(RootTab.Account)
        model.setAuthenticatedUser("privy:wallet-design-first")
        assertEquals(RootTab.Account, model.tab.value)
        model.setAuthenticatedUser("privy:wallet-design-second")
        assertEquals(RootTab.Home, model.tab.value)
    }

    private fun capture(name: String, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
        val view = ComposeView(paparazzi.context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setContent {
                BoxWithConstraints(Modifier.fillMaxSize().background(WalletStyle.Background)) {
                    CompositionLocalProvider(LocalReferenceScale provides (maxWidth.value / 392f)) {
                        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), content = content)
                    }
                }
            }
        }
        paparazzi.snapshot(view, name = "${name}_${viewportWidth}px")
    }

    private fun sampleTransfers(): List<TransferJournalRecord> {
        val session = TransferSessionIdentity.create(
            userId = "privy:wallet-design-fixture",
            walletId = ethereumWallet.walletId,
            networkId = WalletNetworkId.ETHEREUM_MAINNET,
            walletAddress = ethereumWallet.address,
        )
        // Historical dates keep time labels independent of the current day.
        val createdAt = Instant.parse("2020-02-03T10:00:00Z").toEpochMilli()
        return listOf(
            Triple(WalletAssetId.ETHEREUM_USDC, "120", TransferJournalState.FINALIZED),
            Triple(WalletAssetId.ETHEREUM_ETH, "0.125", TransferJournalState.PENDING),
            Triple(WalletAssetId.ETHEREUM_USDC, "1800", TransferJournalState.FAILED_FINALIZED),
        ).mapIndexed { index, (asset, amount, state) ->
            val timestamp = createdAt - index * 3_600_000L
            val review = TransferDraft.create(
                session = session,
                assetId = asset,
                recipient = "0x" + (index + 2).toString().repeat(40),
                amount = amount,
                createdAtEpochMillis = timestamp,
            ).review()
            TransferJournalRecord(
                review = review,
                state = state,
                transactionId = "0x" + (index + 4).toString().repeat(64),
                updatedAtEpochMillis = timestamp + 30_000L,
            )
        }
    }

    companion object {
        private val ethereumWallet = UserWallet(
            chain = WalletChain.ETHEREUM,
            address = "0x1111111111111111111111111111111111111111",
            walletId = "fixture-ethereum-wallet",
        )
        private val solanaWallet = UserWallet(
            chain = WalletChain.SOLANA,
            address = "4vJ9JU1bJJE96FWSJKvHsmmFADCg4gpZQff4P3bkLKi",
            walletId = "fixture-solana-wallet",
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{0}x{1}")
        fun viewports(): List<Array<Int>> = listOf(arrayOf(392, 826), arrayOf(360, 780), arrayOf(320, 640))
    }
}
