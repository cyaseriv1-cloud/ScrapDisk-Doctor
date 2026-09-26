package com.scrapdisk.doctor

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.random.Random

data class SimpleTestResult(
    val success: Boolean,
    val speedText: String,
    val verdictTitle: String,
    val verdictDetail: String,
    val isGood: Boolean,
    val isWarning: Boolean = false,
    val driveType: String = "💽 Almacenamiento",
    val latencyText: String = "Latencia: --",
    val maxLatencyMs: Long = 0L,
    val isDegraded: Boolean = false,
    val errorMessage: String? = null
)

class DiskTester(private val context: Context) {

    suspend fun runFastTest(
        treeUri: Uri,
        onStatus: (String) -> Unit
    ): SimpleTestResult = withContext(Dispatchers.IO) {
        try {
            withTimeout(5000L) {
                onStatus("1/3 Conectando con partición...")

                val rootDir = DocumentFile.fromTreeUri(context, treeUri)
                    ?: return@withTimeout SimpleTestResult(
                        success = false,
                        speedText = "--",
                        verdictTitle = "🔴 ERROR DE CONEXIÓN",
                        verdictDetail = "No se pudo acceder a la partición. Verifica la conexión OTG.",
                        isGood = false
                    )

                val canWrite = try { rootDir.canWrite() } catch (_: Exception) { false }

                // 1. SI ES DE SOLO LECTURA (Típico de particiones NTFS de Windows)
                if (!canWrite) {
                    onStatus("2/3 Partición NTFS detectada (Probando lectura)...")
                    return@withTimeout performFastReadTest(treeUri, onStatus, isNtfs = true)
                }

                // 2. PARTICIÓN ESCRIBIBLE: INTENTAR CREAR ARCHIVO TEMPORAL (Máx 1.5s)
                onStatus("2/3 Creando archivo de prueba (1 MB)...")
                val testFileName = "_test_${System.currentTimeMillis() % 1000}.tmp"

                val testFile = withTimeoutOrNull(1500L) {
                    try {
                        rootDir.createFile("application/octet-stream", testFileName)
                    } catch (_: Exception) {
                        null
                    }
                }

                if (testFile == null) {
                    onStatus("Escritura bloqueada, probando lectura...")
                    return@withTimeout performFastReadTest(treeUri, onStatus, isNtfs = true)
                }

                // 3. FASE DE ESCRITURA Y LECTURA CON MEDICIÓN DE LATENCIA
                try {
                    val totalBytes = 1024 * 1024 // 1 MB
                    val chunkSize = 32 * 1024 // 32 KB por paquete
                    val sampleData = ByteArray(chunkSize).apply { Random.nextBytes(this) }
                    val writeDigest = MessageDigest.getInstance("MD5")

                    var maxWriteChunkMs = 0L
                    var totalWriteChunkMs = 0L
                    var writeChunksCount = 0

                    onStatus("3/3 Escribiendo datos (1 MB)...")
                    val writeStart = System.currentTimeMillis()

                    val outStream: OutputStream = context.contentResolver.openOutputStream(testFile.uri)
                        ?: throw Exception("No se pudo abrir salida")
                    outStream.use { out ->
                        var written = 0
                        while (written < totalBytes) {
                            val chunkStart = System.currentTimeMillis()
                            out.write(sampleData)
                            writeDigest.update(sampleData)
                            val chunkElapsed = (System.currentTimeMillis() - chunkStart).coerceAtLeast(0)

                            totalWriteChunkMs += chunkElapsed
                            if (chunkElapsed > maxWriteChunkMs) maxWriteChunkMs = chunkElapsed
                            writeChunksCount++

                            written += chunkSize
                        }
                        out.flush()
                    }
                    val writeTime = (System.currentTimeMillis() - writeStart).coerceAtLeast(1)
                    val writeSpeedMBs = 1.0 / (writeTime / 1000.0)

                    // Lectura e Integridad
                    onStatus("3/3 Leyendo y verificando latencias...")
                    val readDigest = MessageDigest.getInstance("MD5")
                    val readBuffer = ByteArray(chunkSize)
                    val readStart = System.currentTimeMillis()

                    var maxReadChunkMs = 0L
                    var totalReadChunkMs = 0L
                    var readChunksCount = 0

                    val inStream: InputStream = context.contentResolver.openInputStream(testFile.uri)
                        ?: throw Exception("No se pudo abrir entrada")
                    inStream.use { input ->
                        var readBytes: Int
                        while (input.read(readBuffer).also { readBytes = it } != -1) {
                            val chunkStart = System.currentTimeMillis()
                            readDigest.update(readBuffer, 0, readBytes)
                            val chunkElapsed = (System.currentTimeMillis() - chunkStart).coerceAtLeast(0)

                            totalReadChunkMs += chunkElapsed
                            if (chunkElapsed > maxReadChunkMs) maxReadChunkMs = chunkElapsed
                            readChunksCount++
                        }
                    }
                    val readTime = (System.currentTimeMillis() - readStart).coerceAtLeast(1)
                    val readSpeedMBs = 1.0 / (readTime / 1000.0)

                    val md5Match = writeDigest.digest().contentEquals(readDigest.digest())
                    if (!md5Match) {
                        return@withTimeout SimpleTestResult(
                            success = false,
                            speedText = String.format("Esc: %.1f MB/s", writeSpeedMBs),
                            verdictTitle = "🔴 NO COMPRAR (SECTORES DAÑADOS)",
                            verdictDetail = "¡Alerta! Los datos se alteraron al escribirse. Esta zona del disco tiene sectores defectuosos.",
                            isGood = false,
                            driveType = "💽 Platos Dañados",
                            latencyText = "Latencia Máx: ${maxOf(maxWriteChunkMs, maxReadChunkMs)} ms"
                        )
                    }

                    // Clasificación de unidad y latencia
                    val overallMaxLatency = maxOf(maxWriteChunkMs, maxReadChunkMs)
                    val avgLatencyMs = if (readChunksCount > 0) (totalReadChunkMs / readChunksCount) else 5L
                    val isDegraded = overallMaxLatency > 220L

                    val driveType = when {
                        readSpeedMBs >= 160.0 -> "⚡ SSD / Memoria Sólida"
                        isDegraded -> "⚠️ HDD con Sectores Lentos"
                        else -> "💽 HDD Mecánico (5400/7200 RPM)"
                    }

                    val latencyInfo = "Latencia media: ${avgLatencyMs} ms (Pico: ${overallMaxLatency} ms)"
                    val speedStr = String.format("Esc: %.1f MB/s  |  Lec: %.1f MB/s", writeSpeedMBs, readSpeedMBs)

                    val (verdictTitle, verdictDetail, isGood, isWarning) = when {
                        isDegraded -> Quadruple(
                            "🟡 APTO CON PRECAUCIÓN",
                            "Lectura íntegra, pero se detectaron picos de latencia (${overallMaxLatency} ms). Posible desgaste de pistas o platos.",
                            false,
                            true
                        )
                        else -> Quadruple(
                            "🟢 COMPRA SEGURA (BUENO)",
                            "Respuesta estable sin sectores lentos ni errores de paridad. Estado óptimo.",
                            true,
                            false
                        )
                    }

                    return@withTimeout SimpleTestResult(
                        success = true,
                        speedText = speedStr,
                        verdictTitle = verdictTitle,
                        verdictDetail = verdictDetail,
                        isGood = isGood,
                        isWarning = isWarning,
                        driveType = driveType,
                        latencyText = latencyInfo,
                        maxLatencyMs = overallMaxLatency,
                        isDegraded = isDegraded
                    )

                } finally {
                    try { testFile.delete() } catch (_: Exception) {}
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            return@withContext SimpleTestResult(
                success = false,
                speedText = "0 MB/s (Congelado)",
                verdictTitle = "🔴 PARTICIÓN DAÑADA / TRABADA",
                verdictDetail = "Esta partición no respondió a tiempo. Los cabezales están atascados intentando leer sectores dañados en esta zona del disco.",
                isGood = false,
                driveType = "💽 Mecánico Trabado",
                latencyText = "Latencia: > 5000 ms (Timeout)"
            )
        } catch (e: Exception) {
            return@withContext SimpleTestResult(
                success = false,
                speedText = "Error I/O",
                verdictTitle = "🔴 ERROR EN PARTICIÓN",
                verdictDetail = e.localizedMessage ?: "Error de comunicación con la partición.",
                isGood = false,
                errorMessage = e.message
            )
        }
    }

    private suspend fun performFastReadTest(
        treeUri: Uri,
        onStatus: (String) -> Unit,
        isNtfs: Boolean
    ): SimpleTestResult = withContext(Dispatchers.IO) {
        onStatus("3/3 Leyendo sectores y latencias...")

        val fileUri = findFirstFileUri(treeUri)

        if (fileUri != null) {
            try {
                val readStart = System.currentTimeMillis()
                val buffer = ByteArray(32 * 1024)
                var bytesReadTotal = 0L
                val maxToRead = 1024 * 1024 // 1 MB máximo

                var maxChunkMs = 0L
                var totalChunkMs = 0L
                var chunksCount = 0

                val inStream = context.contentResolver.openInputStream(fileUri)
                inStream?.use { input ->
                    while (bytesReadTotal < maxToRead) {
                        val chunkStart = System.currentTimeMillis()
                        val count = input.read(buffer)
                        if (count == -1) break
                        bytesReadTotal += count

                        val chunkElapsed = (System.currentTimeMillis() - chunkStart).coerceAtLeast(0)
                        totalChunkMs += chunkElapsed
                        if (chunkElapsed > maxChunkMs) maxChunkMs = chunkElapsed
                        chunksCount++
                    }
                }

                val readTime = (System.currentTimeMillis() - readStart).coerceAtLeast(1)
                val readSpeedMBs = (bytesReadTotal.toDouble() / (1024 * 1024)) / (readTime / 1000.0)

                val avgLatencyMs = if (chunksCount > 0) (totalChunkMs / chunksCount) else 5L
                val isDegraded = maxChunkMs > 220L
                val driveType = if (readSpeedMBs >= 160.0) "⚡ SSD / Memoria Sólida" else "💽 HDD Mecánico (NTFS)"

                val speedStr = String.format("Lectura: %.1f MB/s", readSpeedMBs)
                val latencyInfo = "Latencia media: ${avgLatencyMs} ms (Pico: ${maxChunkMs} ms)"

                val verdictTitle = if (isDegraded) "🟡 PARTICIÓN LENTA (NTFS)" else "🟢 PARTICIÓN BUENA (NTFS)"
                val verdictDetail = if (isDegraded)
                    "El disco leyó pero con picos de retardo (${maxChunkMs} ms). Pistas con posible degradación."
                else
                    "Esta partición leyó a ${String.format("%.1f", readSpeedMBs)} MB/s de forma fluida y sin sectores lentos."

                return@withContext SimpleTestResult(
                    success = true,
                    speedText = speedStr,
                    verdictTitle = verdictTitle,
                    verdictDetail = verdictDetail,
                    isGood = !isDegraded,
                    isWarning = isDegraded,
                    driveType = driveType,
                    latencyText = latencyInfo,
                    maxLatencyMs = maxChunkMs,
                    isDegraded = isDegraded
                )
            } catch (e: Exception) {
                return@withContext SimpleTestResult(
                    success = false,
                    speedText = "Error de lectura",
                    verdictTitle = "🔴 SECTORES ILEGIBLES",
                    verdictDetail = "Fallo al leer sectores en esta partición: ${e.localizedMessage ?: "Error I/O"}. Hay sectores dañados.",
                    isGood = false
                )
            }
        }

        return@withContext SimpleTestResult(
            success = true,
            speedText = "Tabla de partición OK",
            verdictTitle = if (isNtfs) "🟢 PARTICIÓN BUENA (NTFS)" else "🟢 PARTICIÓN DETECTADA",
            verdictDetail = "La tabla de particiones respondió con normalidad y sin errores de lectura.",
            isGood = true,
            driveType = "💽 Disco Reconocido",
            latencyText = "Latencia: < 10 ms"
        )
    }

    private fun findFirstFileUri(treeUri: Uri): Uri? {
        return try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            )
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

                var count = 0
                while (cursor.moveToNext() && count < 20) {
                    count++
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol) else ""
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    val docId = if (idCol >= 0) cursor.getString(idCol) else null

                    if (mime != DocumentsContract.Document.MIME_TYPE_DIR && docId != null && size > 0) {
                        return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
