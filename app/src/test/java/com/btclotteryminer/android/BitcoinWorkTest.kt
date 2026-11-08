package com.btclotteryminer.android

import org.junit.Assert.*
import org.junit.Test

class BitcoinWorkTest {
    @Test fun genesisHeaderHash() {
        val header = BitcoinWork.hexBytes(
            "01000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "3ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a" +
            "29ab5f49" + "ffff001d" + "1dac2b7c"
        )
        assertEquals(80, header.size)
        assertEquals(
            "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f",
            BitcoinWork.hashDisplay(header)
        )
    }
    @Test fun headerHasCorrectSizeAndNonce() {
        val h = BitcoinWork.header("00000001", "00".repeat(32), ByteArray(32),
            "495fab29", "1d00ffff", 0x7c2bac1d)
        assertEquals(80, h.size)
        assertEquals("1dac2b7c", BitcoinWork.hex(h.copyOfRange(76, 80)))
    }
    @Test fun genesisNetworkTarget() {
        assertEquals(BitcoinWork.difficultyOneTarget, BitcoinWork.compactTarget("1d00ffff"))
        val genesis = BitcoinWork.hexBytes(
            "01000000" + "00".repeat(32) +
            "3ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a" +
            "29ab5f49" + "ffff001d" + "1dac2b7c"
        )
        assertTrue(BitcoinWork.hashMeetsTarget(genesis, BitcoinWork.compactTarget("1d00ffff")))
        assertFalse(BitcoinWork.hashMeetsTarget(genesis, java.math.BigInteger.ONE))
    }
    @Test fun shareDifficultyOneTarget() {
        assertEquals(BitcoinWork.difficultyOneTarget, BitcoinWork.shareTarget(1.0))
    }
    @Test fun rejectsMalformedHex() {
        try { BitcoinWork.hexBytes("0z"); fail("Should reject malformed hex") }
        catch (_: IllegalArgumentException) {}
    }
}
