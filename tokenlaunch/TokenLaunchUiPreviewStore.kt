package com.elevencapital.app.tokenlaunch

import android.content.Context
import com.elevencapital.app.BuildConfig
import com.elevencapital.app.screens.LaunchTokenDraft
import com.elevencapital.app.screens.LaunchTokenStep
import com.elevencapital.app.screens.TOKEN_LAUNCH_UI_PREVIEW_SYMBOL
import com.elevencapital.app.screens.isTokenLaunchUiPreview
import com.elevencapital.app.screens.launchTokenValidationMessage
import com.elevencapital.app.screens.tokenLaunchUiPreviewResult
import com.elevencapital.app.wallet.canonicalSolanaPublicKey
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** Debug-only Community state. It is deliberately separate from confirmed on-chain receipts. */
internal data class StoredTokenLaunchUiPreview(
    val name: String,
    val symbol: String,
    val mintAddress: String,
    val imageUri: String? = null,
    val description: String = "",
    val xHandle: String = "",
    val telegramHandle: String = "",
    val websiteUrl: String = "",
    val totalSupply: String = "1000000000",
    val feeBps: String = "100",
    val migrationThresholdSol: String = "10",
    val supplyOnMigrationPercent: String = "20",
    val curvePreset: String = "Classic",
    val initialMarketCapSol: String = "",
    val graduationMarketCapSol: String = "",
    val launchProtectionEnabled: Boolean = false,
    val vestCreatorTokens: Boolean = false,
    val vestedSupplyPercent: String = "10",
    val vestingDurationMonths: String = "12",
    val vestingFrequency: String = "Monthly",
) {
    fun toDraft(): LaunchTokenDraft = LaunchTokenDraft(
        imageUri = imageUri, name = name, symbol = symbol,
        description = description, xHandle = xHandle,
        telegramHandle = telegramHandle, websiteUrl = websiteUrl,
        totalSupply = totalSupply, feeBps = feeBps,
        migrationThresholdSol = migrationThresholdSol,
        supplyOnMigrationPercent = supplyOnMigrationPercent,
        curvePreset = curvePreset,
        initialMarketCapSol = initialMarketCapSol,
        graduationMarketCapSol = graduationMarketCapSol,
        launchProtectionEnabled = launchProtectionEnabled,
        vestCreatorTokens = vestCreatorTokens,
        vestedSupplyPercent = vestedSupplyPercent,
        vestingDurationMonths = vestingDurationMonths,
        vestingFrequency = vestingFrequency,
    )
}

/**
 * Keeps the hidden UI rehearsal visible across Activity/process recreation without ever turning it
 * into a real launch receipt. Every decoded field is pinned to the single supported rehearsal.
 */
internal object TokenLaunchUiPreviewStore {
    private const val PREFERENCES = "eleven-token-launch-ui-wallet-v2"

    fun read(context: Context, userId: String, walletAddress: String): StoredTokenLaunchUiPreview? {
        if (!BuildConfig.DEBUG || !validOwner(userId, walletAddress)) return null
        val raw = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(ownerKey(userId, walletAddress), null) ?: return null
        return decodeTokenLaunchUiPreview(raw)
    }

    fun save(
        context: Context,
        userId: String,
        walletAddress: String,
        draft: LaunchTokenDraft,
        mintAddress: String,
    ): Boolean {
        if (!BuildConfig.DEBUG || !validOwner(userId, walletAddress) ||
            !isTokenLaunchUiPreview(draft.symbol)) return false
        val imageUri = draft.imageUri
        if (imageUri != null && !hasDurableReadAccess(context, imageUri)) return false
        val stored = StoredTokenLaunchUiPreview(
            name = draft.name,
            symbol = TOKEN_LAUNCH_UI_PREVIEW_SYMBOL,
            mintAddress = mintAddress,
            imageUri = imageUri,
            description = draft.description,
            xHandle = draft.xHandle,
            telegramHandle = draft.telegramHandle,
            websiteUrl = draft.websiteUrl,
            totalSupply = draft.totalSupply,
            feeBps = draft.feeBps,
            migrationThresholdSol = draft.migrationThresholdSol,
            supplyOnMigrationPercent = draft.supplyOnMigrationPercent,
            curvePreset = draft.curvePreset,
            initialMarketCapSol = draft.initialMarketCapSol,
            graduationMarketCapSol = draft.graduationMarketCapSol,
            launchProtectionEnabled = draft.launchProtectionEnabled,
            vestCreatorTokens = draft.vestCreatorTokens,
            vestedSupplyPercent = draft.vestedSupplyPercent,
            vestingDurationMonths = draft.vestingDurationMonths,
            vestingFrequency = draft.vestingFrequency,
        )
        val encoded = encodeTokenLaunchUiPreview(stored) ?: return false
        return context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(ownerKey(userId, walletAddress), encoded).commit()
    }

