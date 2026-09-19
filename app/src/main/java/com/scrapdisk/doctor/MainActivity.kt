package com.scrapdisk.doctor

import android.content.Intent
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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var diskTester: DiskTester
    private lateinit var hapticHelper: HapticFeedbackHelper
    private lateinit var testHistory: TestHistory

    private var isTesting = false

    private val selectDriveLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // Conceder permisos persistentes para evitar desconexiones de permisos durante el test
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}

            executeDiskTest(uri)
        } else {
            showReadyState("No se seleccionó ninguna carpeta o unidad.")
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
        // Botón principal de prueba
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
        binding.progressTest.visibility = View.VISIBLE
        binding.tvProgressStatus.visibility = View.VISIBLE
        binding.tvSpeedResult.visibility = View.GONE

        binding.ivStatusIcon.setImageResource(R.drawable.ic_harddrive)
        binding.ivStatusIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary))
        binding.tvVerdictTitle.text = "DIAGNOSTICANDO..."
        binding.tvVerdictTitle.setTextColor(ContextCompat.getColor(this, R.color.text_title))
        binding.tvVerdictDetail.text = "Comprobando velocidad e integridad de sectores..."

        lifecycleScope.launch {
            val result = diskTester.runFastTest(uri) { statusText ->
                runOnUiThread {
                    binding.tvProgressStatus.text = statusText
                }
            }

            renderResult(result)
            testHistory.saveTest(result)

            isTesting = false
            binding.btnSelectDrive.isEnabled = true
            binding.progressTest.visibility = View.GONE
            binding.tvProgressStatus.visibility = View.GONE
            binding.btnSelectDrive.text = "⚡ PROBAR OTRO DISCO"
        }
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
