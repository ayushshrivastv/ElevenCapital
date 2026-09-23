package com.elevencapital.app.screens

import com.elevencapital.app.purchase.PurchasePaymentAsset
import com.elevencapital.core.stock.Stock
import com.elevencapital.core.stock.flow.OrderSide
import java.math.BigDecimal
import java.math.RoundingMode

/** Uses the verified portfolio valuation without assuming a dollar peg for any token. */
internal fun paymentAmountForUsd(usdAmount: BigDecimal, asset: PurchasePaymentAsset?): BigDecimal? {
    if (usdAmount.signum() <= 0 || asset == null || asset.balance.signum() <= 0) return null
    val usdValue = asset.usdValue?.takeIf { it.signum() > 0 } ?: return null
    return usdAmount.multiply(asset.balance).divide(usdValue, asset.decimals, RoundingMode.DOWN)
}

internal fun paymentUsdForAmount(tokenAmount: BigDecimal, asset: PurchasePaymentAsset?): BigDecimal? {
    if (tokenAmount.signum() <= 0 || asset == null || asset.balance.signum() <= 0) return null
    val usdValue = asset.usdValue?.takeIf { it.signum() > 0 } ?: return null
    return tokenAmount.multiply(usdValue).divide(asset.balance, 18, RoundingMode.DOWN)
}

/** Buy entry is denominated in USD; sell entry is the exact stock-token quantity. */
internal fun orderInputAmount(
    side: OrderSide,
    enteredAmount: BigDecimal,
    asset: PurchasePaymentAsset?,
): BigDecimal? = when (side) {
    OrderSide.BUY -> paymentAmountForUsd(enteredAmount, asset)
    OrderSide.SELL -> enteredAmount.takeIf { it.signum() > 0 && asset != null }
}

internal fun orderExceedsBalance(
    side: OrderSide,
    enteredAmount: BigDecimal,
    asset: PurchasePaymentAsset?,
    fallbackBalance: BigDecimal?,
): Boolean = when (side) {
    OrderSide.BUY -> asset?.usdValue?.let { enteredAmount > it } ?: false
    OrderSide.SELL -> (asset?.balance ?: fallbackBalance)?.let { enteredAmount > it } ?: false
}

/** Indicative only: execution quantity is supplied by the eventual purchase quote. */
internal fun indicativeStockQuantity(usdAmount: BigDecimal, stock: Stock): BigDecimal? {
    if (usdAmount.signum() <= 0 || stock.quote.currencyCode != "USD") return null
    val price = stock.quote.price?.takeIf { it.signum() > 0 } ?: return null
    return usdAmount.divide(price, 18, RoundingMode.DOWN)
}
