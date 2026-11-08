package com.btclotteryminer.android

import java.security.MessageDigest

/**
 * Bitcoin Stratum v1 job encoding primitives.
 * This module builds candidate headers; it does not submit shares or claim wins.
 */
object BitcoinWork {
    fun hexBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0 && hex.all { it in "0123456789abcdefABCDEF" }) { "Invalid hex" }
        return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    fun sha256d(bytes: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(md.digest(bytes))
    }
    fun reverse(bytes: ByteArray) = bytes.reversedArray()
    private fun u32le(value: Long) = ByteArray(4) { i -> (value ushr (i * 8)).toByte() }

    fun merkleRoot(coinbase1: String, extranonce1: String, extranonce2: String,
                   coinbase2: String, branches: List<String>): ByteArray {
        var current = sha256d(hexBytes(coinbase1 + extranonce1 + extranonce2 + coinbase2))
        for (branch in branches) {
            require(branch.length == 64) { "Invalid Merkle branch" }
            current = sha256d(current + hexBytes(branch))
        }
        return current
    }

    /**
     * Stratum v1 version, previous hash and ntime are encoded in 4-byte words
     * as received. prevhash is converted by reversing each 4-byte word.
     * The merkle root digest is already in internal byte order.
     */
    fun header(version: String, prevHash: String, merkle: ByteArray,
               ntime: String, nbits: String, nonce: Long): ByteArray {
        require(version.length == 8 && ntime.length == 8 && nbits.length == 8)
        require(prevHash.length == 64 && merkle.size == 32)
        require(nonce in 0..0xffffffffL)
        val previous = hexBytes(prevHash)
        val previousLE = ByteArray(32)
        for (i in 0 until 8) for (j in 0 until 4) previousLE[i * 4 + j] = previous[i * 4 + (3 - j)]
        val result = hexBytes(version).reversedArray() + previousLE + merkle +
            hexBytes(ntime).reversedArray() + hexBytes(nbits).reversedArray() + u32le(nonce)
        check(result.size == 80)
        return result
    }

    /** For a raw network-order 80-byte Bitcoin block header. */
    fun hashDisplay(header: ByteArray): String {
        require(header.size == 80)
        return hex(reverse(sha256d(header)))
    }
}
