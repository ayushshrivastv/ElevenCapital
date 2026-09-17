package com.elevencapital.core.market

/** Small bounded SSE decoder. JSON/provider validation is deliberately left to the caller. */
class ServerSentEventDecoder(private val maxEventCharacters: Int = 4 * 1024 * 1024) {
    data class Event(val name: String, val data: String)
    private var name = "message"
    private val data = StringBuilder()
    private var firstLine = true

    fun line(rawLine: String): Event? {
        val line = if (firstLine) rawLine.removePrefix("\uFEFF") else rawLine
        firstLine = false
        require(line.length <= maxEventCharacters) { "Market stream line too large." }
        if (line.isEmpty()) {
            val event = if (data.isNotEmpty()) Event(name, data.toString().removeSuffix("\n")) else null
            name = "message"
            data.setLength(0)
            return event
        }
        if (line.startsWith(':')) return null
        val field = line.substringBefore(':')
        val value = line.substringAfter(':', "").removePrefix(" ")
        when (field) {
            "event" -> name = value.ifEmpty { "message" }
            "data" -> {
                require(data.length + value.length + 1 <= maxEventCharacters) { "Market stream event too large." }
                data.append(value).append('\n')
            }
        }
        return null
    }
}