    fun remove(context: Context, userId: String, walletAddress: String): Boolean =
        BuildConfig.DEBUG && validOwner(userId, walletAddress) &&
            context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().remove(ownerKey(userId, walletAddress)).commit()

    /** Clears this user's debug previews across wallets, including when no wallet is available now. */
    fun removeForUser(context: Context, userId: String): Boolean {
        if (!BuildConfig.DEBUG || !validUserId(userId)) return false
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val prefix = "$userId|"
        val ownedKeys = preferences.all.keys.filter { key ->
            key.startsWith(prefix) && key.substring(prefix.length).let { walletAddress ->
                canonicalSolanaPublicKey(walletAddress) == walletAddress
            }
        }
        if (ownedKeys.isEmpty()) return true
        val editor = preferences.edit()
        ownedKeys.forEach { editor.remove(it) }
        return editor.commit()
    }

    private fun validOwner(userId: String, walletAddress: String): Boolean =
        validUserId(userId) &&
            canonicalSolanaPublicKey(walletAddress) == walletAddress

    private fun validUserId(userId: String): Boolean =
        userId.isNotBlank() && userId.length <= 256 && userId.none(Char::isISOControl)

    private fun ownerKey(userId: String, walletAddress: String): String = "$userId|$walletAddress"

    private fun hasDurableReadAccess(context: Context, imageUri: String): Boolean =
        isCanonicalTokenLaunchUiPreviewImageUri(imageUri) &&
            (TokenLaunchArtworkStore.hasDurableAccess(context, imageUri) || runCatching {
                context.applicationContext.contentResolver.persistedUriPermissions.any { permission ->
                    permission.isReadPermission && permission.uri.toString() == imageUri
                }
            }.getOrDefault(false))
}

internal fun encodeTokenLaunchUiPreview(value: StoredTokenLaunchUiPreview): String? {
    if (!isCanonicalTokenLaunchUiPreview(value)) return null
    val encodedName = encodePreviewText(value.name)
    val encodedImage = value.imageUri?.let(::encodePreviewText) ?: "-"
    return listOf(
        "5", encodedName, value.symbol, value.mintAddress, encodedImage,
        value.totalSupply, value.feeBps, value.migrationThresholdSol,
        value.supplyOnMigrationPercent, value.initialMarketCapSol,
        value.graduationMarketCapSol,
        if (value.launchProtectionEnabled) "1" else "0",
        if (value.vestCreatorTokens) "1" else "0",
        value.vestedSupplyPercent, value.vestingDurationMonths, value.vestingFrequency,
        encodeOptionalPreviewText(value.description), encodeOptionalPreviewText(value.xHandle),
        encodeOptionalPreviewText(value.telegramHandle), encodeOptionalPreviewText(value.websiteUrl),
        value.curvePreset,
    ).joinToString("|")
}

