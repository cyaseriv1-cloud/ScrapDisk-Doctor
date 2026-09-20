package com.scrapdisk.doctor

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.scrapdisk.doctor.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var diskTester: DiskTester
    private lateinit var hapticHelper: HapticFeedbackHelper
    private lateinit var testHistory: TestHistory

    private var isTesting = false
    private var testJob: Job? = null
    private var watchdogJob: Job? = null

    private val selectDriveLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // NOTA CRÍTICA: NO llamamos a takePersistableUriPermission()
            // Tomar permisos persistentes hace que el servicio MediaScanner de Android
            // intente indexar todo el disco de 1TB en segundo plano buscando fotos y videos,
            // dejando los cabezales del disco rascando al 100% de esfuerzo.
            executeDiskTest(uri)
        } else {
            showReadyState("No se seleccionó ninguna partición o unidad.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        diskTester = DiskTester(this)
        hapticHelper = HapticFeedbackHelper(this)
        testHistory = TestHistory(this)

        // Limpiar permisos persistentes residuales de versiones anteriores que puedan tener al indexador despierto
        cleanupPersistedPermissions()

        setupListeners()
    }

    private fun setupListeners() {
        // Botón principal: si está testeando, funciona como botón de PARAR/CANCELAR
        binding.btnSelectDrive.setOnClickListener {
            if (isTesting) {
                cancelActiveTest("Prueba cancelada por el usuario.")
            } else {
                selectDriveLauncher.launch(null)
            }
        }

        // Botón de expulsión / desconexión segura
        binding.btnEjectDrive.setOnClickListener {
            safeEjectDrive()
        }

        // Botón de historial
        binding.btnOpenHistory.setOnClickListener {
            showHistoryDialog()
        }
    }

    private fun executeDiskTest(uri: Uri) {
        isTesting = true

        // Transformar botón a botón de CANCELAR rojo
        binding.btnSelectDrive.text = "⏹️ CANCELAR PRUEBA"
        binding.btnSelectDrive.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.status_bad)
        )
        binding.btnSelectDrive.setIconResource(android.R.drawable.ic_menu_close_clear_cancel)

        binding.progressTest.visibility = View.VISIBLE
        binding.tvProgressStatus.visibility = View.VISIBLE
        binding.tvSpeedResult.visibility = View.GONE

        binding.ivStatusIcon.setImageResource(R.drawable.ic_harddrive)
        binding.ivStatusIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary))
        binding.tvVerdictTitle.text = "DIAGNOSTICANDO..."
        binding.tvVerdictTitle.setTextColor(ContextCompat.getColor(this, R.color.text_title))
        binding.tvVerdictDetail.text = "Comprobando velocidad e integridad de sectores..."

        // 1. Temporizador Guardián (Watchdog) de 5.5s
        watchdogJob?.cancel()
        watchdogJob = lifecycleScope.launch {
            delay(5500L)
            if (isTesting) {
                testJob?.cancel()
                val timeoutResult = SimpleTestResult(
                    success = false,
                    speedText = "0 MB/s (Congelado)",
                    verdictTitle = "🔴 PARTÍCIÓN DAÑADA / TRABADA",
                    verdictDetail = "Esta partición no respondió en 5 segundos. Los cabezales están atascados en sectores defectuosos de esta zona del disco.",
                    isGood = false
                )
                onTestFinished(timeoutResult)
            }
        }

        // 2. Ejecutar prueba
        testJob?.cancel()
        testJob = lifecycleScope.launch {
            val result = diskTester.runFastTest(uri) { statusText ->
                runOnUiThread {
                    binding.tvProgressStatus.text = statusText
                }
            }

            watchdogJob?.cancel()
            onTestFinished(result)
        }
    }

    private fun cancelActiveTest(reason: String) {
        testJob?.cancel()
        watchdogJob?.cancel()

        val cancelResult = SimpleTestResult(
            success = false,
            speedText = "Cancelado",
            verdictTitle = "⏹️ PRUEBA DETENIDA",
            verdictDetail = "$reason Si se quedó colgado, esta partición tiene sectores que traban la lectura.",
            isGood = false,
            isWarning = true
        )
        onTestFinished(cancelResult)
    }

    private fun onTestFinished(result: SimpleTestResult) {
        isTesting = false
        renderResult(result)
        testHistory.saveTest(result)

        // Liberar referencias y forzar recolección de basura para cerrar descriptores de archivos de inmediato
        cleanupPersistedPermissions()
        System.gc()

        // Restaurar botón principal a modo normal
        binding.btnSelectDrive.text = "⚡ PROBAR OTRA PARTICIÓN"
        binding.btnSelectDrive.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.primary)
        )
        binding.btnSelectDrive.setIconResource(R.drawable.ic_harddrive)
        binding.progressTest.visibility = View.GONE
        binding.tvProgressStatus.visibility = View.GONE
    }

    private fun safeEjectDrive() {
        cleanupPersistedPermissions()
        System.gc()

        AlertDialog.Builder(this)
            .setTitle("⏏️ Expulsar Disco")
            .setMessage("La aplicación ha cerrado todos los accesos al disco y liberado la memoria.\n\n" +
                    "• Recuerda que los motores del disco mecánico siguen girando mientras el cable USB le suministre energía eléctrica.\n\n" +
                    "• Si quieres que el disco detenga sus platos antes de desconectarlo, puedes pulsar 'Abrir Almacenamiento' y tocar en 'Expulsar'.")
            .setPositiveButton("Abrir Almacenamiento") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    } catch (_: Exception) {}
                }
            }
            .setNegativeButton("Listo / Entendido", null)
            .show()
    }

    private fun cleanupPersistedPermissions() {
        try {
            for (perm in contentResolver.persistedUriPermissions) {
                contentResolver.releasePersistableUriPermission(
                    perm.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        } catch (_: Exception) {}
    }

    private fun renderResult(result: SimpleTestResult) {
        binding.tvVerdictTitle.text = result.verdictTitle
        binding.tvVerdictDetail.text = result.verdictDetail

        binding.tvSpeedResult.visibility = View.VISIBLE
        binding.tvSpeedResult.text = result.speedText

        when {
            result.isGood -> {
                val color = ContextCompat.getColor(this, R.color.status_good)
                binding.ivStatusIcon.setImageResource(R.drawable.ic_check_circle)
                binding.ivStatusIcon.imageTintList = ColorStateList.valueOf(color)
                binding.tvVerdictTitle.setTextColor(color)
                binding.tvSpeedResult.setTextColor(color)
                hapticHelper.vibrateSuccess()
            }
            result.isWarning -> {
                val color = ContextCompat.getColor(this, R.color.status_warning)
                binding.ivStatusIcon.setImageResource(R.drawable.ic_alert_triangle)
                binding.ivStatusIcon.imageTintList = ColorStateList.valueOf(color)
                binding.tvVerdictTitle.setTextColor(color)
                binding.tvSpeedResult.setTextColor(color)
                hapticHelper.vibrateWarning()
            }
            else -> {
                val color = ContextCompat.getColor(this, R.color.status_bad)
                binding.ivStatusIcon.setImageResource(R.drawable.ic_x_circle)
                binding.ivStatusIcon.imageTintList = ColorStateList.valueOf(color)
                binding.tvVerdictTitle.setTextColor(color)
                binding.tvSpeedResult.setTextColor(color)
                hapticHelper.vibrateFailure()
            }
        }
    }

    private fun showReadyState(message: String) {
        binding.tvVerdictTitle.text = "LISTO PARA PROBAR"
        binding.tvVerdictTitle.setTextColor(ContextCompat.getColor(this, R.color.text_title))
        binding.tvVerdictDetail.text = message
        binding.tvSpeedResult.visibility = View.GONE
        binding.ivStatusIcon.setImageResource(R.drawable.ic_harddrive)
        binding.ivStatusIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary))
    }

    private fun showHistoryDialog() {
        val historyList = testHistory.loadHistory()
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Historial de Particiones Probadas")

        if (historyList.isEmpty()) {
            builder.setMessage("Aún no has probado ninguna partición en esta sesión.")
            builder.setPositiveButton("Aceptar", null)
            builder.show()
            return
        }

        val inflater = LayoutInflater.from(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }

        for (item in historyList) {
            val itemView = inflater.inflate(R.layout.item_history, container, false)
            val tvVerdict = itemView.findViewById<TextView>(R.id.tvItemVerdict)
            val tvDate = itemView.findViewById<TextView>(R.id.tvItemDate)
            val tvDetails = itemView.findViewById<TextView>(R.id.tvItemDetails)

            tvVerdict.text = item.verdict
            tvVerdict.setTextColor(
                if (item.isSuccess) ContextCompat.getColor(this, R.color.status_good)
                else ContextCompat.getColor(this, R.color.status_bad)
            )
            tvDate.text = item.dateStr
            tvDetails.text = item.details

            container.addView(itemView)
        }

        builder.setView(container)
        builder.setPositiveButton("Cerrar", null)
        builder.setNegativeButton("Borrar Historial") { _, _ ->
            testHistory.clearHistory()
        }
        builder.show()
    }
}
