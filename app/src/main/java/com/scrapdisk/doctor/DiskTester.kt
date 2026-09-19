package com.scrapdisk.doctor

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.random.Random

data class TestResult(
    val success: Boolean,
    val writeSpeedMBs: Double = 0.0,
    val readSpeedMBs: Double = 0.0,
    val integrityOk: Boolean = false,
    val verdictTitle: String,
    val verdictDetail: String,
    val verdictType: VerdictType,
    val errorMessage: String? = null
)

enum class VerdictType {
    EXCELLENT,
    WARNING,
    CRITICAL
}

class DiskTester(private val context: Context) {

    suspend fun runQuickTest(
        treeUri: Uri,
        testSizeMB: Int = 30,
        onProgress: (stage: String, percent: Int) -> Unit
    ): TestResult = withContext(Dispatchers.IO) {
        val rootDir = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext TestResult(
                success = false,
                verdictTitle = "ERROR DE ACCESO",
                verdictDetail = "No se pudo acceder a la unidad USB seleccionada.",
                verdictType = VerdictType.CRITICAL,
                errorMessage = "DocumentFile retornó nulo"
            )

        val fileName = "_scrap_doctor_test_${System.currentTimeMillis()}.tmp"
        onProgress("Creando archivo de prueba en disco...", 5)

        val testFile = rootDir.createFile("application/octet-stream", fileName)
            ?: return@withContext TestResult(
                success = false,
                verdictTitle = "NO SE PUEDE ESCRIBIR",
                verdictDetail = "El disco no permite crear archivos. Posible disco protegido contra escritura o tabla de archivos dañada.",
                verdictType = VerdictType.CRITICAL,
                errorMessage = "Fallo al crear archivo temporal"
            )

        try {
            // 1. Preparar búfer de 1 MB con bytes aleatorios fijos para la prueba
            val bufferSize = 1024 * 1024 // 1 MB
            val chunk = ByteArray(bufferSize).apply { Random.nextBytes(this) }
            val originalDigest = MessageDigest.getInstance("MD5")

            // 2. FASE DE ESCRITURA
            onProgress("Escribiendo $testSizeMB MB de datos...", 15)
            val writeStartTime = System.currentTimeMillis()
            val outStream: OutputStream = context.contentResolver.openOutputStream(testFile.uri)
                ?: throw Exception("No se pudo abrir el stream de salida hacia el disco")

            outStream.use { out ->
                for (i in 0 until testSizeMB) {
                    out.write(chunk)
                    originalDigest.update(chunk)
                    val percent = 15 + ((i + 1) * 35 / testSizeMB)
                    onProgress("Escribiendo bloque ${i + 1}/$testSizeMB...", percent)
                }
                out.flush()
            }
            val writeTimeMs = (System.currentTimeMillis() - writeStartTime).coerceAtLeast(1)
            val writeSpeed = (testSizeMB.toDouble() / (writeTimeMs / 1000.0))

            // 3. FASE DE LECTURA E INTEGRIDAD
            onProgress("Leyendo y verificando integridad...", 55)
            val readDigest = MessageDigest.getInstance("MD5")
            val readBuffer = ByteArray(bufferSize)
            val readStartTime = System.currentTimeMillis()

            val inStream: InputStream = context.contentResolver.openInputStream(testFile.uri)
                ?: throw Exception("No se pudo abrir el stream de lectura desde el disco")

            var totalBytesRead = 0L
            inStream.use { input ->
                var bytesRead: Int
                var readCount = 0
                while (input.read(readBuffer).also { bytesRead = it } != -1) {
                    readDigest.update(readBuffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    readCount++
                    val percent = 55 + (readCount.coerceAtMost(testSizeMB) * 35 / testSizeMB)
                    onProgress("Verificando datos leídos ($readCount/$testSizeMB MB)...", percent)
                }
            }
            val readTimeMs = (System.currentTimeMillis() - readStartTime).coerceAtLeast(1)
            val readSpeed = ((totalBytesRead.toDouble() / (1024 * 1024)) / (readTimeMs / 1000.0))

            // 4. VERIFICACIÓN DE INTEGRIDAD
            onProgress("Comparando sumas de comprobación MD5...", 95)
            val expectedHash = originalDigest.digest()
            val actualHash = readDigest.digest()
            val integrityOk = expectedHash.contentEquals(actualHash)

            if (!integrityOk) {
                return@withContext TestResult(
                    success = false,
                    writeSpeedMBs = writeSpeed,
                    readSpeedMBs = readSpeed,
                    integrityOk = false,
                    verdictTitle = "🔴 NO COMPRAR (DATOS CORRUPTOS)",
                    verdictDetail = "¡Alerta! Los datos leídos no coinciden con los escritos. El disco tiene sectores con corrupción activa.",
                    verdictType = VerdictType.CRITICAL,
                    errorMessage = "Fallo de suma de comprobación MD5"
                )
            }

            // 5. EVALUACIÓN DE VELOCIDAD Y VEREDICTO FINAL
            val (title, detail, type) = when {
                writeSpeed >= 30.0 -> Triple(
                    "🟢 COMPRA SEGURA (Excelente)",
                    "El disco lee y escribe con total fluidez. Integridad de sectores al 100%.",
                    VerdictType.EXCELLENT
                )
                writeSpeed >= 12.0 -> Triple(
                    "🟡 APTO CON PRECAUCIÓN (Velocidad Media)",
                    "El disco funciona y no corrompe datos, pero la velocidad es modesta (propia de USB 2.0 o disco con desgaste).",
                    VerdictType.WARNING
                )
                else -> Triple(
                    "🔴 NO COMPRAR (Demasiado Lento)",
                    "El disco presenta latencias críticas (< 12 MB/s). Podría tener sectores con retardo mecánico o platos desgastados.",
                    VerdictType.CRITICAL
                )
            }

            onProgress("Completado", 100)
            return@withContext TestResult(
                success = true,
                writeSpeedMBs = writeSpeed,
                readSpeedMBs = readSpeed,
                integrityOk = true,
                verdictTitle = title,
                verdictDetail = detail,
                verdictType = type
            )

        } catch (e: Exception) {
            return@withContext TestResult(
                success = false,
                verdictTitle = "🔴 NO COMPRAR (ERROR DE E/S)",
                verdictDetail = "Ocurrió un error grave de lectura/escritura: ${e.localizedMessage ?: "Fallo de comunicación"}. El disco está defectuoso.",
                verdictType = VerdictType.CRITICAL,
                errorMessage = e.message
            )
        } finally {
            // 6. LIMPIEZA AUTOMÁTICA DEL ARCHIVO TEMPORAL
            try {
                testFile.delete()
            } catch (_: Exception) {}
        }
    }
}
