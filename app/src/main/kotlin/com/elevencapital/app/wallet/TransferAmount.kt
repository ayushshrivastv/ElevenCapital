package com.elevencapital.app.wallet

import java.math.BigInteger

/** Exact display/base-unit pair. Floating-point values never enter transfer preparation. */
class TransferAmount private constructor(
    val displayAmount: String,
    val baseUnits: BigInteger,
    val decimals: Int,
) {
    override fun equals(other: Any?): Boolean = other is TransferAmount &&
        displayAmount == other.displayAmount && baseUnits == other.baseUnits && decimals == other.decimals
    override fun hashCode(): Int = 31 * (31 * displayAmount.hashCode() + baseUnits.hashCode()) + decimals
    override fun toString(): String = displayAmount

    companion object {
        private val decimalPattern = Regex("(?:0|[1-9][0-9]*)(?:\\.([0-9]+))?")

        fun parse(input: String, asset: WalletAsset): TransferAmount {
            if (input.length !in 1..160 || !decimalPattern.matches(input)) {
                transferValidationFailure(WalletTransferErrorCode.INVALID_AMOUNT,
                    "Enter a positive amount using ordinary decimal digits.")
            }
            val integer = input.substringBefore('.')
            val fraction = input.substringAfter('.', "")
            if (fraction.length > asset.decimals) {
                transferValidationFailure(WalletTransferErrorCode.EXCESS_PRECISION,
                    "This amount has more decimal places than the selected asset supports.")
            }
            val baseUnitText = (integer + fraction.padEnd(asset.decimals, '0')).trimStart('0').ifEmpty { "0" }
            val baseUnits = BigInteger(baseUnitText)
            if (baseUnits.signum() <= 0) {
                transferValidationFailure(WalletTransferErrorCode.INVALID_AMOUNT, "Enter an amount greater than zero.")
            }
            val maximum = BigInteger.ONE.shiftLeft(asset.baseUnitBits).subtract(BigInteger.ONE)
            if (baseUnits > maximum) {
                transferValidationFailure(WalletTransferErrorCode.AMOUNT_TOO_LARGE,
                    "This amount is too large for the selected asset.")
            }
            val normalizedFraction = fraction.trimEnd('0')
            val normalized = if (normalizedFraction.isEmpty()) integer else "$integer.$normalizedFraction"
            return TransferAmount(normalized, baseUnits, asset.decimals)
        }
    }
}