internal fun decodeTokenLaunchUiPreview(raw: String): StoredTokenLaunchUiPreview? {
    if (raw.length !in 1..8_192) return null
    val fields = raw.split('|')
    val imageUri = when {
        fields.size == 4 && fields[0] == "1" && raw.length <= 256 -> null
        (fields.size == 5 && fields[0] == "2") ||
            (fields.size == 16 && fields[0] == "3") ||
            (fields.size == 20 && fields[0] == "4") ||
            (fields.size == 21 && fields[0] == "5") -> when (fields[4]) {
            "-" -> null
            else -> decodePreviewText(fields[4])?.takeIf { encodePreviewText(it) == fields[4] }
                ?: return null
        }
        else -> return null
    }
    val name = decodePreviewText(fields[1]) ?: return null
    // Previously saved Christmas launches used SPXTR. Keep their wallet-scoped record and
    // synthetic mint, but present the current ticker everywhere after restoring them.
    if (fields[2] != TOKEN_LAUNCH_UI_PREVIEW_SYMBOL && fields[2] != "SPXTR") return null
    val base = StoredTokenLaunchUiPreview(name, TOKEN_LAUNCH_UI_PREVIEW_SYMBOL, fields[3], imageUri)
    val restoredEconomics = if (fields.size in listOf(16, 20, 21)) {
        if (fields[11] !in listOf("0", "1") || fields[12] !in listOf("0", "1")) return null
        base.copy(
            totalSupply = fields[5], feeBps = fields[6], migrationThresholdSol = fields[7],
            supplyOnMigrationPercent = fields[8], initialMarketCapSol = fields[9],
            graduationMarketCapSol = fields[10], launchProtectionEnabled = fields[11] == "1",
            vestCreatorTokens = fields[12] == "1", vestedSupplyPercent = fields[13],
            vestingDurationMonths = fields[14], vestingFrequency = fields[15],
        )
    } else base
    val restored = if (fields.size in listOf(20, 21)) restoredEconomics.copy(
        description = decodeOptionalPreviewText(fields[16]) ?: return null,
        xHandle = decodeOptionalPreviewText(fields[17]) ?: return null,
        telegramHandle = decodeOptionalPreviewText(fields[18]) ?: return null,
        websiteUrl = decodeOptionalPreviewText(fields[19]) ?: return null,
    ) else restoredEconomics
    return restored.copy(curvePreset = fields.getOrNull(20) ?: "Classic")
        .takeIf(::isCanonicalTokenLaunchUiPreview)
}

private fun encodeOptionalPreviewText(value: String): String =
    if (value.isEmpty()) "-" else encodePreviewText(value)

private fun decodeOptionalPreviewText(value: String): String? =
    if (value == "-") "" else decodePreviewText(value)?.takeIf { encodePreviewText(it) == value }

private fun encodePreviewText(value: String): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(value.toByteArray(Charsets.UTF_8))

private fun decodePreviewText(encoded: String): String? = runCatching {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(encoded)))
        .toString()
}.getOrNull()

internal fun isCanonicalTokenLaunchUiPreviewImageUri(value: String): Boolean =
    value.length in 1..2_048 && value == value.trim() && value.none(Char::isISOControl) &&
        runCatching {
            val uri = URI(value)
            uri.scheme == "content" && !uri.isOpaque && !uri.rawAuthority.isNullOrBlank() &&
                uri.rawUserInfo == null
        }.getOrDefault(false)

internal fun isCanonicalTokenLaunchUiPreview(value: StoredTokenLaunchUiPreview): Boolean =
    value.name.isNotBlank() && value.name == value.name.trim() &&
    value.name.toByteArray(Charsets.UTF_8).size <= 32 && value.name.none(Char::isISOControl) &&
    value.symbol == TOKEN_LAUNCH_UI_PREVIEW_SYMBOL &&
    value.mintAddress == tokenLaunchUiPreviewResult().mintAddress &&
    (value.imageUri == null || isCanonicalTokenLaunchUiPreviewImageUri(value.imageUri)) &&
    value.description.length <= 280 &&
    (value.description.isBlank() || launchTokenValidationMessage(LaunchTokenStep.DESCRIPTION, value.toDraft()) == null) &&
    value.websiteUrl.length <= 200 &&
    launchTokenValidationMessage(LaunchTokenStep.DETAILS, value.toDraft()) == null &&
    launchTokenValidationMessage(LaunchTokenStep.DBC, value.toDraft()) == null &&
    listOf(value.totalSupply, value.feeBps, value.migrationThresholdSol,
        value.supplyOnMigrationPercent, value.initialMarketCapSol, value.graduationMarketCapSol,
        value.vestedSupplyPercent, value.vestingDurationMonths, value.vestingFrequency,
        value.curvePreset)
        .none { '|' in it || it.length > 64 }
