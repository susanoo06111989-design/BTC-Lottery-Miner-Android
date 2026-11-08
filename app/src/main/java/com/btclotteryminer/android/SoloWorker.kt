package com.btclotteryminer.android

import android.content.Context
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Foreground-only Stratum v1 CPU worker. No service, no background mining.
 * Share submissions require an explicit server reply to be counted as accepted.
 */
class SoloWorker(private val context: Context, private val report: (String) -> Unit) {
    private val active = AtomicBoolean(false)
    @Volatile private var socket: Socket? = null
    private var threadHandle: Thread? = null
    fun isRunning() = active.get()
    fun stop() {
        active.set(false)
        try { socket?.close() } catch (_: Exception) {}
        threadHandle?.interrupt()
    }
    fun start(host: String, port: Int, address: String, workerName: String) {
        if (!active.compareAndSet(false, true)) return
        threadHandle = thread(name = "bitcoin-solo-stratum") {
            var failures = 0
            try {
                while (active.get()) {
                    try {
                        runSession(host, port, address, workerName)
                        if (active.get()) throw java.io.EOFException("Sesión Stratum finalizada")
                    } catch (e: Exception) {
                        if (!active.get()) break
                        failures++
                        val delaySeconds = minOf(60, 3 * (1 shl minOf(failures - 1, 4)))
                        report("Conexión interrumpida: ${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}")
                        report("RECONEXIÓN #$failures en $delaySeconds s")
                        // Sleep in small intervals so manual STOP is always responsive.
                        repeat(delaySeconds * 5) {
                            if (!active.get()) return@thread
                            try { Thread.sleep(200) } catch (_: InterruptedException) {
                                if (!active.get()) return@thread
                            }
                        }
                    } finally {
                        try { socket?.close() } catch (_: Exception) {}
                        socket = null
                    }
                }
            } finally {
                active.set(false)
                socket = null
                report("Motor detenido")
            }
        }
    }

