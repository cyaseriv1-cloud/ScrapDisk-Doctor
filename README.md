# 🩺 ScrapDisk Doctor (Android OTG)

**ScrapDisk Doctor** es una aplicación nativa para Android diseñada específicamente para evaluar en menos de 30 segundos si un disco duro mecánico (HDD) o disco de estado sólido (SSD) de segunda mano o desguace es apto para compra.

---

## ⚡ ¿Cómo funciona la prueba?

1. **Conexión OTG:** Conecta tu disco duro al teléfono móvil usando tu adaptador/cable OTG.
2. **Selección rápida:** Presiona el botón principal **SELECCIONAR DISCO Y TESTEAR** y escoge la carpeta raíz de tu disco USB en el selector del sistema.
3. **Prueba de Estrés (30 segundos):**
   * **Escritura:** Escribe bloques de datos de prueba (10 MB, 30 MB o 50 MB) midiendo la velocidad exacta (MB/s).
   * **Lectura:** Lee secuencialmente los datos y calcula su suma de comprobación MD5.
   * **Integridad de sectores:** Compara que los datos leídos sean idénticos a los escritos. Si hay un solo byte alterado, detecta sectores muertos o corruptos.
   * **Limpieza:** Elimina automáticamente el archivo temporal generado (`.tmp`).
4. **Veredicto:** Emite una respuesta visual clara tipo semáforo y una vibración háptica:
   * 🟢 **COMPRA SEGURA (Excelente):** Velocidad fluida (> 30 MB/s), 0 errores.
   * 🟡 **APTO CON PRECAUCIÓN:** Velocidad modesta (12-30 MB/s), pero datos íntegros.
   * 🔴 **NO COMPRAR:** Error de lectura/escritura (I/O Error), datos alterados o velocidad crítica (< 12 MB/s).

---

## 📱 Descarga e Instalación en tu Móvil

Puedes descargar directamente el instalador **APK** listo para usar desde la sección de **Releases** de este repositorio:
👉 [Descargar ScrapDisk Doctor APK](https://github.com/cyaseriv1-cloud/ScrapDisk-Doctor/releases)

---

## 🛠️ Tecnologías Utilizadas

* **Lenguaje:** Kotlin
* **Arquitectura:** MVVM con Coroutines (`Dispatchers.IO` y `Dispatchers.Main`)
* **Acceso a Archivos:** Android Storage Access Framework (SAF - `DocumentFile`)
* **Feedback:** Haptic Vibrator API (Android 8.0 a Android 14+)
* **CI/CD:** GitHub Actions (Compilación y generación automática de APK con Gradle)

---

## 🛡️ Consejos de Seguridad al Probar Discos en Desguaces

1. **Protección de tu teléfono:** Aunque teléfonos modernos (como el Oppo Find X5) cuentan con protección de sobrecorriente en el puerto USB-C, se recomienda alimentar discos mecánicos mediante un cable en "Y" con una Power Bank externa si el disco vibra con dificultad.
2. **Prueba del Oído:** Si al enchufar el disco escuchas un sonido metálico repetitivo (*"clic... clic... clic..."*), desconéctalo de inmediato: los cabezales o los platos están trabados físicamente.
