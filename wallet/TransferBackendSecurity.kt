package com.elevencapital.app.wallet

import java.net.URI

internal fun interface TransferAccessTokenProvider {
    suspend fun freshToken(expectedUserId: String): String
}

internal fun validatedTransferBackend(baseUrl: String, allowLoopbackHttp: Boolean): String {
    val normalized = baseUrl.trimEnd('/')
    val uri = URI(normalized)
    val loopback = uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost")
    require(uri.scheme == "https" || (allowLoopbackHttp && loopback))
    require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null)
    return normalized
}

internal fun validatedBearerToken(value: String): String {
    require(value.length in 32..8_192 && value.count { it == '.' } == 2)
    require(value.all { it.isLetterOrDigit() || it in "-_.~" })
    return value
}
