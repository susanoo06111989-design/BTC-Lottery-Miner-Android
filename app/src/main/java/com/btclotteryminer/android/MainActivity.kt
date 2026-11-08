package com.btclotteryminer.android

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val running = AtomicBoolean(false)
    private val ui = Handler(Looper.getMainLooper())
    private var worker: Thread? = null
    private lateinit var status: TextView
    private lateinit var wallet: EditText
    private lateinit var host: EditText
    private lateinit var port: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 24)
        }
        val scroll = ScrollView(this)
        scroll.addView(layout)
        layout.addView(TextView(this).apply { text = "BTC Lottery Miner — Alpha 11"; textSize = 23f })
        layout.addView(TextView(this).apply {
            text = "Diagnóstico Stratum: construcción de cabeceras de trabajos reales. No envía shares."
            textSize = 15f
        })
        host = EditText(this).apply { setSingleLine(); hint = "Stratum host"; setText("public-pool.io") }
        port = EditText(this).apply { setSingleLine(); hint = "Stratum port"; inputType = 2; setText("21496") }
        wallet = EditText(this).apply { setSingleLine(); hint = "Bitcoin address (not stored)" }
        layout.addView(host)
        layout.addView(port)
        layout.addView(wallet)
        status = TextView(this).apply { text = "Ready"; textSize = 16f; setPadding(0, 24, 0, 24) }
        layout.addView(status)
        layout.addView(Button(this).apply {
            text = "PROBAR CONEXIÓN STRATUM"
            setOnClickListener { diagnose() }
        })
        layout.addView(Button(this).apply {
            text = "INICIAR BENCHMARK SHA-256d"
            setOnClickListener { startBenchmark() }
        })
        layout.addView(Button(this).apply { text = "DETENER BENCHMARK"; setOnClickListener { stopBenchmark() } })
        setContentView(scroll)
    }

    private fun diagnose() {
        if (running.get()) { status.text = "Detén el benchmark primero."; return }
        val hostname = host.text.toString().trim()
        val portNumber = port.text.toString().toIntOrNull()
        val address = wallet.text.toString().trim()
        if (hostname.isBlank() || portNumber == null || portNumber !in 1..65535 || address.isBlank()) {
            status.text = "Introduce servidor, puerto y dirección Bitcoin."; return
        }
        status.text = "Conectando…"
        thread(name = "stratum-diagnostic") {
            val result = StringBuilder()
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(hostname, portNumber), 6000)
                    socket.soTimeout = 8000
                    result.append("TCP conectado\n")
                    val writer = socket.getOutputStream().bufferedWriter()
                    val reader = socket.getInputStream().bufferedReader()
                    writer.write(JSONObject().put("id", 1).put("method", "mining.subscribe").put("params", org.json.JSONArray()).toString() + "\n")
                    writer.flush()
                    var subscribed = false
                    var authorized = false
                    var gotJob = false
                    var extranonce1 = ""
                    var extranonce2Size = 0
                    val deadline = System.currentTimeMillis() + 12000
                    while (System.currentTimeMillis() < deadline && !gotJob) {
                        val line = try { reader.readLine() } catch (_: java.net.SocketTimeoutException) { break } ?: break
                        val obj = try { JSONObject(line) } catch (_: Exception) { continue }
                        if (obj.optInt("id", -1) == 1 && !obj.isNull("result")) {
                            val response = obj.optJSONArray("result")
                            extranonce1 = response?.optString(1) ?: ""
                            extranonce2Size = response?.optInt(2) ?: 0
                            subscribed = extranonce1.isNotBlank() && extranonce2Size in 1..16
                            result.append(if (subscribed) "Extranonce recibido\n" else "Suscripción incompleta\n")
                            writer.write(JSONObject().put("id", 2).put("method", "mining.authorize").put("params", org.json.JSONArray().put(address).put("x")).toString() + "\n")
                            writer.flush()
                        }
                        if (obj.optInt("id", -1) == 2) {
                            authorized = obj.optBoolean("result", false)
                            result.append(if (authorized) "Authorization accepted\n" else "Authorization NOT confirmed\n")
                        }
                        when (obj.optString("method")) {
                            "mining.set_difficulty" -> result.append("Difficulty message received\n")
                            "mining.notify" -> {
                                gotJob = true
                                val params = obj.optJSONArray("params")
                                if (!subscribed || params == null || params.length() < 9) {
                                    result.append("Trabajo incompleto\n")
                                } else try {
                                    val branchArray = params.getJSONArray(4)
                                    val branches = (0 until branchArray.length()).map { branchArray.getString(it) }
                                    val merkle = BitcoinWork.merkleRoot(
                                        params.getString(2), extranonce1,
                                        "00".repeat(extranonce2Size), params.getString(3), branches
                                    )
                                    val header = BitcoinWork.header(
                                        params.getString(5), params.getString(1), merkle,
                                        params.getString(7), params.getString(6), 0L
                                    )
                                    result.append("Job ID: ").append(params.getString(0).take(24)).append("\n")
                                    result.append("Cabecera: ").append(header.size).append(" bytes\n")
                                    result.append("Hash nonce=0: ").append(BitcoinWork.hashDisplay(header)).append("\n")
                                    result.append("Sin envío de shares; no es minería continua.\n")
                                } catch (e: Exception) {
                                    result.append("Error construyendo trabajo: ").append(e.message).append("\n")
                                }
                            }
                        }
                    }
                    if (!subscribed) result.append("Subscription not confirmed\n")
                    if (!authorized) result.append("Authorization not confirmed\n")
                    if (!gotJob) result.append("No job confirmed\n")
                }
            } catch (e: Exception) { result.append("Connection error: ").append(e.javaClass.simpleName) }
            ui.post { status.text = result.toString() }
        }
    }

    private fun startBenchmark() {
        if (!running.compareAndSet(false, true)) return
        status.text = "Calculando hashes SHA-256d locales..."
        worker = thread(name = "sha256d-benchmark") {
            val digest = MessageDigest.getInstance("SHA-256")
            val header = ByteArray(80)
            var count = 0L
            val start = System.nanoTime()
            while (running.get() && !Thread.currentThread().isInterrupted) {
                val nonce = count.toInt()
                for (i in 0..3) header[76 + i] = (nonce ushr (i * 8)).toByte()
                digest.digest(digest.digest(header))
                count++
                if (count % 8192L == 0L) {
                    val elapsed = System.nanoTime() - start
                    if (elapsed >= 1_000_000_000L) {
                        val rate = (count * 1_000_000_000.0 / elapsed).toLong()
                        ui.post { if (running.get()) status.text = "SHA-256d: $rate H/s\nHashes: $count\nLocal benchmark ONLY" }
                    }
                }
            }
        }
    }
    private fun stopBenchmark() {
        running.set(false)
        worker?.interrupt()
        if (::status.isInitialized) status.text = "Benchmark detenido."
    }
    override fun onDestroy() { stopBenchmark(); super.onDestroy() }
}
