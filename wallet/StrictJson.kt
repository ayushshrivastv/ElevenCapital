package com.elevencapital.app.wallet

import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** Parses one JSON object and rejects scalar, array, or trailing payloads. */
internal fun parseSingleJsonObject(bytes: ByteArray): JSONObject {
    val tokenizer = JSONTokener(String(bytes, Charsets.UTF_8))
    val value = tokenizer.nextValue()
    if (value !is JSONObject || tokenizer.nextClean() != 0.toChar()) {
        throw JSONException("Expected one JSON object.")
    }
    return value
}
