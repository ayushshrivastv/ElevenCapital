package com.elevencapital.app.screens

import android.content.Context
import com.elevencapital.app.BuildConfig
import com.elevencapital.core.stock.Stock
import java.math.BigDecimal

/** A local UI rehearsal position, never a wallet balance or a settled trade. */
data class PreviewStockHolding(
    val stock: Stock,
    val quantity: BigDecimal,
    val valueUsd: BigDecimal,
)

data class PreviewSpcxxPosition(
    val quantity: BigDecimal,
    val valueUsd: BigDecimal,
    /** The local rehearsal action's time; zero means an older saved preview without an activity row. */
    val purchasedAtEpochMillis: Long = 0L,
)

/** Scoped by the signed-in user so a rehearsal holding cannot appear in another account. */
object PreviewSpcxxHoldingStore {
    private const val PREFERENCES = "spcxx_purchase_preview"

    fun read(context: Context, userId: String): PreviewSpcxxPosition? {
        if (!BuildConfig.DEBUG || userId.isBlank()) return null
        val parts = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(userId, null)?.split('|') ?: return null
        if (parts.size != 2 && parts.size != 3) return null
        val quantity = parts[0].toBigDecimalOrNull() ?: return null
        val valueUsd = parts[1].toBigDecimalOrNull() ?: return null
        val purchaseTime = if (parts.size == 3) parts[2].toLongOrNull() ?: return null else 0L
        if (quantity.signum() <= 0 || valueUsd.signum() <= 0) return null
        if (purchaseTime < 0L) return null
        return PreviewSpcxxPosition(quantity, valueUsd, purchaseTime)
    }

    fun set(
        context: Context,
        userId: String,
        quantity: BigDecimal,
        valueUsd: BigDecimal,
    ): PreviewSpcxxPosition? {
        if (!BuildConfig.DEBUG || userId.isBlank() || quantity.signum() <= 0 || valueUsd.signum() <= 0) return null
        val next = PreviewSpcxxPosition(quantity, valueUsd, System.currentTimeMillis())
        // The holding and its Home activity share a single record, so removal cannot leave an
        // orphaned transaction row or restore a removed holding on the next app launch.
        val serialized = "${next.quantity.toPlainString()}|${next.valueUsd.toPlainString()}|${next.purchasedAtEpochMillis}"
        return next.takeIf {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(userId, serialized).commit()
        }
    }

    fun remove(context: Context, userId: String): Boolean = BuildConfig.DEBUG && userId.isNotBlank() &&
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().remove(userId).commit()
}
