package com.btclotteryminer.android

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val running = AtomicBoolean(false)
    private val ui = Handler(Looper.getMainLooper())
    private var worker: Thread? = null
    private lateinit var status: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 24)
        }
        layout.addView(TextView(this).apply {
            text = "BTC Lottery Miner Android 2.2.1 — Alpha 8"
            textSize = 22f
        })
        status = TextView(this).apply {
            text = "SHA-256d local benchmark. No Stratum connection or Bitcoin mining yet."
            textSize = 16f
            setPadding(0, 24, 0, 24)
        }
        layout.addView(status)
        layout.addView(Button(this).apply {
            text = "INICIAR PRUEBA SHA-256d"
            setOnClickListener { startBenchmark() }
        })
        layout.addView(Button(this).apply {
            text = "DETENER"
            setOnClickListener { stopBenchmark() }
        })
        setContentView(layout)
    }
    private fun startBenchmark() {
        if (!running.compareAndSet(false, true)) return
        status.text = "Calculando hashes SHA-256d locales..."
        worker = thread(name = "sha256d-benchmark") {
            val digest = MessageDigest.getInstance("SHA-256")
            val header = ByteArray(80)
            var count = 0L
            var start = System.nanoTime()
            while (running.get() && !Thread.currentThread().isInterrupted) {
                val nonce = count.toInt()
                for (i in 0..3) header[76 + i] = (nonce ushr (i * 8)).toByte()
                digest.digest(digest.digest(header))
                count++
                if (count % 8192L == 0L) {
                    val now = System.nanoTime()
                    if (now - start >= 1_000_000_000L) {
                        val rate = (count * 1_000_000_000.0 / (now - start)).toLong()
                        ui.post { if (running.get()) status.text = "Benchmark SHA-256d: $rate H/s\nHashes: $count\nNO es minería Bitcoin real" }
                    }
                }
            }
        }
    }
    private fun stopBenchmark() {
        running.set(false)
        worker?.interrupt()
        status.text = "Prueba detenida."
    }
    override fun onDestroy() {
        stopBenchmark()
        super.onDestroy()
    }
}
