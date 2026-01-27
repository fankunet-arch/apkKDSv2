/*
 * 文件名: app/src/main/java/com/toptea/topteakds/ConfigManager.kt
 * 描述: 规范 4.3 节和 7.1 节, 负责持久化保存和读取打印机配置
 */
package com.toptea.topteakds // <-- 已修正

import android.content.Context
import android.content.SharedPreferences

// 用于承载配置的数据类
data class PrinterConfig(
    val type: String,
    val ip: String,
    val port: Int,
    val macAddress: String
)

object ConfigManager {

    private const val PREFS_NAME = "TopTeaPrinterConfig"
    private const val KEY_TYPE = "PRINTER_TYPE"
    private const val KEY_IP = "PRINTER_IP"
    private const val KEY_PORT = "PRINTER_PORT"
    private const val KEY_MAC = "PRINTER_MAC"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 规范 4.3 和 7.3: 保存配置到 SharedPreferences
     */
    fun saveConfig(context: Context, type: String, ip: String, port: Int, macAddress: String) {
        val editor = getPrefs(context).edit()
        editor.putString(KEY_TYPE, type)
        editor.putString(KEY_IP, ip)
        editor.putInt(KEY_PORT, port)
        editor.putString(KEY_MAC, macAddress)
        editor.apply()
    }

    /**
     * 规范 7.1: 读取已保存的配置
     */
    fun loadConfig(context: Context): PrinterConfig {
        val prefs = getPrefs(context)
        return PrinterConfig(
            type = prefs.getString(KEY_TYPE, "WIFI") ?: "WIFI",
            ip = prefs.getString(KEY_IP, "") ?: "",
            port = prefs.getInt(KEY_PORT, 9100),
            macAddress = prefs.getString(KEY_MAC, "") ?: ""
        )
    }
}