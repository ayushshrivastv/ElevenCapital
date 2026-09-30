package com.elevencapital.app.tokenlaunch

import org.json.JSONObject

/** User-owned launch details used only to create the permanent metadata document. */
data class TokenLaunchMetadataInput(
    val imageUri: String,
    val name: String,
    val symbol: String,
    val description: String,
    val websiteUrl: String? = null,
    val xUrl: String? = null,
    val telegramUrl: String? = null,
) {
    init {
        require(imageUri.isNotBlank() && imageUri.length <= 2_048 && imageUri.none(Char::isISOControl))
        require(name.isNotEmpty() && name == name.trim() && name.toByteArray(Charsets.UTF_8).size <= 32)
        require(symbol.matches(Regex("[A-Z0-9]{1,10}")))
        require(description.length in 10..280 && description == description.trim())
        listOf(websiteUrl, xUrl, telegramUrl).filterNotNull().forEach {
            require(isPublicTokenHttpsUrl(it))
        }
    }
}

internal data class TokenLaunchMetadataUpload(
    val name: String,
    val symbol: String,
    val description: String,
    val artwork: EncodedTokenArtwork,
    val websiteUrl: String?,
    val xUrl: String?,
    val telegramUrl: String?,
)

internal data class UploadedTokenMetadata(
    val metadataUri: String,
    val imageUri: String,
    val contentHash: String,
) {
    init {
        require(isPublicTokenHttpsUrl(metadataUri))
        require(isPublicTokenHttpsUrl(imageUri))
        require(contentHash.matches(Regex("[a-f0-9]{64}")))
    }
}

internal fun parseUploadedTokenMetadata(source: JSONObject): UploadedTokenMetadata {
    if (source.keys().asSequence().toSet() != setOf("metadataUri", "imageUri", "contentHash")) {
        protocolFailure()
    }
    return try {
        UploadedTokenMetadata(
            metadataUri = source.get("metadataUri") as? String ?: protocolFailure(),
            imageUri = source.get("imageUri") as? String ?: protocolFailure(),
            contentHash = source.get("contentHash") as? String ?: protocolFailure(),
        )
    } catch (failure: TokenLaunchException) {
        throw failure
    } catch (_: Exception) {
        protocolFailure()
    }
}
