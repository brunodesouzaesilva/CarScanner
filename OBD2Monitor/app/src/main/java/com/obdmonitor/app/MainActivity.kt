package com.obdmonitor.app

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.pm.PackageManager
import android.os.*
import android.view.View
import android.widget.*
import java.util.UUID
import java.util.ArrayDeque
import java.util.Locale

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var readings: TextView
    private lateinit var logView: TextView
    private lateinit var scanButton: Button
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var dtcButton: Button
    private var manager: BluetoothManager? = null
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var rx: BluetoothGattCharacteristic? = null
    private val queue = ArrayDeque<String>()
    private var waiting = false
    private var running = false
    private var connected = false
    private var receiving = StringBuilder()
    private var currentCommand = ""
    private var timeoutTask: Runnable? = null
    private var pollTask: Runnable? = null
    private var scanTask: Runnable? = null
    private val metrics = linkedMapOf("RPM" to "—", "Velocidade" to "—", "Temperatura" to "—", "Tensão" to "—")
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        val title = TextView(this).apply { text = "OBD2 Monitor · BLE"; textSize = 24f }
        status = TextView(this).apply { text = "Desconectado"; textSize = 16f }
        readings = TextView(this).apply { textSize = 20f }
        scanButton = Button(this).apply { text = "Buscar OBDII"; setOnClickListener { ensurePermissionsAndScan() } }
        startButton = Button(this).apply { text = "Iniciar leituras"; isEnabled = false; setOnClickListener { startPolling() } }
        stopButton = Button(this).apply { text = "Parar"; setOnClickListener { stopPolling() } }
        dtcButton = Button(this).apply { text = "Consultar falhas (DTC)"; isEnabled = false; setOnClickListener { enqueue("03") } }
        logView = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        listOf(title, status, scanButton, startButton, stopButton, dtcButton, readings, TextView(this).apply { text = "Registro (copie para análise):" }, logView).forEach { root.addView(it) }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        refreshMetrics()
    }

    private fun log(msg: String) { runOnUiThread { logView.append("${System.currentTimeMillis() % 100000}: $msg\n") } }
    private fun state(msg: String) { runOnUiThread { status.text = msg; log(msg) } }
    private fun refreshMetrics() { readings.text = metrics.entries.joinToString("\n") { "${it.key}: ${it.value}" } }

    private fun ensurePermissionsAndScan() {
        val needed = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 42) else scan()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 42 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) scan()
        else state("Permissão Bluetooth negada")
    }
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = try { result.device.name ?: result.scanRecord?.deviceName ?: "" } catch (_: SecurityException) { "" }
            if (name.contains("OBD", true) || name.contains("ELM", true) || name.contains("V-LINK", true)) {
                stopScan()
                state("Encontrado $name; conectando…")
                gatt = result.device.connectGatt(this@MainActivity, false, callback, BluetoothDevice.TRANSPORT_LE)
            }
        }
        override fun onScanFailed(errorCode: Int) { state("Falha no scan: $errorCode") }
    }
    private fun scan() {
        try {
            stopAll()
            val adapter = manager?.adapter
            if (adapter == null || !adapter.isEnabled) { state("Ative o Bluetooth do celular"); return }
            scanner = adapter.bluetoothLeScanner
            state("Buscando OBDII por 15 segundos…")
            scanner?.startScan(scanCallback)
            scanTask = Runnable { stopScan(); if (!connected) state("Busca encerrada. Confira ignição, adaptador e Car Scanner fechado.") }
            handler.postDelayed(scanTask!!, 15000)
        } catch (e: SecurityException) { state("Sem permissão: ${e.message}") }
    }
    private fun stopScan() {
        scanTask?.let { handler.removeCallbacks(it) }; scanTask = null
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
    }
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && statusCode == BluetoothGatt.GATT_SUCCESS) {
                connected = true; state("Conectado; descobrindo serviços…"); g.discoverServices()
            } else { connected = false; running = false; state("Desconectado (status $statusCode)"); runOnUiThread { startButton.isEnabled = false; dtcButton.isEnabled = false } }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) { state("Falha ao descobrir serviços"); return }
            val chars = g.services.flatMap { it.characteristics }
            chars.forEach { log("GATT ${it.uuid} props=${it.properties}") }
            val writable = chars.filter { it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 }
            val readable = chars.filter { it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 }
            val known = listOf("0000fff1", "0000ffe1", "0000fff2")
            tx = writable.firstOrNull { c -> known.any { c.uuid.toString().startsWith(it) } } ?: writable.firstOrNull()
            rx = readable.firstOrNull { it.service.uuid == tx?.service?.uuid } ?: readable.firstOrNull()
            if (tx == null || rx == null) { state("Não foi possível identificar TX/RX. Copie os UUIDs do registro."); return }
            log("TX=${tx?.uuid} RX=${rx?.uuid}")
            val r = rx!!
            g.setCharacteristicNotification(r, true)
            val descriptor = r.getDescriptor(cccd)
            if (descriptor != null) {
                val mode = if (r.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                descriptor.value = mode
                if (!g.writeDescriptor(descriptor)) state("Não foi possível ativar notificações")
            } else ready()
        }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, statusCode: Int) {
            if (statusCode == BluetoothGatt.GATT_SUCCESS) ready() else state("Erro ao habilitar notificações: $statusCode")
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) { onBytes(characteristic.value) }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) { onBytes(value) }
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, statusCode: Int) { if (statusCode != BluetoothGatt.GATT_SUCCESS) { log("Falha escrita: $statusCode"); completeResponse("WRITE_ERROR") } }
    }
    private fun ready() {
        state("BLE pronto; inicializando ELM327")
        runOnUiThread { startButton.isEnabled = true; dtcButton.isEnabled = true }
        listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "0100").forEach { enqueue(it) }
    }
    private fun enqueue(command: String) { handler.post { queue.add(command); sendNext() } }
    private fun sendNext() {
        if (waiting || queue.isEmpty() || !connected) return
        val c = tx ?: return
        currentCommand = queue.removeFirst(); waiting = true; receiving = StringBuilder()
        log("> $currentCommand")
        val bytes = "$currentCommand\r".toByteArray(Charsets.US_ASCII)
        c.value = bytes
        c.writeType = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val ok = try { gatt?.writeCharacteristic(c) == true } catch (_: Exception) { false }
        if (!ok) { completeResponse("WRITE_ERROR"); return }
        timeoutTask = Runnable { log("Timeout: $currentCommand"); completeResponse("TIMEOUT") }
        handler.postDelayed(timeoutTask!!, 3500)
    }
    private fun onBytes(bytes: ByteArray) {
        handler.post {
            if (!waiting) { log("RX espontâneo: ${bytes.toString(Charsets.US_ASCII).trim()}"); return@post }
            receiving.append(bytes.toString(Charsets.US_ASCII))
            if (receiving.contains('>')) completeResponse(receiving.toString())
        }
    }
    private fun completeResponse(raw: String) {
        timeoutTask?.let { handler.removeCallbacks(it) }; timeoutTask = null
        val cmd = currentCommand
        waiting = false
        log("< ${raw.replace("\r", " ").replace("\n", " ").take(350)}")
        parse(cmd, raw)
        handler.postDelayed({ sendNext() }, 180)
    }
    private fun parse(cmd: String, raw: String) {
        val compact = raw.uppercase(Locale.US).replace(" ", "").replace("\r", "").replace("\n", "")
        if (cmd == "ATRV") {
            val v = Regex("(\\d{1,2}\\.\\d{1,2})V").find(raw.uppercase(Locale.US))?.groupValues?.get(1)
            if (v != null) metrics["Tensão"] = "$v V (adaptador)"
        }
        val pid = when (cmd) { "010C" -> "410C"; "010D" -> "410D"; "0105" -> "4105"; else -> null }
        if (pid != null) {
            val m = Regex("$pid([0-9A-F]{2})([0-9A-F]{2})?").find(compact)
            if (m != null) {
                val a = m.groupValues[1].toInt(16)
                val b = m.groupValues.getOrNull(2)?.takeIf { it.length == 2 }?.toInt(16) ?: 0
                when (cmd) {
                    "010C" -> if (m.groupValues[2].length == 2) metrics["RPM"] = "${(a * 256 + b) / 4} rpm"
                    "010D" -> metrics["Velocidade"] = "$a km/h"
                    "0105" -> metrics["Temperatura"] = "${a - 40} °C"
                }
            }
        }
        if (cmd == "03") log("DTC resposta bruta: $compact (não apaga códigos)")
        runOnUiThread { refreshMetrics() }
    }
    private fun startPolling() {
        if (!connected) return
        running = true
        pollTask?.let { handler.removeCallbacks(it) }
        val cycle = object : Runnable {
            override fun run() {
                if (!running || !connected) return
                listOf("010C", "010D", "0105", "ATRV").forEach { enqueue(it) }
                handler.postDelayed(this, 5000)
            }
        }
        pollTask = cycle
        handler.post(cycle)
        state("Leitura periódica iniciada")
    }
    private fun stopPolling() { running = false; pollTask?.let { handler.removeCallbacks(it) }; pollTask = null; state("Leitura periódica parada") }
    private fun stopAll() {
        stopPolling(); stopScan(); timeoutTask?.let { handler.removeCallbacks(it) }; timeoutTask = null
        queue.clear(); waiting = false; connected = false; tx = null; rx = null
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}; gatt = null
    }
    override fun onDestroy() { stopAll(); super.onDestroy() }
}
