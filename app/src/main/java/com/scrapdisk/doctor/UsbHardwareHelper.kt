package com.scrapdisk.doctor

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

data class UsbHardwareInfo(
    val isConnected: Boolean,
    val deviceName: String,
    val bridgeChip: String,
    val vidPid: String,
    val summary: String
)

class UsbHardwareHelper(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    fun getConnectedStorageInfo(): UsbHardwareInfo {
        if (usbManager == null) {
            return UsbHardwareInfo(false, "Sin USB", "Desconocido", "--", "USB Manager no disponible")
        }

        val deviceList = usbManager.deviceList
        if (deviceList.isEmpty()) {
            return UsbHardwareInfo(false, "Desconectado", "--", "--", "Conecta tu adaptador OTG con el disco")
        }

        // Buscar dispositivo de almacenamiento masivo o puente USB
        for (device in deviceList.values) {
            if (isStorageOrBridge(device)) {
                val vendorName = resolveVendorName(device.vendorId)
                val prodName = device.productName?.takeIf { it.isNotBlank() } ?: "Dispositivo USB"
                val mfgName = device.manufacturerName?.takeIf { it.isNotBlank() } ?: vendorName
                val vidHex = String.format("0x%04X", device.vendorId)
                val pidHex = String.format("0x%04X", device.productId)

                val bridgeDescription = if (mfgName != vendorName) "$vendorName ($mfgName)" else vendorName
                val summary = "🔌 $prodName | Chip: $bridgeDescription ($vidHex:$pidHex)"

                return UsbHardwareInfo(
                    isConnected = true,
                    deviceName = prodName,
                    bridgeChip = bridgeDescription,
                    vidPid = "$vidHex:$pidHex",
                    summary = summary
                )
            }
        }

        // Si hay algún dispositivo USB conectado pero no catalogado como clase 0x08
        val firstDev = deviceList.values.first()
        val vidHex = String.format("0x%04X", firstDev.vendorId)
        val pidHex = String.format("0x%04X", firstDev.productId)
        val vendor = resolveVendorName(firstDev.vendorId)
        val prod = firstDev.productName ?: "Adaptador USB"

        return UsbHardwareInfo(
            isConnected = true,
            deviceName = prod,
            bridgeChip = vendor,
            vidPid = "$vidHex:$pidHex",
            summary = "🔌 $prod | Chip: $vendor ($vidHex:$pidHex)"
        )
    }

    private fun isStorageOrBridge(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_MASS_STORAGE) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE) {
                return true
            }
        }
        // Algunos puentes económicos se registran como clase Vendor (0xFF)
        val knownStorageVendors = setOf(0x174C, 0x152D, 0x0BDA, 0x2109, 0x05E3, 0x1058, 0x0BC2, 0x0480, 0x2537)
        return device.vendorId in knownStorageVendors
    }

    private fun resolveVendorName(vendorId: Int): String {
        return when (vendorId) {
            0x174C -> "ASMedia (Puente SATA/NVMe)"
            0x152D -> "JMicron (Puente SATA)"
            0x0BDA -> "Realtek (Puente USB 3.x/RTL9210)"
            0x2109 -> "VIA Labs (VLI Hub/SATA)"
            0x05E3 -> "Genesys Logic"
            0x1058 -> "Western Digital"
            0x0BC2 -> "Seagate Technology"
            0x0480 -> "Toshiba"
            0x04E8 -> "Samsung Electronics"
            0x2537 -> "Norelsys"
            0x1E7D -> "Inateck / UGREEN"
            0x04B4 -> "Cypress Semiconductor"
            0x13FD -> "Initio Corporation"
            0x0781 -> "SanDisk"
            0x0951 -> "Kingston Technology"
            else -> "Puente Genérico (VID 0x${Integer.toHexString(vendorId).uppercase()})"
        }
    }
}
