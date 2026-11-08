package com.btclotteryminer.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private var soloWorker: SoloWorker? = null
    private val events = ArrayDeque<String>()
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var wallet: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var state: TextView
    private lateinit var speed: TextView
    private lateinit var hashes: TextView
    private lateinit var shares: TextView
    private lateinit var difficulty: TextView
    private lateinit var network: TextView
    private lateinit var device: TextView
    private lateinit var log: TextView
    private var mining = false
    private var closing = false
    private val alive = AtomicBoolean(true)
    private val green = Color.rgb(22, 145, 65)
    private val red = Color.rgb(190, 36, 42)
    private val gray = Color.rgb(204, 204, 204)

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float = 15f, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.rgb(40, 48, 60))
            if (bold) setTypeface(null, Typeface.BOLD)
        }
    private fun card(title: String, parent: LinearLayout, content: TextView) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.rgb(245, 247, 250))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(225, 230, 237))
            }
        }
        container.addView(label(title, 16f, true))
        container.addView(content)
        parent.addView(container, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(10)
        })
    }
    private fun field(name: String, value: String, numeric: Boolean = false): EditText =
        EditText(this).apply {
            hint = name
            setSingleLine(true)
            setText(value)
            if (numeric) inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 16f
        }
    private fun controls(active: Boolean) {
        mining = active
        state.text = if (active) "ESTADO: MINANDO SOLO" else "ESTADO: DETENIDO"
        state.setTextColor(if (active) green else Color.DKGRAY)
        startButton.backgroundTintList = ColorStateList.valueOf(if (active) green else gray)
        startButton.setTextColor(if (active) Color.WHITE else Color.BLACK)
        stopButton.backgroundTintList = ColorStateList.valueOf(if (active) gray else red)
        stopButton.setTextColor(if (active) Color.BLACK else Color.WHITE)
        startButton.isEnabled = !active && !closing
        stopButton.isEnabled = active && !closing
        host.isEnabled = !active && !closing
        port.isEnabled = !active && !closing
        wallet.isEnabled = !active && !closing
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(32))
        }
        scroll.addView(root)
        root.addView(label("₿ BTC Lottery Miner", 24f, true))
        root.addView(label("Android 2.2.1 · Alpha 11 · Bitcoin SOLO", 13f))
        root.addView(label("CONFIGURACIÓN", 16f, true).apply { setPadding(0, dp(20), 0, dp(6)) })
        host = field("Pool Stratum", "public-pool.io")
        port = field("Puerto", "21496", true)
        wallet = field("Dirección Bitcoin", "")
        root.addView(label("Pool Stratum"))
        root.addView(host)
        root.addView(label("Puerto"))
        root.addView(port)
        root.addView(label("Dirección Bitcoin (no se guarda)"))
        root.addView(wallet)
        startButton = Button(this).apply {
            text = "INICIO DE MINERÍA"
            setOnClickListener { startSolo() }
        }
        stopButton = Button(this).apply {
            text = "DETENER MINERÍA"
            setOnClickListener {
                if (soloWorker?.isRunning() == true && !closing) {
                    closing = true
                    state.text = "ESTADO: DETENIENDO..."
                    controls(true)
                    soloWorker?.stop()
                }
            }
        }
        root.addView(startButton)
        root.addView(stopButton)
        state = label("ESTADO: DETENIDO", 18f, true)
        root.addView(state)
        speed = label("Hashrate: —", 17f)
        hashes = label("Hashes: —")
        shares = label("Aceptadas: 0 · Rechazadas: 0")
        difficulty = label("Dificultad de share: —")
        network = label("Precio BTC/USD: —\nAltura de bloque: —\nTarifa rápida: —")
        device = label("Batería: —\nTemperatura batería: —\nEstado térmico: —")
        log = label("Esperando conexión...", 13f)
        card("RENDIMIENTO", root, LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(speed); addView(hashes); addView(shares); addView(difficulty)
        }.let { wrap -> label("").also { /* wrapper attached below */ } })
        // Replace the placeholder card content with the actual metrics container.
        val performance = root.getChildAt(root.childCount - 1) as LinearLayout
        performance.removeViewAt(1)
        performance.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(speed); addView(hashes); addView(shares); addView(difficulty)
        })
        card("RED BITCOIN · mempool.space", root, network)
        card("DISPOSITIVO", root, device)
        card("EVENTOS STRATUM · ÚLTIMOS 12", root, log)
        root.addView(label("Minería experimental en primer plano. Solo cuentan las shares confirmadas por el servidor.", 12f).apply {
            setPadding(0, dp(16), 0, 0)
        })
        setContentView(scroll)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        controls(false)
        refreshDevice()
        refreshNetwork()
    }

    private fun startSolo() {
        if (soloWorker?.isRunning() == true || closing) return
        val h = host.text.toString().trim()
        val p = port.text.toString().toIntOrNull()
        val address = wallet.text.toString().trim()
        if (h.isBlank() || p == null || p !in 1..65535 ||
            !address.matches(Regex("^(bc1[ac-hj-np-z02-9]{11,87}|[13][a-km-zA-HJ-NP-Z1-9]{25,34})$"))) {
            Toast.makeText(this, "Introduce pool, puerto y dirección Bitcoin válida.", Toast.LENGTH_LONG).show()
            return
        }
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(wallet.windowToken, 0)
        wallet.clearFocus()
        state.text = "ESTADO: CONECTANDO..."
        startButton.isEnabled = false
        soloWorker = SoloWorker(applicationContext) { msg ->
            ui.post {
                if (!alive.get()) return@post
                when {
                    msg == "Motor detenido" -> {
                        closing = false
                        controls(false)
                    }
                    msg.startsWith("Trabajo real:") -> {
                        if (!mining) controls(true)
                        val rate = Regex("Trabajo real: (\\d+) H/s").find(msg)?.groupValues?.get(1)
                        val count = Regex("hashes=(\\d+)").find(msg)?.groupValues?.get(1)
                        val diff = Regex("dificultad share=([0-9.Ee+-]+)").find(msg)?.groupValues?.get(1)
                        val accepted = Regex("aceptadas=(\\d+)").find(msg)?.groupValues?.get(1)
                        val rejected = Regex("rechazadas=(\\d+)").find(msg)?.groupValues?.get(1)
                        if (rate != null) speed.text = "Hashrate: $rate H/s"
                        if (count != null) hashes.text = "Hashes calculados: $count"
                        if (diff != null) difficulty.text = "Dificultad de share: $diff"
                        shares.text = "Aceptadas: ${accepted ?: "0"} · Rechazadas: ${rejected ?: "0"}"
                    }
                    msg == "Autorización rechazada" -> state.text = "ESTADO: AUTORIZACIÓN RECHAZADA"
                    msg == "Autorización confirmada" -> state.text = "ESTADO: AUTORIZADO, ESPERANDO TRABAJO"
                }
                if (!msg.startsWith("Trabajo real:")) {
                    events.addFirst(msg)
                    while (events.size > 12) events.removeLast()
                    log.text = events.joinToString("\n")
                }
            }
        }
        soloWorker?.start(h, p, address)
    }

    private fun getJson(path: String): JSONObject {
        val connection = URL("https://mempool.space/api/$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
    private fun getText(path: String): String {
        val connection = URL("https://mempool.space/api/$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }
    private fun refreshNetwork() {
        thread(name = "bitcoin-network-data") {
            val result = try {
                val prices = getJson("v1/prices")
                val height = getText("blocks/tip/height").trim()
                val fees = getJson("v1/fees/recommended")
                "Precio BTC/USD: $${prices.optString("USD", "—")}\nAltura de bloque: $height\nTarifa rápida: ${fees.optString("fastestFee", "—")} sat/vB"
            } catch (_: Exception) {
                "Datos de red no disponibles (sin conexión o servicio inaccesible)."
            }
            ui.post { if (alive.get()) network.text = result }
        }
        ui.postDelayed({ if (alive.get()) refreshNetwork() }, 90_000L)
    }
    private fun refreshDevice() {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermal = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus.toString() else "No disponible"
        val percentage = if (level >= 0 && scale > 0) "${level * 100 / scale} %" else "No disponible"
        val temperature = if (tenths >= 0) "${tenths / 10.0} °C (batería)" else "Sensor no disponible"
        device.text = "Batería: $percentage\nTemperatura: $temperature\nEstado térmico Android: $thermal\nTemperatura CPU: no disponible"
        ui.postDelayed({ if (alive.get()) refreshDevice() }, 15_000L)
    }
    override fun onDestroy() {
        alive.set(false)
        soloWorker?.stop()
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
