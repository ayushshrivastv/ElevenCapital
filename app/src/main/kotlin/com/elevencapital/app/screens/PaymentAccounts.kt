package com.elevencapital.app.screens

import com.elevencapital.app.purchase.PurchasePaymentAsset

/** Each funded token/network pair remains distinct; dollar values determine display order. */
internal fun fundedPaymentAssets(assets: List<PurchasePaymentAsset>): List<PurchasePaymentAsset> =
    assets.filter { it.enabled && it.balance.signum() > 0 }
        .sortedWith(compareByDescending<PurchasePaymentAsset> { it.usdValue }.thenBy { it.id })
