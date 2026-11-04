package com.androidtabletcast

fun ByteArray.toHex(): String {
    val chars = CharArray(size * 2)
    val alphabet = "0123456789abcdef"
    for (index in indices) {
        val value = this[index].toInt() and 0xFF
        chars[index * 2] = alphabet[value ushr 4]
        chars[index * 2 + 1] = alphabet[value and 0x0F]
    }
    return String(chars)
}

fun hexToBytes(value: String): ByteArray {
    val text = value.trim()
    if (text.length % 2 != 0) {
        throw IllegalArgumentException("16進文字列の長さが奇数です")
    }
    val out = ByteArray(text.length / 2)
    for (index in out.indices) {
        out[index] = text.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
    return out
}
