package edu.cornell.em577.tamperprooflogging.util

private val HEX_CHARS = "0123456789ABCDEF"

/**
 * Converts the hex string to its corresponding ByteArray representation. Paired with
 * ByteArray.toHex
 */
fun String.hexStringToByteArray() : ByteArray {

    val result = ByteArray(length / 2)

    for (i in 0 until length step 2) {
        val firstIndex = HEX_CHARS.indexOf(this[i])
        val secondIndex = HEX_CHARS.indexOf(this[i + 1])

        val octet = firstIndex.shl(4).or(secondIndex)
        result.set(i.shr(1), octet.toByte())
    }

    return result
}