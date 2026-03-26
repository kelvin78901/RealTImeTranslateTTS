package com.example.myapplication1.translation

/**
 * Lightweight parser for SentencePiece .spm model files (protobuf ModelProto).
 * Extracts vocabulary pieces (token string, score, type) without any protobuf dependency.
 *
 * Wire format reference:
 *   ModelProto { repeated SentencePiece pieces = 1; ... }
 *   SentencePiece { string piece = 1; float score = 2; Type type = 3; }
 */
object SpmParser {

    data class SpmPiece(val piece: String, val score: Float, val type: Int) {
        companion object {
            const val TYPE_NORMAL = 1
            const val TYPE_UNKNOWN = 2
            const val TYPE_CONTROL = 3
            const val TYPE_USER_DEFINED = 4
            const val TYPE_BYTE = 6
        }
    }

    fun parse(data: ByteArray): List<SpmPiece> {
        val pieces = mutableListOf<SpmPiece>()
        var pos = 0
        while (pos < data.size) {
            val (tag, nextPos) = readVarint(data, pos) ?: break
            pos = nextPos
            val fieldNum = (tag ushr 3).toInt()
            val wireType = (tag and 0x7).toInt()

            if (fieldNum == 1 && wireType == 2) {
                // field 1 = repeated SentencePiece (length-delimited message)
                val (len, dataStart) = readVarint(data, pos) ?: break
                pos = dataStart
                val endPos = pos + len.toInt()
                if (endPos > data.size) break
                pieces.add(parsePiece(data, pos, endPos))
                pos = endPos
            } else {
                pos = skipField(data, pos, wireType) ?: break
            }
        }
        return pieces
    }

    // ---- internal helpers ----

    private fun parsePiece(data: ByteArray, start: Int, end: Int): SpmPiece {
        var piece = ""
        var score = 0f
        var type = SpmPiece.TYPE_NORMAL
        var pos = start

        while (pos < end) {
            val (tag, nextPos) = readVarint(data, pos) ?: break
            pos = nextPos
            val fieldNum = (tag ushr 3).toInt()
            val wireType = (tag and 0x7).toInt()

            when {
                fieldNum == 1 && wireType == 2 -> {
                    // string piece
                    val (len, dp) = readVarint(data, pos) ?: break
                    pos = dp
                    val strEnd = pos + len.toInt()
                    if (strEnd > end) break
                    piece = String(data, pos, len.toInt(), Charsets.UTF_8)
                    pos = strEnd
                }
                fieldNum == 2 && wireType == 5 -> {
                    // float score (little-endian fixed32)
                    if (pos + 4 > end) break
                    score = Float.fromBits(
                        (data[pos].toInt() and 0xFF) or
                        ((data[pos + 1].toInt() and 0xFF) shl 8) or
                        ((data[pos + 2].toInt() and 0xFF) shl 16) or
                        ((data[pos + 3].toInt() and 0xFF) shl 24)
                    )
                    pos += 4
                }
                fieldNum == 3 && wireType == 0 -> {
                    // enum Type (varint)
                    val (v, np) = readVarint(data, pos) ?: break
                    type = v.toInt()
                    pos = np
                }
                else -> {
                    pos = skipField(data, pos, wireType) ?: break
                }
            }
        }
        return SpmPiece(piece, score, type)
    }

    private fun skipField(data: ByteArray, pos: Int, wireType: Int): Int? = when (wireType) {
        0 -> readVarint(data, pos)?.second
        1 -> if (pos + 8 <= data.size) pos + 8 else null
        2 -> {
            val (len, dataStart) = readVarint(data, pos) ?: return null
            val end = dataStart + len.toInt()
            if (end <= data.size) end else null
        }
        5 -> if (pos + 4 <= data.size) pos + 4 else null
        else -> null
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var pos = start
        while (pos < data.size) {
            val b = data[pos].toInt() and 0xFF
            result = result or ((b.toLong() and 0x7F) shl shift)
            pos++
            if (b and 0x80 == 0) return result to pos
            shift += 7
            if (shift > 63) return null
        }
        return null
    }
}
