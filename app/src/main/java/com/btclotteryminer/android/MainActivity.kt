package com.btclotteryminer.android

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply {
            text = "BTC Lottery Miner Android 2.2.1 - Alpha 7\n\nAPK compilation scaffold. Mining engine is NOT integrated yet."
            textSize = 20f
            setPadding(32, 64, 32, 32)
        }
        setContentView(status)
    }
}
