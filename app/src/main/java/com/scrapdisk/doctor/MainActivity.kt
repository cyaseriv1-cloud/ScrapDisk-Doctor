package com.scrapdisk.doctor

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.scrapdisk.doctor.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var diskTester: DiskTester
    private lateinit var hapticHelper: HapticFeedbackHelper
    private lateinit var soundHelper: SoundFeedbackHelper
    private lateinit var usbHardwareHelper: UsbHardwareHelper
    private lateinit var testHistory: TestHistory

    private var isTesting = false
    private var testJob: Job? = null
    private var watchdogJob: Job? = null
    private var latestResult: SimpleTestResult? = null

    private val selectDriveLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
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
        soundHelper = SoundFeedbackHelper(this)
        usbHardwareHelper = UsbHardwareHelper(this)
        testHistory = TestHistory(this)

        cleanupPersistedPermissions()
        setupListeners()
        updateSoundToggleUi()
    }

    override fun onResume() {
        super.onResume()
        updateUsbHardwareBanner()
    }

    override fun onDestroy() {
        super.onDestroy()
        soundHelper.release()
    }

    private fun setupListeners() {
        // Botón principal: test o cancelar si está activo
        binding.btnSelectDrive.setOnClickListener {
            soundHelper.playClick()
            if (isTesting) {
                cancelActiveTest("Prueba cancelada por el usuario.")
            } else {
                selectDriveLauncher.launch(null)
            }
        }

        // Botón de expulsión / desconexión segura
        binding.btnEjectDrive.setOnClickListener {
            soundHelper.playClick()
            safeEjectDrive()
        }

        // Botón de historial
        binding.btnOpenHistory.setOnClickListener {
            soundHelper.playClick()
            showHistoryDialog()
        }

        // Botón de sonido (Mute / Unmute)
        binding.btnToggleSound.setOnClickListener {
            soundHelper.isSoundEnabled = !soundHelper.isSoundEnabled
            soundHelper.playClick()
            updateSoundToggleUi()
        }

        // Actualizar banner USB al tocar icono
        binding.btnRefreshUsb.setOnClickListener {
            soundHelper.playClick()
            updateUsbHardwareBanner()
        }

        // Abrir calculadora de ganga tras la prueba
        binding.btnOpenBargainDialog.setOnClickListener {
            soundHelper.playClick()
            showBargainDialog()
        }
    }

    private fun updateSoundToggleUi() {
        if (soundHelper.isSoundEnabled) {
            binding.btnToggleSound.setImageResource(R.drawable.ic_volume_up)
            binding.btnToggleSound.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.primary)
            )
        } else {
            binding.btnToggleSound.setImageResource(R.drawable.ic_volume_off)
            binding.btnToggleSound.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.text_muted)
            )
        }
    }

    private fun updateUsbHardwareBanner() {
        val hwInfo = usbHardwareHelper.getConnectedStorageInfo()
        binding.tvUsbHardwareInfo.text = hwInfo.summary
    }

    private fun executeDiskTest(uri: Uri) {
        isTesting = true

        binding.btnSelectDrive.text = "⏹️ CANCELAR PRUEBA"
        binding.btnSelectDrive.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.status_bad)
        )
        binding.btnSelectDrive.setIconResource(android.R.drawable.ic_menu_close_clear_cancel)

        binding.progressTest.visibility = View.VISIBLE
        binding.tvProgressStatus.visibility = View.VISIBLE
        binding.tvSpeedResult.visibility = View.GONE
        binding.tvLatencyResult.visibility = View.GONE
        binding.badgeDriveType.visibility = View.GONE
        binding.btnOpenBargainDialog.visibility = View.GONE

        binding.ivStatusIcon.setImageResource(R.drawable.ic_harddrive)
        binding.ivStatusIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary))
        binding.tvVerdictTitle.text = "DIAGNOSTICANDO..."
        binding.tvVerdictTitle.setTextColor(ContextCompat.getColor(this, R.color.text_title))
        binding.tvVerdictDetail.text = "Comprobando velocidad, latencia e integridad de sectores..."

        // Temporizador Guardián (Watchdog) de 5.5s
        watchdogJob?.cancel()
        watchdogJob = lifecycleScope.launch {
            delay(5500L)
            if (isTesting) {
                testJob?.cancel()
                val timeoutResult = SimpleTestResult(
                    success = false,
                    speedText = "0 MB/s (Congelado)",
                    verdictTitle = "🔴 PARTÍCIÓN DAÑADA / TRABADA",
                    verdictDetail = "Esta partición no respondió en 5 segundos. Los cabezales están atascados en sectores defectuosos.",
                    isGood = false,
                    driveType = "💽 Mecánico Trabado",
                    latencyText = "Latencia: > 5000 ms"
                )
                onTestFinished(timeoutResult)
            }
        }

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
            isWarning = true,
            driveType = "💽 Prueba Interrumpida",
            latencyText = "Latencia: Cancelada"
        )
        onTestFinished(cancelResult)
    }

    private fun onTestFinished(result: SimpleTestResult) {
        isTesting = false
        latestResult = result
        renderResult(result)
        testHistory.saveTest(result)

        // Forzar parada del motor del disco ANTES de liberar permisos
        forceSpinDownDisk()

        cleanupPersistedPermissions()
        System.gc()

        binding.btnSelectDrive.text = "⚡ PROBAR OTRA PARTICIÓN"
        binding.btnSelectDrive.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.primary)
        )
        binding.btnSelectDrive.setIconResource(R.drawable.ic_harddrive)
        binding.progressTest.visibility = View.GONE
        binding.tvProgressStatus.visibility = View.GONE

        // Mostrar botón de calculadora de ganga si la prueba fue exitosa
        if (result.success) {
            binding.btnOpenBargainDialog.visibility = View.VISIBLE
        }
    }

    private fun renderResult(result: SimpleTestResult) {
        binding.tvVerdictTitle.text = result.verdictTitle
        binding.tvVerdictDetail.text = result.verdictDetail

        binding.tvSpeedResult.visibility = View.VISIBLE
        binding.tvSpeedResult.text = result.speedText

        binding.tvLatencyResult.visibility = View.VISIBLE
        binding.tvLatencyResult.text = result.latencyText

        binding.badgeDriveType.visibility = View.VISIBLE
        binding.badgeDriveType.text = result.driveType

        when {
            result.isGood -> {
                val color = ContextCompat.getColor(this, R.color.status_good)
                binding.ivStatusIcon.setImageResource(R.drawable.ic_check_circle)
                binding.ivStatusIcon.imageTintList = ColorStateList.valueOf(color)
                binding.tvVerdictTitle.setTextColor(color)
                binding.tvSpeedResult.setTextColor(color)
                hapticHelper.vibrateSuccess()
                soundHelper.playSuccess()
            }
            result.isWarning -> {
                val color = ContextCompat.getColor(this, R.color.status_warning)
                binding.ivStatusIcon.setImageResource(R.drawable.ic_alert_triangle)
                binding.ivStatusIcon.imageTintList = ColorStateList.valueOf(color)
                binding.tvVerdictTitle.setTextColor(color)
                binding.tvSpeedResult.setTextColor(color)
                hapticHelper.vibrateWarning()
                soundHelper.playWarning()
            }
            else -> {
                val color = ContextCompat.getColor(this, R.color.status_bad)
                binding.ivStatusIcon.setImageResource(R.drawable.ic_x_circle)
                binding.ivStatusIcon.imageTintList = ColorStateList.valueOf(color)
                binding.tvVerdictTitle.setTextColor(color)
                binding.tvSpeedResult.setTextColor(color)
                hapticHelper.vibrateFailure()
                soundHelper.playFailure()
            }
        }
    }

    private fun showBargainDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_bargain, null)
        val etLabel = dialogView.findViewById<EditText>(R.id.etDiskLabel)
        val etPrice = dialogView.findViewById<EditText>(R.id.etDiskPrice)
        val toggleCapacity = dialogView.findViewById<MaterialButtonToggleGroup>(R.id.toggleCapacity)
        val tvPricePerGb = dialogView.findViewById<TextView>(R.id.tvPricePerGb)
        val tvVerdict = dialogView.findViewById<TextView>(R.id.tvBargainVerdict)
        val tvBadge = dialogView.findViewById<TextView>(R.id.tvBargainBadge)
        val btnSave = dialogView.findViewById<MaterialButton>(R.id.btnSaveBargain)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelBargain)

        val historyCount = testHistory.loadHistory().size
        etLabel.setText("Disco #$historyCount")

        var selectedCapGb = 1000.0

        val recalculate = {
            val priceStr = etPrice.text.toString().trim()
            val price = priceStr.toDoubleOrNull()

            if (price != null && price > 0) {
                val pricePerGb = price / selectedCapGb
                tvPricePerGb.text = String.format(Locale.US, "Costo: $%.3f por GB (Total $%.2f)", pricePerGb, price)

                val isSsd = latestResult?.driveType?.contains("SSD", ignoreCase = true) == true

                val (badgeText, badgeColor, verdictText) = when {
                    isSsd -> when {
                        pricePerGb <= 0.04 -> Triple("💎 GANGAZA", R.color.status_good, "¡Excelente precio para ser disco de estado sólido (SSD)!")
                        pricePerGb <= 0.08 -> Triple("⚖️ PRECIO JUSTO", R.color.accent, "Precio estándar de mercado para SSD de segunda mano.")
                        else -> Triple("💸 CARO", R.color.status_bad, "Está caro para comprar en desguace.")
                    }
                    else -> when {
                        pricePerGb <= 0.018 -> Triple("💎 GANGAZA", R.color.status_good, "¡Muy barato! Vale totalmente la pena si está sano.")
                        pricePerGb <= 0.035 -> Triple("⚖️ PRECIO JUSTO", R.color.accent, "Precio promedio aceptable para un HDD usado.")
                        else -> Triple("💸 CARO", R.color.status_bad, "Precio alto para ser un disco de desguace.")
                    }
                }

                tvBadge.text = badgeText
                tvBadge.setTextColor(ContextCompat.getColor(this, badgeColor))
                tvVerdict.text = verdictText
                tvVerdict.setTextColor(ContextCompat.getColor(this, badgeColor))
            } else {
                tvPricePerGb.text = "Ingresa el precio que te piden"
                tvVerdict.text = "Esperando número..."
                tvBadge.text = "-- / --"
            }
        }

        toggleCapacity.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                selectedCapGb = when (checkedId) {
                    R.id.btnCap250 -> 250.0
                    R.id.btnCap500 -> 500.0
                    R.id.btnCap2000 -> 2000.0
                    else -> 1000.0
                }
                recalculate()
            }
        }

        etPrice.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { recalculate() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        })

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val label = etLabel.text.toString().trim().ifBlank { "Disco #$historyCount" }
            val priceStr = etPrice.text.toString().trim()
            val bargainInfo = if (priceStr.isNotBlank()) {
                val capLabel = if (selectedCapGb >= 1000) "${(selectedCapGb / 1000).toInt()}TB" else "${selectedCapGb.toInt()}GB"
                "${tvBadge.text}: $$priceStr por $capLabel"
            } else {
                "Sin precio"
            }

            testHistory.updateLatestTestLabel(label, bargainInfo)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun safeEjectDrive() {
        cleanupPersistedPermissions()
        System.gc()

        if (forceSpinDownDisk()) {
            // La API Android 10+ pudo enviar la señal STOP UNIT
            AlertDialog.Builder(this)
                .setTitle("⏏️ Disco Expulsado")
                .setMessage(
                    "✅ Se ha enviado la señal de parada al disco.\n\n" +
                    "Los cabezales se han estacionado y el motor debería detenerse en unos segundos.\n\n" +
                    "Ahora puedes desconectar el cable USB de forma segura."
                )
                .setPositiveButton("Listo", null)
                .show()
        } else {
            // Fallback: instrucciones manuales (Android < 10 o sin volúmenes USB)
            AlertDialog.Builder(this)
                .setTitle("⏏️ Expulsar Disco")
                .setMessage(
                    "La app cerró todos los accesos al disco.\n\n" +
                    "Para detener el motor antes de desconectar:\n" +
                    "→ Abre Almacenamiento → toca 'Expulsar' junto al disco USB."
                )
                .setPositiveButton("Abrir Almacenamiento") { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
                    } catch (_: Exception) {
                        try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
                    }
                }
                .setNegativeButton("Listo", null)
                .show()
        }
    }

    /**
     * Envía señal SCSI STOP UNIT al disco USB usando la API de Android 10+.
     * Esto detiene el motor del disco mecánico y estaciona los cabezales.
     * @return true si se pudo enviar la señal a al menos un volumen USB
     */
    private fun forceSpinDownDisk(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false

        return try {
            val storageManager = getSystemService(Context.STORAGE_SERVICE) as StorageManager
            val usbVolumes = storageManager.storageVolumes.filter { volume ->
                volume.isRemovable && !volume.isPrimary
            }

            if (usbVolumes.isEmpty()) return false

            var ejectedAtLeastOne = false
            for (volume in usbVolumes) {
                try {
                    volume.eject(mainExecutor) { result ->
                        when (result) {
                            android.os.storage.StorageVolume.EJECT_SUCCESS ->
                                Toast.makeText(this, "🛑 Motor del disco detenido", Toast.LENGTH_SHORT).show()
                            android.os.storage.StorageVolume.EJECT_CONFLICT ->
                                Toast.makeText(this, "⚠️ El sistema aún usa el disco", Toast.LENGTH_SHORT).show()
                            else ->
                                Toast.makeText(this, "⏏️ Disco expulsado", Toast.LENGTH_SHORT).show()
                        }
                    }
                    ejectedAtLeastOne = true
                } catch (_: Exception) {}
            }
            ejectedAtLeastOne
        } catch (_: Exception) {
            false
        }
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

    private fun showReadyState(message: String) {
        binding.tvVerdictTitle.text = "LISTO PARA PROBAR"
        binding.tvVerdictTitle.setTextColor(ContextCompat.getColor(this, R.color.text_title))
        binding.tvVerdictDetail.text = message
        binding.tvSpeedResult.visibility = View.GONE
        binding.tvLatencyResult.visibility = View.GONE
        binding.badgeDriveType.visibility = View.GONE
        binding.btnOpenBargainDialog.visibility = View.GONE
        binding.ivStatusIcon.setImageResource(R.drawable.ic_harddrive)
        binding.ivStatusIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary))
    }

    private fun showHistoryDialog() {
        val historyList = testHistory.loadHistory()
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Historial de Discos Probados")

        if (historyList.isEmpty()) {
            builder.setMessage("Aún no has probado ningún disco en esta sesión.")
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
            val tvLabel = itemView.findViewById<TextView>(R.id.tvItemLabel)
            val tvBargain = itemView.findViewById<TextView>(R.id.tvItemBargain)
            val tvDriveType = itemView.findViewById<TextView>(R.id.tvItemDriveType)

            tvVerdict.text = item.verdict
            tvVerdict.setTextColor(
                if (item.isSuccess) ContextCompat.getColor(this, R.color.status_good)
                else ContextCompat.getColor(this, R.color.status_bad)
            )
            tvDate.text = item.dateStr
            tvDetails.text = item.details

            if (!item.label.isNullOrBlank()) {
                tvLabel.visibility = View.VISIBLE
                tvLabel.text = "🏷️ ${item.label}"
            } else {
                tvLabel.visibility = View.GONE
            }

            if (!item.bargainInfo.isNullOrBlank()) {
                tvBargain.visibility = View.VISIBLE
                tvBargain.text = item.bargainInfo
            } else {
                tvBargain.visibility = View.GONE
            }

            if (!item.driveType.isNullOrBlank()) {
                val latency = if (!item.latencyText.isNullOrBlank()) " | ${item.latencyText}" else ""
                tvDriveType.text = "${item.driveType}$latency"
                tvDriveType.visibility = View.VISIBLE
            } else {
                tvDriveType.visibility = View.GONE
            }

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
