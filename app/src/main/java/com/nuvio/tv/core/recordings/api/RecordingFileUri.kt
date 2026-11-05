package com.nuvio.tv.core.recordings.api

/** A `file://` URI for an absolute path, percent-encoded the way the players expect. */
object RecordingFileUri {
    fun fromPath(path: String): String {
        if (path.startsWith("file://")) return path
        val sb = StringBuilder("file://")
        for (b in path.encodeToByteArray()) {
            val c = b.toInt() and 0xFF
            val plain = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code ||
                c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code || c == '/'.code
            if (plain) sb.append(c.toChar()) else {
                sb.append('%')
                sb.append(HEX[c shr 4])
                sb.append(HEX[c and 0xF])
            }
        }
        return sb.toString()
    }

    private const val HEX = "0123456789ABCDEF"
}
