package com.elevencapital.app.tokenlaunch

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Keeps Photo Picker artwork available to the launch flow and Community after app restarts. */
internal object TokenLaunchArtworkStore {
    private const val DIRECTORY = "token_artwork"
    private const val PROVIDER_PATH = "token_artwork"
    private val FILE_NAME = Regex("[a-f0-9]{64}\\.jpg")

    suspend fun importPickedImage(context: Context, pickedUri: Uri): String = withContext(Dispatchers.IO) {
        require(pickedUri.scheme == ContentResolver.SCHEME_CONTENT)
        val appContext = context.applicationContext
        // The picker grant may be temporary. Fit the complete image into a bounded JPEG
        // while it is valid, then keep it privately instead of persisting the grant.
        val bytes = Base64.getDecoder().decode(
            AndroidTokenArtworkEncoder(appContext.contentResolver).encode(pickedUri.toString()).base64,
        )
        currentCoroutineContext().ensureActive()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val directory = File(appContext.filesDir, DIRECTORY)
        check(directory.isDirectory || directory.mkdirs()) { "Unable to keep token image." }
        val artwork = File(directory, "$hash.jpg")
        if (!artwork.isFile) {
            val pending = File.createTempFile("incoming-", ".jpg", directory)
            try {
                pending.outputStream().use { it.write(bytes) }
                currentCoroutineContext().ensureActive()
                check(pending.renameTo(artwork) || artwork.isFile) { "Unable to keep token image." }
            } finally {
                pending.delete()
            }
        }
        check(artwork.isFile && artwork.length() in 4..MAX_TOKEN_ARTWORK_BYTES.toLong()) {
            "Unable to keep token image."
        }
        FileProvider.getUriForFile(appContext, authority(appContext), artwork).toString()
    }

    fun hasDurableAccess(context: Context, imageUri: String): Boolean {
        return localArtworkFile(context, imageUri) != null
    }

    /** Read our own saved JPEG directly for UI previews; no content-provider round trip. */
    fun localArtworkFile(context: Context, imageUri: String): File? {
        val fileName = ownedArtworkFileName(imageUri, context.packageName) ?: return null
        val artwork = File(File(context.applicationContext.filesDir, DIRECTORY), fileName)
        return artwork.takeIf { it.isFile && it.length() in 4..MAX_TOKEN_ARTWORK_BYTES.toLong() }
    }

    private fun authority(context: Context): String = "${context.packageName}.token-artwork"

    internal fun ownedArtworkFileName(imageUri: String, packageName: String): String? = runCatching {
        val uri = Uri.parse(imageUri)
        if (uri.scheme != ContentResolver.SCHEME_CONTENT ||
            uri.authority != "$packageName.token-artwork" ||
            uri.query != null || uri.fragment != null ||
            uri.pathSegments.size != 2 || uri.pathSegments[0] != PROVIDER_PATH
        ) return null
        uri.pathSegments[1].takeIf(FILE_NAME::matches)
    }.getOrNull()
}
