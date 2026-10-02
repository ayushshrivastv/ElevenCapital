package com.elevencapital.app.data

import com.elevencapital.app.wallet.canonicalSolanaPublicKey
import com.elevencapital.app.wallet.parseSingleJsonObject
import com.elevencapital.app.wallet.validatedBearerToken
import com.elevencapital.app.wallet.validatedTransferBackend
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal enum class CommunityTokenMintStatus {
    VALID,
    INVALID,
    UNAVAILABLE,
    AUTH_REQUIRED,
}

internal data class CommunityTokenMintValidation(
    val status: CommunityTokenMintStatus,
    val mintAddress: String? = null,
) {
    val isValid: Boolean get() = status == CommunityTokenMintStatus.VALID && mintAddress != null
}

internal fun interface CommunityTokenMintAccessTokenProvider {
    suspend fun freshToken(expectedUserId: String): String
}

/** A read-only backend check; a scanned address alone never enables a token purchase. */
internal class CommunityTokenMintValidator(
    baseUrl: String,
    private val accessToken: CommunityTokenMintAccessTokenProvider,
    http: OkHttpClient = OkHttpClient(),
    allowLoopbackHttp: Boolean = false,
) {
    private val base = validatedTransferBackend(baseUrl, allowLoopbackHttp)
    private val client = http.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .cache(null)
        .build()

    suspend fun validate(userId: String, mint: String): CommunityTokenMintValidation {
        val canonicalMint = canonicalSolanaPublicKey(mint)
            ?: return CommunityTokenMintValidation(CommunityTokenMintStatus.INVALID)
        val bearer = try {
            validatedBearerToken(accessToken.freshToken(userId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return CommunityTokenMintValidation(CommunityTokenMintStatus.AUTH_REQUIRED)
        }
        val request = Request.Builder()
            .url("$base/v1/community-tokens/$canonicalMint/validate")
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .header("Authorization", "Bearer $bearer")
            .get()
            .build()
        return execute(request, canonicalMint)
    }

    private suspend fun execute(request: Request, expectedMint: String): CommunityTokenMintValidation =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, failure: IOException) {
                    if (continuation.isActive) continuation.resume(
                        CommunityTokenMintValidation(CommunityTokenMintStatus.UNAVAILABLE))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try { response.use { received -> parse(received, expectedMint) } }
                        catch (_: Exception) { CommunityTokenMintValidation(CommunityTokenMintStatus.UNAVAILABLE) }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }

    private fun parse(response: Response, expectedMint: String): CommunityTokenMintValidation {
        when (response.code) {
            400, 404, 422 -> return CommunityTokenMintValidation(CommunityTokenMintStatus.INVALID)
            401, 403 -> return CommunityTokenMintValidation(CommunityTokenMintStatus.AUTH_REQUIRED)
            200 -> Unit
            else -> return CommunityTokenMintValidation(CommunityTokenMintStatus.UNAVAILABLE)
        }
        if (response.header("Content-Type")?.substringBefore(';')?.trim() != "application/json" ||
            "no-store" !in response.header("Cache-Control").orEmpty().lowercase()
                .split(',').map(String::trim)) {
            return CommunityTokenMintValidation(CommunityTokenMintStatus.UNAVAILABLE)
        }
        val json = try { parseSingleJsonObject(readBounded(response)) }
            catch (_: Exception) { return CommunityTokenMintValidation(CommunityTokenMintStatus.UNAVAILABLE) }
        val exact = json.keys().asSequence().toSet() == setOf(
            "schemaVersion", "network", "mintAddress", "valid")
        val version = json.opt("schemaVersion")
        if (!exact || version !is Number || version.toString() != "1" ||
            json.optString("network") != "solana-mainnet" ||
            json.optString("mintAddress") != expectedMint ||
            json.opt("valid") != true) {
            return CommunityTokenMintValidation(CommunityTokenMintStatus.UNAVAILABLE)
        }
        return CommunityTokenMintValidation(CommunityTokenMintStatus.VALID, expectedMint)
    }

    private fun readBounded(response: Response): ByteArray {
        val body = response.body ?: throw IOException("Missing mint-validation response")
        if (body.contentLength() > MAX_RESPONSE_BYTES) throw IOException("Mint-validation response too large")
        return body.byteStream().use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(1_024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (out.size() + count > MAX_RESPONSE_BYTES) throw IOException("Mint-validation response too large")
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 8 * 1_024
    }
}
