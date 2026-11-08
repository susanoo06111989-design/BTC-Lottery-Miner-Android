package com.btclotteryminer.android

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.inputmethod.InputMethodManager
import android.content.Context
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
    private var soloWorker: SoloWorker? = null
    private lateinit var status: TextView
    private lateinit var miningLog: TextView
    private lateinit var motorState: TextView
    private lateinit var startMiningButton: Button
    private lateinit var stopMiningButton: Button
    private var miningConnected = false
    private val recentEvents = ArrayDeque<String>()
    private lateinit var wallet: EditText
    private lateinit var host: EditText
    private lateinit var port: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 24)
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            scrollBarStyle = android.view.View.SCROLLBARS_INSIDE_INSET
            isFillViewport = true
        }
        scroll.addView(layout)
        layout.addView(TextView(this).apply { text = "BTC Lottery Miner — Alpha 11"; textSize = 23f })
        layout.addView(TextView(this).apply {
            text = "Minería SOLO experimental en primer plano. Shares solo cuentan cuando el servidor confirma."
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
        motorState = TextView(this).apply {
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 12, 0, 12)
        }
        layout.addView(motorState)
        miningLog = TextView(this).apply { textSize = 13f; setPadding(0, 8, 0, 12) }
        layout.addView(miningLog)
        startMiningButton = Button(this).apply {
            text = "INICIO DE MINERÍA"
            setOnClickListener { startSolo() }
        }
        layout.addView(startMiningButton)
        stopMiningButton = Button(this).apply {
            text = "DETENER MINERÍA"
            setOnClickListener {
                if (soloWorker?.isRunning() == true) {
                    soloWorker?.stop()
                    motorState.text = "DETENIENDO..."
                    status.text = "Cerrando conexión SOLO..."
                }
            }
        }
        layout.addView(stopMiningButton)
        updateMiningControls(false)
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
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun updateMiningControls(connected: Boolean) {
        miningConnected = connected
        motorState.text = if (connected) "ESTADO: MINANDO SOLO" else "ESTADO: DETENIDO"
        motorState.setTextColor(if (connected) Color.rgb(22, 130, 57) else Color.DKGRAY)
        startMiningButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
            if (connected) Color.rgb(22, 145, 65) else Color.LTGRAY))
        startMiningButton.setTextColor(if (connected) Color.WHITE else Color.BLACK)
        stopMiningButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
            if (connected) Color.LTGRAY else Color.rgb(190, 36, 42)))
        stopMiningButton.setTextColor(if (connected) Color.BLACK else Color.WHITE)
        startMiningButton.isEnabled = !connected
        stopMiningButton.isEnabled = connected
    }

    private fun startSolo() {
        if (running.get()) { status.text = "Detén el benchmark antes."; return }
        val hostname = host.text.toString().trim()
        val portNumber = port.text.toString().toIntOrNull()
        val address = wallet.text.toString().trim()
        if (hostname.isBlank() || portNumber == null || portNumber !in 1..65535 ||
            !address.matches(Regex("^(bc1[ac-hj-np-z02-9]{11,87}|[13][a-km-zA-HJ-NP-Z1-9]{25,34})$"))) {
            status.text = "Introduce servidor, puerto y dirección Bitcoin válida."; return
        }
        if (soloWorker?.isRunning() == true) return
        status.text = "Conectando motor SOLO..."
        motorState.text = "ESTADO: CONECTANDO"
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(wallet.windowToken, 0)
        wallet.clearFocus()
        host.clearFocus()
        port.clearFocus()
        soloWorker = SoloWorker(applicationContext) { message ->
            ui.post {
                if (::status.isInitialized) {
                    if (message == "Motor detenido" || message.startsWith("Sesión terminada:") ||
                        message.startsWith("Desconexión inesperada:") || message == "Autorización rechazada") {
                        updateMiningControls(false)
                    } else if (message == "Autorización confirmada") {
                        motorState.text = "ESTADO: AUTORIZADO, ESPERANDO TRABAJO"
                    } else if (message.startsWith("Trabajo real:")) {
                        if (!miningConnected) updateMiningControls(true)
                    }
                    if (message.startsWith("Trabajo real:")) {
                        status.text = message
                    } else {
                        recentEvents.addFirst(message)
                        while (recentEvents.size > 12) recentEvents.removeLast()
                        miningLog.text = "EVENTOS STRATUM (últimos 12):\n" + recentEvents.joinToString("\n")
                    }
                }
            }
        }
        soloWorker?.start(hostname, portNumber, address)
    }

    private fun diagnose() {
        if (running.get() || soloWorker?.isRunning() == true) { status.text = "Detén el motor activo primero."; return }
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
                    var stratumDifficulty = 1.0
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
                            "mining.set_difficulty" -> {
                                val nextDifficulty = obj.optJSONArray("params")?.optDouble(0) ?: Double.NaN
                                if (nextDifficulty.isFinite() && nextDifficulty > 0.0) {
                                    stratumDifficulty = nextDifficulty
                                    result.append("Dificultad Stratum: ").append(stratumDifficulty).append("\n")
                                } else result.append("Dificultad Stratum inválida\n")
                            }
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
                                    val target = BitcoinWork.compactTarget(params.getString(6))
                                    result.append("Objetivo de bloque: ").append(target.toString(16)).append("\n")
                                    result.append("Nonce 0 cumple objetivo: ")
                                        .append(if (BitcoinWork.hashMeetsTarget(header, target)) "SÍ (candidato; sin confirmar)" else "NO")
                                        .append("\n")
                                    val shareTarget = BitcoinWork.shareTarget(stratumDifficulty)
                                    var shareCandidates = 0
                                    var blockCandidates = 0
                                    val scanStart = System.nanoTime()
                                    val scanHeader = header.copyOf()
                                    val digest = MessageDigest.getInstance("SHA-256")
                                    for (nonce in 0 until 100_000) {
                                        for (i in 0..3) scanHeader[76 + i] = (nonce ushr (i * 8)).toByte()
                                        val hash = java.math.BigInteger(1,
                                            digest.digest(digest.digest(scanHeader)).reversedArray())
                                        if (hash <= shareTarget) shareCandidates++
                                        if (hash <= target) blockCandidates++
                                    }
                                    val seconds = (System.nanoTime() - scanStart) / 1e9
                                    result.append("Nonces de trabajo real probados: 100000\n")
                                    result.append("Velocidad de prueba: ")
                                        .append((100000.0 / seconds).toLong()).append(" H/s\n")
                                    result.append("Candidatas share locales: ").append(shareCandidates).append("\n")
                                    result.append("Candidatas bloque locales: ").append(blockCandidates).append("\n")
                                    result.append("SIN envío al servidor. Diagnóstico limitado, no minería continua.\n")
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
        if (soloWorker?.isRunning() == true || !running.compareAndSet(false, true)) return
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
    override fun onDestroy() { soloWorker?.stop(); stopBenchmark(); super.onDestroy() }
}
