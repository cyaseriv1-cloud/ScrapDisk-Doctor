package com.scrapdisk.doctor

import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
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
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var diskTester: DiskTester
    private lateinit var hapticHelper: HapticFeedbackHelper
    private lateinit var testHistory: TestHistory

    private var selectedSizeMB = 30
    private var isTesting = false

    private val selectDriveLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            executeDiskTest(uri)
        } else {
            showReadyState("No se seleccionó ninguna unidad.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        diskTester = DiskTester(this)
        hapticHelper = HapticFeedbackHelper(this)
        testHistory = TestHistory(this)

        setupListeners()
    }

    private fun setupListeners() {
        // Selector de tamaño de prueba
        binding.toggleSizeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                selectedSizeMB = when (checkedId) {
                    R.id.btnSize10 -> 10
                    R.id.btnSize50 -> 50
                    else -> 30
                }
            }
        }

        // Botón principal
        binding.btnSelectDrive.setOnClickListener {
            if (!isTesting) {
                selectDriveLauncher.launch(null)
            }
        }

        // Botón de historial
        binding.btnOpenHistory.setOnClickListener {
            showHistoryDialog()
        }
    }

    private fun executeDiskTest(uri: Uri) {
        isTesting = true
        binding.btnSelectDrive.isEnabled = false
        binding.toggleSizeGroup.isEnabled = false
        binding.progressTest.visibility = View.VISIBLE
        binding.tvProgressStatus.visibility = View.VISIBLE
        binding.progressTest.isIndeterminate = false
        binding.progressTest.progress = 0

        binding.ivStatusIcon.setImageResource(R.drawable.ic_harddrive)
        binding.ivStatusIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary))
        binding.tvVerdictTitle.text = "TESTEANDO DISCO..."
        binding.tvVerdictTitle.setTextColor(ContextCompat.getColor(this, R.color.text_title))
        binding.tvVerdictDetail.text = "Por favor, mantén el cable OTG conectado firmemente."

        binding.tvWriteSpeed.text = "-- MB/s"
        binding.tvReadSpeed.text = "-- MB/s"
        binding.tvIntegrityStatus.text = "Comprobando..."
        binding.tvIntegrityBadge.text = "EN PROCESO"
        binding.tvIntegrityBadge.setTextColor(ContextCompat.getColor(this, R.color.accent))

        lifecycleScope.launch {
            val result = diskTester.runQuickTest(uri, selectedSizeMB) { stage, percent ->
                runOnUiThread {
                    binding.tvProgressStatus.text = stage
                    binding.progressTest.progress = percent
                }
            }

            renderResult(result)
            testHistory.saveTest(result, selectedSizeMB)

            isTesting = false
            binding.btnSelectDrive.isEnabled = true
            binding.toggleSizeGroup.isEnabled = true
            binding.progressTest.visibility = View.GONE
            binding.tvProgressStatus.visibility = View.GONE
            binding.btnSelectDrive.text = getString(R.string.btn_retest)
        }
    }

    private fun renderResult(result: TestResult) {
        binding.tvVerdictTitle.text = result.verdictTitle
        binding.tvVerdictDetail.text = result.verdictDetail

        if (result.success) {
            binding.tvWriteSpeed.text = String.format(Locale.US, "%.1f MB/s", result.writeSpeedMBs)
            binding.tvReadSpeed.text = String.format(Locale.US, "%.1f MB/s", result.readSpeedMBs)
            binding.tvIntegrityStatus.text = "Sectores íntegros (MD5 verificado)"
            binding.tvIntegrityBadge.text = "100% OK"
            binding.tvIntegrityBadge.setTextColor(ContextCompat.getColor(this, R.color.status_good))
        } else {
            binding.tvWriteSpeed.text = if (result.writeSpeedMBs > 0) String.format(Locale.US, "%.1f MB/s", result.writeSpeedMBs) else "0.0 MB/s"
            binding.tvReadSpeed.text = if (result.readSpeedMBs > 0) String.format(Locale.US, "%.1f MB/s", result.readSpeedMBs) else "0.0 MB/s"
            binding.tvIntegrityStatus.text = result.errorMessage ?: "Fallo crítico en sectores"
            binding.tvIntegrityBadge.text = "FALLÓ"
            binding.tvIntegrityBadge.setTextColor(ContextCompat.getColor(this, R.color.status_bad))
        }

        when (result.verdictType) {
            VerdictType.EXCELLENT -> {
                val color = ContextCompat.getColor(this, R.color.status_good)
                binding.ivStatusIcon.setImageResource(R.drawable.ic_check_circle)
                binding.ivStatusIcon.imageTintList = ColorStateList.valueOf(color)
                binding.tvVerdictTitle.setTextColor(color)
                hapticHelper.vibrateSuccess()
            }
            VerdictType.WARNING -> {
                val color = ContextCompat.getColor(this, R.color.status_warning)
                binding.ivStatusIcon.setImageResource(R.drawable.ic_alert_triangle)
                binding.ivStatusIcon.imageTintList = ColorStateList.valueOf(color)
                binding.tvVerdictTitle.setTextColor(color)
                hapticHelper.vibrateWarning()
            }
            VerdictType.CRITICAL -> {
                val color = ContextCompat.getColor(this, R.color.status_bad)
                binding.ivStatusIcon.setImageResource(R.drawable.ic_x_circle)
                binding.ivStatusIcon.imageTintList = ColorStateList.valueOf(color)
                binding.tvVerdictTitle.setTextColor(color)
                hapticHelper.vibrateFailure()
            }
        }
    }

    private fun showReadyState(message: String) {
        binding.tvVerdictTitle.text = "LISTO PARA PROBAR"
        binding.tvVerdictTitle.setTextColor(ContextCompat.getColor(this, R.color.text_title))
        binding.tvVerdictDetail.text = message
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
