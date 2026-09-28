package com.adb.kitty.data.fastboot

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface

enum class UsbDeviceMode(val displayName: String) {
    ADB("ADB 调试模式 (255/66/1)"),
    FASTBOOT("Fastboot / Fastbootd 标准模式 (255/66/3)"),
    FASTBOOTD_CUSTOM("特定新设备 Fastbootd 扩展接口 (255/66/2)"),
    QUALCOMM_9008("高通 9008 EDL 深度刷机模式 (VID:1478 PID:36872)"),
    QUALCOMM_900E("高通 900E 诊断模式 (VID:1478 PID:36878)"),
    MTK_PRELOADER("联发科 MTK Preloader 模式 (VID:3725 PID:8192)"),
    MTK_BROM("联发科 MTK BROM 深度刷机模式 (VID:3725 PID:3)"),
    UNKNOWN("未知设备/未匹配到有效通信接口");

    companion object {
        fun matchDevice(device: UsbDevice): Pair<UsbDeviceMode, UsbInterface?> {
            val vid = device.vendorId
            val pid = device.productId

            // 1. 优先校验硬件厂商特定 EDL/BROM VID 与 PID
            when {
                vid == 1478 && pid == 36872 -> return QUALCOMM_9008 to findPrimaryBulkInterface(device)
                vid == 1478 && pid == 36878 -> return QUALCOMM_900E to findPrimaryBulkInterface(device)
                vid == 3725 && pid == 8192  -> return MTK_PRELOADER to findPrimaryBulkInterface(device)
                vid == 3725 && pid == 3     -> return MTK_BROM to findPrimaryBulkInterface(device)
            }

            // 2. 校验 Android 规范接口 Class=255, Subclass=66
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == 255 && intf.interfaceSubclass == 66) {
                    return when (intf.interfaceProtocol) {
                        1 -> ADB to intf
                        3 -> FASTBOOT to intf // 绝大多数 Bootloader Fastboot 以及 用户态 Fastbootd
                        2 -> FASTBOOTD_CUSTOM to intf // 仅部分新机型/特定厂商使用的 Fastbootd 接口
                        else -> UNKNOWN to null
                    }
                }
            }

            return UNKNOWN to null
        }

        private fun findPrimaryBulkInterface(device: UsbDevice): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.endpointCount >= 2) {
                    return intf
                }
            }
            return if (device.interfaceCount > 0) device.getInterface(0) else null
        }
    }
}
