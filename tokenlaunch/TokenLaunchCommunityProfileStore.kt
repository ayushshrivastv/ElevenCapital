package com.elevencapital.app.tokenlaunch

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import com.elevencapital.app.screens.LaunchTokenDraft
import com.elevencapital.app.wallet.canonicalSolanaPublicKey
import java.net.URI
import java.security.MessageDigest
import org.json.JSONObject

/** Display-only details for a confirmed launch. No wallet or transaction material belongs here. */
internal data class StoredTokenLaunchCommunityProfile(
    val userId: String,
    val mintAddress: String,
    val name: String,
    val symbol: String,
    val imageUri: String?,
) {
    fun toDraft(): LaunchTokenDraft = LaunchTokenDraft(
        imageUri = imageUri,
        name = name,
        symbol = symbol,
    )
}

/**
 * Keeps Community artwork/name after process recreation. Callers save this only after receipt
 * confirmation; local artwork must be app-owned or have a persisted document read grant.
 */
internal object TokenLaunchCommunityProfileStore {
    private const val PREFERENCES = "eleven-token-community-profile-mainnet-v1"

    fun read(
        context: Context,
        userId: String,
        mintAddress: String,
    ): StoredTokenLaunchCommunityProfile? {
        if (!validOwnerAndMint(userId, mintAddress)) return null
        val raw = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(key(userId, mintAddress), null) ?: return null
        return decodeTokenLaunchCommunityProfile(raw)
            ?.takeIf { it.userId == userId && it.mintAddress == mintAddress }
            ?.let { profile ->
                if (profile.imageUri != null && !hasDurableArtworkAccess(context, profile.imageUri)) {
                    profile.copy(imageUri = null)
                } else {
                    profile
                }
            }
    }

    fun save(
        context: Context,
        userId: String,
        mintAddress: String,
        draft: LaunchTokenDraft,
    ): Boolean {
        val profile = StoredTokenLaunchCommunityProfile(
            userId = userId,
            mintAddress = mintAddress,
            name = draft.name,
            symbol = draft.symbol,
            imageUri = draft.imageUri,
        )
        val encoded = encodeTokenLaunchCommunityProfile(profile) ?: return false
        if (profile.imageUri != null && !hasDurableArtworkAccess(context, profile.imageUri)) return false
        return context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(key(userId, mintAddress), encoded).commit()
    }

    private fun key(userId: String, mintAddress: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$userId\u0000$mintAddress".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

internal fun encodeTokenLaunchCommunityProfile(value: StoredTokenLaunchCommunityProfile): String? {
    if (!validCommunityProfile(value)) return null
    val raw = JSONObject()
        .put("version", 1)
        .put("userId", value.userId)
        .put("mintAddress", value.mintAddress)
        .put("name", value.name)
        .put("symbol", value.symbol)
        .put("imageUri", value.imageUri ?: JSONObject.NULL)
        .toString()
    return raw.takeIf { it.length <= MAX_PROFILE_CHARS }
}

internal fun decodeTokenLaunchCommunityProfile(raw: String): StoredTokenLaunchCommunityProfile? {
    if (raw.length !in 1..MAX_PROFILE_CHARS) return null
    return runCatching {
        val value = JSONObject(raw)
        if (value.keys().asSequence().toSet() != PROFILE_KEYS || value.get("version") != 1) return null
        val imageValue = value.get("imageUri")
        val imageUri = when {
            imageValue === JSONObject.NULL -> null
            imageValue is String -> imageValue
            else -> return null
        }
        StoredTokenLaunchCommunityProfile(
            userId = value.get("userId") as? String ?: return null,
            mintAddress = value.get("mintAddress") as? String ?: return null,
            name = value.get("name") as? String ?: return null,
            symbol = value.get("symbol") as? String ?: return null,
            imageUri = imageUri,
        ).takeIf(::validCommunityProfile)
    }.getOrNull()
}

private fun validCommunityProfile(value: StoredTokenLaunchCommunityProfile): Boolean =
    validOwnerAndMint(value.userId, value.mintAddress) &&
        value.name.isNotBlank() && value.name == value.name.trim() &&
        value.name.toByteArray(Charsets.UTF_8).size <= 32 && value.name.none(Char::isISOControl) &&
        value.symbol.matches(Regex("[A-Z0-9]{1,10}")) &&
        validCommunityArtworkUri(value.imageUri)

private fun validOwnerAndMint(userId: String, mintAddress: String): Boolean =
    safeIdentity(userId, 256) && canonicalSolanaPublicKey(mintAddress) == mintAddress

private fun validCommunityArtworkUri(value: String?): Boolean {
    if (value == null) return true
    if (value.length !in 12..2_048 || value != value.trim() ||
        value.any { it.isWhitespace() || it.isISOControl() }) return false
    if (isPublicTokenHttpsUrl(value)) return true
    return runCatching {
        val uri = URI(value)
        uri.scheme == "content" && !uri.isOpaque &&
            !uri.rawAuthority.isNullOrBlank() && !uri.rawPath.isNullOrBlank() && uri.rawFragment == null
    }.getOrDefault(false)
}

private fun hasDurableArtworkAccess(context: Context, imageUri: String): Boolean {
    if (isPublicTokenHttpsUrl(imageUri)) return true
    if (TokenLaunchArtworkStore.hasDurableAccess(context, imageUri)) return true
    val uri = Uri.parse(imageUri)
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
    return runCatching {
        context.contentResolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission && permission.uri == uri
        }
    }.getOrDefault(false)
}

private const val MAX_PROFILE_CHARS = 5_000
private val PROFILE_KEYS = setOf("version", "userId", "mintAddress", "name", "symbol", "imageUri")
