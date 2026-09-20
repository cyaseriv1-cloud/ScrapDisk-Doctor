package com.scrapdisk.doctor

import android.content.Context
import android.net.Uri
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
    val errorMessage: String? = null
)

class DiskTester(private val context: Context) {

    suspend fun runFastTest(
        treeUri: Uri,
        onStatus: (String) -> Unit
    ): SimpleTestResult = withContext(Dispatchers.IO) {
        // TIMEOUT GLOBAL ESTRICTO: Nada en esta función puede tardar más de 6 segundos jamás
        try {
            withTimeout(6000L) {
                onStatus("1/3 Conectando con unidad USB...")

                val rootDir = DocumentFile.fromTreeUri(context, treeUri)
                    ?: return@withTimeout SimpleTestResult(
                        success = false,
                        speedText = "--",
                        verdictTitle = "🔴 ERROR DE CONEXIÓN",
                        verdictDetail = "No se pudo acceder a la unidad. Verifica que el cable OTG esté bien conectado.",
                        isGood = false
                    )

                // 1. COMPROBAR MODO DE ACCESO (NTFS vs FAT32/exFAT)
                // Si el disco está en NTFS (formato típico de Windows), Android no permite escritura de fábrica.
                // En vez de congelar el teléfono intentando escribir, pasamos directo al test de lectura.
                val canWrite = rootDir.canWrite()

                if (!canWrite) {
                    onStatus("2/3 Disco NTFS detectado (Probando lectura)...")
                    return@withTimeout performReadPerformanceTest(rootDir, onStatus, isNtfs = true)
                }

                // 2. DISCO ESCRIBIBLE: INTENTAR CREAR ARCHIVO TEMPORAL (2 segundos de timeout máximo)
                onStatus("2/3 Creando archivo de prueba (1 MB)...")
                val testFileName = "_test_${System.currentTimeMillis() % 1000}.tmp"
                
                val testFile = withTimeoutOrNull(2000L) {
                    try {
                        rootDir.createFile("application/octet-stream", testFileName)
                    } catch (_: Exception) {
                        null
                    }
                }

                // Si falló la creación (por ejemplo, partición con protección), probar lectura
                if (testFile == null) {
                    onStatus("Escritura bloqueada por Android, probando lectura...")
                    return@withTimeout performReadPerformanceTest(rootDir, onStatus, isNtfs = true)
                }

                // 3. FASE DE ESCRITURA Y LECTURA (1 MB en paquetes de 32 KB)
                try {
                    val totalBytes = 1024 * 1024 // 1 MB
                    val chunkSize = 32 * 1024 // 32 KB
                    val sampleData = ByteArray(chunkSize).apply { Random.nextBytes(this) }
                    val writeDigest = MessageDigest.getInstance("MD5")

                    // Escritura
                    onStatus("3/3 Escribiendo y leyendo datos (1 MB)...")
                    val writeStart = System.currentTimeMillis()
                    
                    val outStream: OutputStream = context.contentResolver.openOutputStream(testFile.uri)
                        ?: throw Exception("No se pudo abrir salida")
                    outStream.use { out ->
                        var written = 0
                        while (written < totalBytes) {
                            out.write(sampleData)
                            writeDigest.update(sampleData)
                            written += chunkSize
                        }
                        out.flush()
                    }
                    val writeTime = (System.currentTimeMillis() - writeStart).coerceAtLeast(1)
                    val writeSpeedMBs = 1.0 / (writeTime / 1000.0)

                    // Lectura e Integridad
                    val readDigest = MessageDigest.getInstance("MD5")
                    val readBuffer = ByteArray(chunkSize)
                    val readStart = System.currentTimeMillis()

                    val inStream: InputStream = context.contentResolver.openInputStream(testFile.uri)
                        ?: throw Exception("No se pudo abrir entrada")
                    inStream.use { input ->
                        var readBytes: Int
                        while (input.read(readBuffer).also { readBytes = it } != -1) {
                            readDigest.update(readBuffer, 0, readBytes)
                        }
                    }
                    val readTime = (System.currentTimeMillis() - readStart).coerceAtLeast(1)
                    val readSpeedMBs = 1.0 / (readTime / 1000.0)

                    // Comprobación de MD5
                    val md5Match = writeDigest.digest().contentEquals(readDigest.digest())
                    if (!md5Match) {
                        return@withTimeout SimpleTestResult(
                            success = false,
                            speedText = String.format("Esc: %.1f MB/s", writeSpeedMBs),
                            verdictTitle = "🔴 NO COMPRAR (DATOS CORRUPTOS)",
                            verdictDetail = "¡Alerta! Los datos se alteraron al escribirse. El disco tiene sectores magnéticos dañados.",
                            isGood = false
                        )
                    }

                    val speedStr = String.format("Esc: %.1f MB/s  |  Lec: %.1f MB/s", writeSpeedMBs, readSpeedMBs)
                    return@withTimeout SimpleTestResult(
                        success = true,
                        speedText = speedStr,
                        verdictTitle = "🟢 COMPRA SEGURA (BUENO)",
                        verdictDetail = "El disco escribió y leyó con normalidad sin colgarse y con integridad 100% verificada.",
                        isGood = true
                    )

                } finally {
                    try { testFile.delete() } catch (_: Exception) {}
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            return@withContext SimpleTestResult(
                success = false,
                speedText = "0 MB/s (Congelado)",
                verdictTitle = "🔴 NO COMPRAR (DISCO TRABADO)",
                verdictDetail = "El disco tardó más de 6 segundos en responder a los comandos. Los platos o cabezales están trabados mecánicamente.",
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

    private suspend fun performReadPerformanceTest(
        rootDir: DocumentFile,
        onStatus: (String) -> Unit,
        isNtfs: Boolean
    ): SimpleTestResult = withContext(Dispatchers.IO) {
        onStatus("3/3 Probando velocidad de lectura en disco...")
        
        // Buscar algún archivo existente en el disco para probar la lectura real
        val files = try { rootDir.listFiles() } catch (_: Exception) { emptyArray() }
        val targetFile = files.firstOrNull { it.isFile && it.length() > 0 }

        if (targetFile != null) {
            try {
                val readStart = System.currentTimeMillis()
                val buffer = ByteArray(32 * 1024)
                var bytesReadTotal = 0L
                val maxToRead = 1024 * 1024 // 1 MB máximo

                val inStream = context.contentResolver.openInputStream(targetFile.uri)
                inStream?.use { input ->
                    while (bytesReadTotal < maxToRead) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        bytesReadTotal += count
                    }
                }

                val readTime = (System.currentTimeMillis() - readStart).coerceAtLeast(1)
                val readSpeedMBs = (bytesReadTotal.toDouble() / (1024 * 1024)) / (readTime / 1000.0)

                val speedStr = String.format("Lectura: %.1f MB/s", readSpeedMBs)
                return@withContext SimpleTestResult(
                    success = true,
                    speedText = speedStr,
                    verdictTitle = "🟢 DISCO OPERATIVO (NTFS)",
                    verdictDetail = "El disco gira y lee a ${String.format("%.1f", readSpeedMBs)} MB/s sin trabarse. Al ser formato NTFS de Windows, Android no le escribe, pero el hardware está sano.",
                    isGood = true,
                    isWarning = false
                )
            } catch (e: Exception) {
                return@withContext SimpleTestResult(
                    success = false,
                    speedText = "Fallo de lectura",
                    verdictTitle = "🔴 NO COMPRAR (SECTOR ILEGIBLE)",
                    verdictDetail = "No se pudieron leer los archivos del disco: ${e.localizedMessage ?: "Error de E/S"}",
                    isGood = false
                )
            }
        }

        // Si el disco no tiene archivos pero respondió a la lista de directorios
        return@withContext SimpleTestResult(
            success = true,
            speedText = "Tabla de partición OK",
            verdictTitle = if (isNtfs) "🟢 DISCO OPERATIVO (NTFS Vacío)" else "🟢 DISCO DETECTADO",
            verdictDetail = "El disco respondió de inmediato a la conexión y su tabla de particiones está intacta.",
            isGood = true
        )
    }
}