    private fun send(writer: BufferedWriter, obj: JSONObject) {
        writer.write(obj.toString()); writer.newLine(); writer.flush()
    }
    private fun runSession(host: String, port: Int, address: String, workerName: String) {\n        val username = "$address.$workerName"
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        Socket().use { conn ->
            socket = conn
            conn.connect(InetSocketAddress(host, port), 6000)
            conn.soTimeout = 1200
            val reader = conn.getInputStream().bufferedReader()
            val writer = conn.getOutputStream().bufferedWriter()
            send(writer, JSONObject().put("id", 1).put("method", "mining.subscribe").put("params", JSONArray()))
            report("TCP conectado; esperando suscripción")
            var extra1 = ""
            var extra2Size = 0
            var authorized = false
            var difficulty = Double.NaN
            var job: JSONArray? = null
            var generation = 0L
            var nextExtra2 = 0L
            var pendingId = 10
            val pending = mutableSetOf<Int>()
            var accepted = 0
            var rejected = 0
            var hashes = 0L
            val started = System.nanoTime()
            var lastStatusAt = 0L
            var lastThermalReport = 0L
            while (active.get()) {
                // Read pending Stratum messages before each bounded work chunk.
                try {
                    val line = reader.readLine()
                    if (line != null) {
                        val msg = JSONObject(line)
                        val id = msg.optInt("id", -1)
                        when {
                            id == 1 -> {
                                val res = msg.optJSONArray("result")
                                extra1 = res?.optString(1) ?: ""
                                extra2Size = res?.optInt(2) ?: 0
                                if (extra1.isBlank() || extra2Size !in 1..8) throw IllegalStateException("Suscripción inválida")
                                send(writer, JSONObject().put("id", 2).put("method", "mining.authorize")
                                    .put("params", JSONArray().put(username).put("x")))
                            }
                            id == 2 -> {
                                authorized = msg.optBoolean("result", false)
                                report(if (authorized) "Autorización confirmada" else "Autorización rechazada")
                                if (!authorized) throw IllegalStateException("Autorización rechazada")
                            }
                            id in pending -> {
                                pending.remove(id)
                                if (msg.optBoolean("result", false)) accepted++ else rejected++
                                report("Shares servidor: aceptadas=$accepted rechazadas=$rejected")
                            }
                            msg.optString("method") == "mining.set_difficulty" -> {
                                difficulty = msg.optJSONArray("params")?.optDouble(0) ?: Double.NaN
                                report("Dificultad de share (servidor): $difficulty; objetivo medio: " + if (difficulty.isFinite() && difficulty > 0.0) "%.3g".format(java.util.Locale.US, difficulty * 4294967296.0) + " hashes/share" else "no disponible")
                            }
                            msg.optString("method") == "mining.notify" -> {
                                val params = msg.optJSONArray("params")
                                if (params != null && params.length() >= 9) {
                                    job = params
                                    generation++
                                    nextExtra2 = 0
                                    report("Nuevo trabajo: ${params.optString(0)}; objetivo de bloque nBits=${params.optString(6)} (independiente de dificultad share)")
                                }
                            }
                        }
                    } else throw java.io.EOFException("El servidor cerró la conexión")
                } catch (_: java.net.SocketTimeoutException) {}
                if (!active.get()) break
                if (pm.isDeviceIdleMode) { Thread.sleep(1000); continue }
                val thermal = if (android.os.Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else 0
                if (thermal >= PowerManager.THERMAL_STATUS_MODERATE) {
                    if (System.nanoTime() - lastThermalReport > 10_000_000_000L) {
                        report("Pausa térmica: estado Android $thermal")
                        lastThermalReport = System.nanoTime()
                    }
                    Thread.sleep(2000)
                    continue
                }
                val params = job
                if (!authorized || extra1.isBlank() || params == null || !difficulty.isFinite() || difficulty <= 0.0) continue
                val currentGeneration = generation
                val extra2 = nextExtra2++.toString(16).padStart(extra2Size * 2, '0')
                if (extra2.length > extra2Size * 2) throw IllegalStateException("Espacio extranonce agotado")
                val branches = params.getJSONArray(4)
                val merkle = BitcoinWork.merkleRoot(params.getString(2), extra1, extra2,
                    params.getString(3), (0 until branches.length()).map { branches.getString(it) })
                val version = params.getString(5)
                val ntime = params.getString(7)
                val header = BitcoinWork.header(version, params.getString(1), merkle, ntime, params.getString(6), 0)
                val shareTarget = BitcoinWork.shareTarget(difficulty)
                val blockTarget = BitcoinWork.compactTarget(params.getString(6))
                val digest = MessageDigest.getInstance("SHA-256")
                // Small batches keep the worker responsive to new jobs and thermal state.
                var nonce = 0L
                while (active.get() && nonce < 200_000L && generation == currentGeneration) {
                    for (i in 0..3) header[76+i] = (nonce ushr (8*i)).toByte()
                    val hash = BigInteger(1, digest.digest(digest.digest(header)).reversedArray())
                    if (hash <= shareTarget) {
                        val id = pendingId++
                        pending.add(id)
                        send(writer, JSONObject().put("id", id).put("method", "mining.submit")
                            .put("params", JSONArray().put(username).put(params.getString(0))
                                .put(extra2).put(ntime).put(nonce.toString(16).padStart(8, '0'))))
                        report(if (hash <= blockTarget) "CANDIDATO DE BLOQUE enviado; esperando respuesta" else "Share candidata enviada; esperando respuesta")
                    }
                    nonce++; hashes++
                    if (nonce % 8192L == 0L) {
                        if (!active.get() || generation != currentGeneration) break
                        val elapsed = (System.nanoTime() - started) / 1e9
                        if (elapsed > 0 && System.nanoTime() - lastStatusAt > 2_000_000_000L) {
                            report("Trabajo real: ${(hashes / elapsed).toLong()} H/s; hashes=$hashes; dificultad share=$difficulty; aceptadas=$accepted; rechazadas=$rejected")
                            lastStatusAt = System.nanoTime()
                        }
                        if (android.os.Build.VERSION.SDK_INT >= 29 && pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) break
                    }
                }
            }
        }
    }
}
