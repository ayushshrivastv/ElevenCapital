package com.elevencapital.app.communitybuy

import com.elevencapital.app.wallet.parseSingleJsonObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal fun interface CommunityBuyClusterVerifier {
    suspend fun verifyRecentBlockhash(blockhash: String)
}

/** Independent public mainnet RPC check. A backend label is not proof of a Solana cluster. */
internal class CommunityMainnetVerifier private constructor(
    private val endpoint: String,
    http: OkHttpClient,
) : CommunityBuyClusterVerifier {
    constructor() : this("https://api.mainnet.solana.com", OkHttpClient())
    internal constructor(endpoint: String, http: OkHttpClient, testOnly: Unit) : this(endpoint, http) {
        @Suppress("UNUSED_VARIABLE") val ignored = testOnly
    }

    private val client = http.newBuilder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS).writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS).followRedirects(false)
        .followSslRedirects(false).retryOnConnectionFailure(false).cache(null).build()

    override suspend fun verifyRecentBlockhash(blockhash: String) {
        try {
            val genesis = rpc("getGenesisHash", JSONObject().put("jsonrpc", "2.0")
                .put("id", 1).put("method", "getGenesisHash"))
            if (genesis.getString("result") != MAINNET_GENESIS) failure()
            val valid = rpc("isBlockhashValid", JSONObject().put("jsonrpc", "2.0")
                .put("id", 2).put("method", "isBlockhashValid")
                .put("params", org.json.JSONArray().put(blockhash)
                    .put(JSONObject().put("commitment", "confirmed"))))
            if (valid.getJSONObject("result").get("value") != true) failure()
        } catch (failure: CommunityBuyException) {
            throw failure
        } catch (_: Exception) {
            failure()
        }
    }

    private suspend fun rpc(method: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(endpoint)
            .header("Accept", "application/json").header("Cache-Control", "no-store")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful || response.header("Content-Type")
                    ?.substringBefore(';')?.trim() != "application/json") failure()
            val stream = response.body?.byteStream() ?: failure()
            val bytes = stream.use {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 8_192) failure()
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val json = parseSingleJsonObject(bytes)
            val expectedId = if (method == "getGenesisHash") 1 else 2
            if (json.get("jsonrpc") != "2.0" || json.getInt("id") != expectedId ||
                json.has("error")) failure()
            json
        }
    }

    private fun failure(): Nothing = throw CommunityBuyException(CommunityBuyFailure.SERVICE_UNAVAILABLE,
        "Couldn't independently verify Solana Mainnet. Nothing was signed or submitted.")

    private companion object {
        const val MAINNET_GENESIS = "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d"
    }
}
