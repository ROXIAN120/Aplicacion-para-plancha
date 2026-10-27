package com.example.aplicacionplancha

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/*
 * Control local directo.
 * La app se comunica con el ESP32 por HTTP en la misma red Wi-Fi,
 * sin Firebase en la ruta crítica del motor o de los relés.
 */
class MainActivity : AppCompatActivity() {
    companion object {
        private const val DEFAULT_DEVICE_URL = "http://192.168.4.1"
        private const val PREFS_NAME = "plancha_settings"
        private const val PREF_DEVICE_URL = "device_url"
        private const val REFRESH_MS = 700L
    }

    private val network = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val requestingStatus = AtomicBoolean(false)
    private val commandInFlight = AtomicBoolean(false)

    private lateinit var editDeviceUrl: EditText
    private lateinit var txtConnection: TextView
    private lateinit var txtTemperature: TextView
    private lateinit var txtPressure: TextView
    private lateinit var txtPressureVoltage: TextView
    private lateinit var txtMotor: TextView
    private lateinit var relayButtons: List<Button>
    private lateinit var motorButtons: List<Button>
    private lateinit var calibrateButton: Button

    private val relayStates = BooleanArray(5)
    private var running = false

    private val refreshLoop = object : Runnable {
        override fun run() {
            if (running) {
                requestStatus()
                ui.postDelayed(this, REFRESH_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        loadDeviceUrl()
        configureControls()
    }

    override fun onResume() {
        super.onResume()
        running = true
        ui.removeCallbacks(refreshLoop)
        ui.post(refreshLoop)
    }

    override fun onPause() {
        running = false
        ui.removeCallbacks(refreshLoop)
        super.onPause()
    }

    override fun onDestroy() {
        network.shutdownNow()
        super.onDestroy()
    }

    private fun bindViews() {
        editDeviceUrl = findViewById(R.id.editDeviceUrl)
        txtConnection = findViewById(R.id.txtConnection)
        txtTemperature = findViewById(R.id.txtTemperature)
        txtPressure = findViewById(R.id.txtPressure)
        txtPressureVoltage = findViewById(R.id.txtPressureVoltage)
        txtMotor = findViewById(R.id.txtMotor)

        relayButtons = listOf(
            findViewById(R.id.btnRelay1),
            findViewById(R.id.btnRelay2),
            findViewById(R.id.btnRelay3),
            findViewById(R.id.btnRelay4),
            findViewById(R.id.btnValve)
        )

        motorButtons = listOf(
            findViewById(R.id.btnLeft),
            findViewById(R.id.btnStop),
            findViewById(R.id.btnRight)
        )
        calibrateButton = findViewById(R.id.btnCalibrate)
    }

    private fun loadDeviceUrl() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        editDeviceUrl.setText(prefs.getString(PREF_DEVICE_URL, DEFAULT_DEVICE_URL))
    }

    private fun configureControls() {
        findViewById<Button>(R.id.btnWifiSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }

        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            val normalized = normalizeBaseUrl(editDeviceUrl.text.toString())
            editDeviceUrl.setText(normalized)
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_DEVICE_URL, normalized)
                .apply()
            showConnectionStatus("● Probando conexión con ESP32…", ConnectionState.PENDING)
            requestStatus(force = true)
        }

        relayButtons.forEachIndexed { index, button ->
            button.setOnClickListener {
                val desiredState = !relayStates[index]
                sendCommand("/api/relay?index=$index&state=${if (desiredState) "on" else "off"}")
            }
        }

        findViewById<Button>(R.id.btnLeft).setOnClickListener {
            sendCommand("/api/motor?action=left")
        }
        findViewById<Button>(R.id.btnRight).setOnClickListener {
            sendCommand("/api/motor?action=right")
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            sendCommand("/api/motor?action=stop")
        }

        calibrateButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Calibrar presión")
                .setMessage("Hazlo únicamente con la entrada neumática sin presión. ¿Calibrar cero ahora?")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Calibrar") { _, _ ->
                    sendCommand("/api/calibrate")
                }
                .show()
        }
    }

    private fun normalizeBaseUrl(value: String): String {
        var result = value.trim()
        if (result.isEmpty()) result = DEFAULT_DEVICE_URL
        if (!result.startsWith("http://") && !result.startsWith("https://")) {
            result = "http://$result"
        }
        return result.removeSuffix("/")
    }

    private fun baseUrl(): String = normalizeBaseUrl(editDeviceUrl.text.toString())

    private fun requestStatus(force: Boolean = false) {
        if (commandInFlight.get()) return
        if (!force && !requestingStatus.compareAndSet(false, true)) return
        if (force) requestingStatus.set(true)

        val endpoint = "${baseUrl()}/api/status"
        network.execute {
            try {
                val response = httpRequest(endpoint, "GET")
                val status = JSONObject(response)
                ui.post {
                    applyStatus(status)
                    showConnectionStatus("● ESP32 conectado · control local", ConnectionState.CONNECTED)
                }
            } catch (error: Exception) {
                ui.post {
                    showConnectionStatus("● Sin conexión · escanea el QR Wi‑Fi", ConnectionState.DISCONNECTED)
                }
            } finally {
                requestingStatus.set(false)
            }
        }
    }

    private fun sendCommand(path: String) {
        if (!commandInFlight.compareAndSet(false, true)) return

        setControlsEnabled(false)
        showConnectionStatus("● Enviando orden…", ConnectionState.PENDING)
        val endpoint = "${baseUrl()}$path"

        network.execute {
            try {
                val response = httpRequest(endpoint, "POST")
                val status = JSONObject(response)
                ui.post {
                    applyStatus(status)
                    showConnectionStatus("● Orden confirmada por ESP32", ConnectionState.CONNECTED)
                }
            } catch (error: Exception) {
                ui.post {
                    showConnectionStatus("● No se pudo enviar la orden", ConnectionState.DISCONNECTED)
                    Toast.makeText(this, "Verifica Wi-Fi y dirección del ESP32", Toast.LENGTH_SHORT).show()
                }
            } finally {
                commandInFlight.set(false)
                ui.post {
                    setControlsEnabled(true)
                    requestStatus(force = true)
                }
            }
        }
    }

    private fun httpRequest(urlText: String, method: String): String {
        val connection = (URL(urlText).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = method
            connection.connectTimeout = 900
            connection.readTimeout = 1100
            connection.useCaches = false
            connection.setRequestProperty("Cache-Control", "no-cache")

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code: $body")
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun applyStatus(status: JSONObject) {
        val temperatureOk = status.optBoolean("temperatureOk", false)
        val temperature = status.optDouble("temperatureC", Double.NaN)
        val pressure = status.optDouble("pressureBar", Double.NaN)
        val voltage = status.optDouble("pressureSensorV", Double.NaN)
        val motorActive = status.optBoolean("motorActive", false)
        val motorDirection = status.optString("motorDirection", "stopped")

        txtTemperature.text = if (temperatureOk && temperature.isFinite()) {
            String.format(Locale.US, "Temperatura: %.1f °C", temperature)
        } else {
            "Temperatura: sensor desconectado"
        }

        txtPressure.text = if (pressure.isFinite()) {
            String.format(Locale.US, "Presión: %.2f bar", pressure)
        } else {
            "Presión: -- bar"
        }

        txtPressureVoltage.text = if (voltage.isFinite()) {
            String.format(Locale.US, "Señal de presión: %.3f V", voltage)
        } else {
            "Señal de presión: -- V"
        }

        txtMotor.text = if (!motorActive) {
            "Motor: detenido"
        } else if (motorDirection == "right") {
            "Motor: girando derecha"
        } else {
            "Motor: girando izquierda"
        }
        txtMotor.setTextColor(
            getColor(if (motorActive) R.color.plancha_warning else R.color.plancha_success)
        )

        val relays: JSONArray = status.optJSONArray("relays") ?: JSONArray()
        for (index in 0 until 5) {
            relayStates[index] = relays.optBoolean(index, false)
            updateRelayButton(index)
        }
    }

    private fun updateRelayButton(index: Int) {
        val name = listOf("Bajar", "Subir", "Modo", "Parar", "Electroválvula")[index]
        val state = if (relayStates[index]) "ENCENDIDO" else "APAGADO"
        val button = relayButtons[index]
        button.text = "$name: $state"
        button.backgroundTintList = ColorStateList.valueOf(
            getColor(if (relayStates[index]) R.color.plancha_success else R.color.plancha_neutral)
        )
        button.setTextColor(
            getColor(if (relayStates[index]) R.color.white else R.color.plancha_text)
        )
    }

    private enum class ConnectionState { CONNECTED, PENDING, DISCONNECTED }

    private fun showConnectionStatus(message: String, state: ConnectionState) {
        txtConnection.text = message
        val color = when (state) {
            ConnectionState.CONNECTED -> R.color.plancha_success
            ConnectionState.PENDING -> R.color.plancha_warning
            ConnectionState.DISCONNECTED -> R.color.plancha_error
        }
        txtConnection.setTextColor(getColor(color))
        txtConnection.backgroundTintList = ColorStateList.valueOf(
            getColor(
                when (state) {
                    ConnectionState.CONNECTED -> R.color.plancha_neutral
                    ConnectionState.PENDING -> R.color.plancha_neutral
                    ConnectionState.DISCONNECTED -> R.color.plancha_neutral
                }
            )
        )
    }

    private fun setControlsEnabled(enabled: Boolean) {
        relayButtons.forEach { it.isEnabled = enabled }
        motorButtons.forEach { it.isEnabled = enabled }
        calibrateButton.isEnabled = enabled
    }
}
