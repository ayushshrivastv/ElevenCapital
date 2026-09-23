package com.elevencapital.app

import android.app.Application
import com.elevencapital.app.auth.PrivyAuthController
import com.elevencapital.app.wallet.WalletTransferCoordinator
import com.elevencapital.app.wallet.TransferJournal
import com.elevencapital.app.purchase.PurchaseCoordinator
import com.elevencapital.app.purchase.PurchaseJournal

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

    override fun onCreate() {
        super.onCreate()
        // Application.onCreate runs on the main thread, as required by Privy's Android SDK.
        transferJournal = TransferJournal(this)
        purchaseJournal = PurchaseJournal(this)
        auth = PrivyAuthController(this, transferJournal)
        transfers = BuildConfig.MARKET_DATA_URL.takeIf(String::isNotBlank)
            ?.let { WalletTransferCoordinator(it, auth, transferJournal, allowLoopbackHttp = BuildConfig.DEBUG) }
        purchases = BuildConfig.MARKET_DATA_URL.takeIf(String::isNotBlank)
            ?.let { PurchaseCoordinator(it, auth, purchaseJournal, allowLoopbackHttp = BuildConfig.DEBUG) }
    }
}
