package com.scrapdisk.doctor

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
    val errorMessage: String? = null
)

class DiskTester(private val context: Context) {

    suspend fun runFastTest(
        treeUri: Uri,
        onStatus: (String) -> Unit
    ): SimpleTestResult = withContext(Dispatchers.IO) {
        try {
            onStatus("Conectando con unidad USB...")
            val rootDir = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext SimpleTestResult(
                    success = false,
                    speedText = "--",
                    verdictTitle = "🔴 ERROR DE CONEXIÓN",
                    verdictDetail = "No se pudo acceder a la unidad. Verifica que el cable OTG esté bien conectado.",
                    isGood = false
                )

            val testFileName = "_test_${System.currentTimeMillis() % 10000}.tmp"
            val testFile = rootDir.createFile("application/octet-stream", testFileName)

            // Si Android no permite crear el archivo, verificar si es por formato NTFS (solo lectura en Android)
            if (testFile == null) {
                onStatus("Verificando si es partición NTFS (solo lectura)...")
                val files = rootDir.listFiles()
                val existingFile = files.firstOrNull { it.isFile && it.length() > 0 }
                if (existingFile != null) {
                    val readOk = testReadExisting(existingFile)
                    if (readOk) {
                        return@withContext SimpleTestResult(
                            success = true,
                            speedText = "Lectura OK",
                            verdictTitle = "🟡 DISCO NTFS (OPERATIVO)",
                            verdictDetail = "El disco gira y responde a lectura. No se pudo escribir porque está en formato NTFS (formato de Windows que Android solo lee de fábrica).",
                            isGood = false,
                            isWarning = true
                        )
                    }
                }
                return@withContext SimpleTestResult(
                    success = false,
                    speedText = "0 MB/s",
                    verdictTitle = "🔴 NO SE PUEDE ESCRIBIR",
                    verdictDetail = "El disco rechazó la escritura. Puede tener la tabla de particiones dañada o estar protegido contra escritura.",
                    isGood = false
                )
            }

            try {
                // Prueba rápida y ligera: 2 MB en bloques de 32 KB para evitar saturar el búfer IPC de Android
                val totalBytes = 2 * 1024 * 1024 // 2 MB
                val chunkSize = 32 * 1024 // 32 KB por paquete
                val sampleData = ByteArray(chunkSize).apply { Random.nextBytes(this) }
                val writeDigest = MessageDigest.getInstance("MD5")

                // FASE 1: ESCRITURA CON TIMEOUT ESTRICTO DE 6 SEGUNDOS (Evita que se quede pegado)
                onStatus("Escribiendo datos de prueba (2 MB)...")
                val writeStart = System.currentTimeMillis()

                withTimeout(6000L) {
                    val outStream: OutputStream = context.contentResolver.openOutputStream(testFile.uri)
                        ?: throw Exception("No se pudo abrir el stream de salida")
                    outStream.use { out ->
                        var written = 0
                        while (written < totalBytes) {
                            out.write(sampleData)
                            writeDigest.update(sampleData)
                            written += chunkSize
                        }
                        out.flush()
                    }
                }
                val writeTime = (System.currentTimeMillis() - writeStart).coerceAtLeast(1)
                val writeSpeedMBs = 2.0 / (writeTime / 1000.0)

                // FASE 2: LECTURA CON TIMEOUT ESTRICTO DE 6 SEGUNDOS
                onStatus("Leyendo y verificando integridad...")
                val readDigest = MessageDigest.getInstance("MD5")
                val readBuffer = ByteArray(chunkSize)
                val readStart = System.currentTimeMillis()

                withTimeout(6000L) {
                    val inStream: InputStream = context.contentResolver.openInputStream(testFile.uri)
                        ?: throw Exception("No se pudo abrir el stream de entrada")
                    inStream.use { input ->
                        var readBytes: Int
                        while (input.read(readBuffer).also { readBytes = it } != -1) {
                            readDigest.update(readBuffer, 0, readBytes)
                        }
                    }
                }
                val readTime = (System.currentTimeMillis() - readStart).coerceAtLeast(1)
                val readSpeedMBs = 2.0 / (readTime / 1000.0)

                // FASE 3: COMPROBAR SUMA DE COMPROBACIÓN MD5
                val md5Match = writeDigest.digest().contentEquals(readDigest.digest())
                if (!md5Match) {
                    return@withContext SimpleTestResult(
                        success = false,
                        speedText = String.format("%.1f MB/s", writeSpeedMBs),
                        verdictTitle = "🔴 NO COMPRAR (CORRUPCIÓN)",
                        verdictDetail = "¡Alerta! Los datos se alteraron al escribirse. El disco tiene sectores defectuosos que dañan archivos.",
                        isGood = false
                    )
                }

                // Veredicto exitoso
                val speedStr = String.format("Esc: %.1f MB/s  |  Lec: %.1f MB/s", writeSpeedMBs, readSpeedMBs)
                return@withContext SimpleTestResult(
                    success = true,
                    speedText = speedStr,
                    verdictTitle = "🟢 COMPRA SEGURA (BUENO)",
                    verdictDetail = "El disco escribió y leyó con normalidad sin colgarse y con integridad 100% verificada.",
                    isGood = true
                )

            } finally {
                try { testFile.delete() } catch (_: Exception) {}
            }

        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            return@withContext SimpleTestResult(
                success = false,
                speedText = "0 MB/s (Congelado)",
                verdictTitle = "🔴 NO COMPRAR (DISCO TRABADO)",
                verdictDetail = "El disco tardó más de 6 segundos en responder. Tiene cabezales atascados o sectores muertos que congelan el sistema.",
                isGood = false
            )
        } catch (e: Exception) {
            return@withContext SimpleTestResult(
                success = false,
                speedText = "Error I/O",
                verdictTitle = "🔴 NO COMPRAR (ERROR DE E/S)",
                verdictDetail = e.localizedMessage ?: "Error de comunicación con el disco.",
                isGood = false,
                errorMessage = e.message
            )
        }
    }

    private fun testReadExisting(file: DocumentFile): Boolean {
        return try {
            val buf = ByteArray(16384)
            context.contentResolver.openInputStream(file.uri)?.use {
                it.read(buf) > 0
            } ?: false
        } catch (_: Exception) {
            false
        }
    }
}
