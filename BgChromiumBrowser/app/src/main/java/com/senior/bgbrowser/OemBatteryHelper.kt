package com.senior.bgbrowser

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AlertDialog

object OemBatteryHelper {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (!isIgnoringBatteryOptimizations(context)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            }
        }
    }

    fun showOemGuideDialog(context: Context) {
        val brand = Build.MANUFACTURER.lowercase()
        val guideMessage = when {
            brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") -> """
                Thiết bị Xiaomi / HyperOS / MIUI:
                1. Bật "Tự khởi chạy" (Autostart) cho ứng dụng.
                2. Vào Pin & Hiệu suất -> Tiết kiệm pin ứng dụng -> Chọn "Không hạn chế" (No restrictions).
                3. Mở danh sách Đa nhiệm (Recent Apps), kéo giữ ứng dụng và bấm biểu tượng Khóa.
            """.trimIndent()

            brand.contains("samsung") -> """
                Thiết bị Samsung (One UI):
                1. Vào Cài đặt -> Ứng dụng -> BG Chromium Browser -> Pin -> Chọn "Không hạn chế" (Unrestricted).
                2. Vào Chăm sóc thiết bị -> Pin -> Giới hạn sử dụng dưới nền -> Thêm vào "Ứng dụng không bao giờ nghỉ".
            """.trimIndent()

            brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") -> """
                Thiết bị OPPO / Realme / OnePlus (ColorOS):
                1. Vào Cài đặt -> Ứng dụng -> Quản lý ứng dụng -> BG Chromium Browser.
                2. Vào Mức sử dụng pin -> Bật "Cho phép hoạt động dưới nền" và "Cho phép tự khởi động".
            """.trimIndent()

            brand.contains("vivo") || brand.contains("iqoo") -> """
                Thiết bị Vivo / iQOO (FuntouchOS / OriginOS):
                1. Vào Cài đặt -> Pin -> Tiêu thụ điện năng nền cao -> Bật cho phép BG Chromium Browser.
                2. Vào Quản lý ứng dụng -> Quyền -> Tự khởi động -> Bật.
            """.trimIndent()

            brand.contains("huawei") || brand.contains("honor") -> """
                Thiết bị Huawei / Honor (EMUI / HarmonyOS):
                1. Vào Cài đặt -> Pin -> Khởi chạy ứng dụng (App Launch).
                2. Tắt "Quản lý tự động" và bật đủ 3 mục: Tự khởi chạy, Khởi chạy thứ cấp, Chạy trong nền.
            """.trimIndent()

            else -> """
                Hướng dẫn chung:
                1. Cho phép "Không tối ưu hóa pin" (Unrestricted battery).
                2. Cấp quyền "Hiển thị trên ứng dụng khác" (Overlay 1x1px) để giữ tốc độ bộ đếm JS khi tắt màn hình.
            """.trimIndent()
        }

        AlertDialog.Builder(context)
            .setTitle("Hướng dẫn chống ngủ theo hãng (${Build.MANUFACTURER})")
            .setMessage(guideMessage)
            .setPositiveButton("Mở cài đặt Tự khởi chạy") { _, _ ->
                openOemAutoStartSettings(context)
            }
            .setNeutralButton("Xin miễn tối ưu Pin") { _, _ ->
                requestIgnoreBatteryOptimizations(context)
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun openOemAutoStartSettings(context: Context) {
        val intents = listOf(
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
        )
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            } catch (_: Exception) {}
        }
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(fallback)
    }
}
