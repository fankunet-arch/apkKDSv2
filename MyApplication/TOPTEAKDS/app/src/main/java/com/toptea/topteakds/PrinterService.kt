/*
 * 文件名: app/src/main/java/com/toptea/topteakds/PrinterService.kt
 * 描述: 规范 7.1 节, 打印作业的实际执行者
 */
package com.toptea.topteakds // <-- 已修正

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.OutputStream
import java.net.Socket
// import com.toptea.topteakds.ConfigManager (在同一个包下, 无需 import)
// import com.toptea.topteakds.PrinterConfig (在同一个包下, 无需 import)


object PrinterService {

    /**
     * 规范 7.1: 执行打印作业的入口点
     * 必须在后台线程 (Dispatchers.IO) 上调用
     */
    suspend fun executePrint(context: Context, jsonPayloadString: String) {
        // 1. 读取配置 (规范 7.1)
        val config = ConfigManager.loadConfig(context)

        // 2. 解析负载 (规范 7.1)
        val json = JSONObject(jsonPayloadString)
        val size = json.getString("size")
        val commands = json.getJSONArray("commands")

        // 标记是 ESC/POS (收据) 还是 TSPL (标签)
        val isTsplMode = size.contains("x", ignoreCase = true) // e.g., "50x30 mm"

        var outputStream: OutputStream? = null
        var socket: Socket? = null
        // TODO: 蓝牙和 USB 可能需要其他连接对象

        try {
            // 3. 建立连接 (规范 7.1)
            when (config.type) {
                "WIFI" -> {
                    if (config.ip.isEmpty() || config.port == 0) {
                        throw Exception("WIFI config is invalid (IP/Port missing)")
                    }
                    // 使用 withContext 确保 I/O 操作在 IO 线程
                    val newSocket = withContext(Dispatchers.IO) {
                        Socket(config.ip, config.port)
                    }
                    socket = newSocket
                    outputStream = newSocket.getOutputStream()
                }
                "BLUETOOTH" -> {
                    // TODO: 在此处实现蓝牙连接逻辑 (规范 7.1)
                    // 1. 使用 config.macAddress
                    // 2. 使用厂商的 BT SDK 建立连接
                    // 3. 将连接的 OutputStream 赋值给 outputStream
                    throw Exception("Bluetooth printing not yet implemented")
                }
                "USB" -> {
                    // TODO: 在此处实现 USB 连接逻辑 (规范 7.1)
                    // 1. 使用厂商的 USB SDK 建立连接
                    // 2. 将连接的 OutputStream 赋值给 outputStream
                    throw Exception("USB printing not yet implemented")
                }
                else -> throw Exception("Unknown printer type: ${config.type}")
            }

            if (outputStream == null) {
                throw Exception("Failed to establish printer connection")
            }

            val writer = DataOutputStream(outputStream)

            // 4. 配置驱动 (规范 7.1 关键逻辑)
            if (isTsplMode) {
                // 分支 B: TSPL 模式 (标签)
                // 示例尺寸: "50x30 mm"
                val sizePart = size.split(" ")[0] // e.g., "50x30"
                val dimensions = sizePart.split("x", ignoreCase = true)

                // 安全检查：确保尺寸格式正确
                if (dimensions.size < 2) {
                    throw Exception("Invalid TSPL size format: '$size'. Expected format: 'WIDTHxHEIGHT mm' (e.g., '50x30 mm')")
                }

                val width = dimensions[0].trim()
                val height = dimensions[1].trim()

                // 验证宽高是有效数字
                if (width.toDoubleOrNull() == null || height.toDoubleOrNull() == null) {
                    throw Exception("Invalid dimensions in size: '$size'. Width and height must be numbers.")
                }

                // TODO: 确认你的 TSPL 打印机的间隙
                val gap = "2" // 标签间隙, 2mm

                // 规范 7.1: 发送 TSPL 初始化指令
                writer.writeBytes("SIZE $width mm, $height mm\n")
                writer.writeBytes("GAP $gap mm, 0 mm\n")
                writer.writeBytes("CLS\n")
                // TODO: 可能还需要设置 DENSITY, SPEED 等
            } else {
                // 分支 A: ESC/POS 模式 (收据)
                // 规范 7.1: 发送 ESC/POS 初始化指令
                writer.write(byteArrayOf(0x1B, 0x40)) // ESC @
            }

            // 5. 循环执行指令 (规范 7.1)
            for (i in 0 until commands.length()) {
                val cmd = commands.getJSONObject(i)
                val type = cmd.getString("type")

                // TODO: 在这里将 JSON 指令翻译为 TSPL 或 ESC/POS 字节流
                // (这是一个简化的示例)
                when (type) {
                    "text" -> {
                        val value = cmd.getString("value")
                        if (isTsplMode) {
                            // TODO: TSPL 文本指令 (e.g., TEXT 10,10,"TSS24.BF2",0,1,1,"芒果果肉")
                            writer.writeBytes("TEXT 20,20,\"TSS24.BF2\",0,1,1,\"$value\"\n")
                        } else {
                            // TODO: ESC/POS 文本指令
                            // 需要处理 size, align 等
                            writer.write(value.toByteArray(charset("GBK"))) // 假设是中文
                            writer.writeBytes("\n")
                        }
                    }
                    "kv" -> {
                        val key = cmd.getString("key")
                        val value = cmd.getString("value")
                        if (isTsplMode) {
                            // TODO: TSPL KV 指令
                            writer.writeBytes("TEXT 20,50,\"TSS24.BF2\",0,1,1,\"$key: $value\"\n")
                        } else {
                            // TODO: ESC/POS KV 指令 (通常需要计算位置)
                            writer.write("$key: $value\n".toByteArray(charset("GBK")))
                        }
                    }
                    "divider" -> {
                        val char = cmd.optString("char", "-")
                        val divider = char.repeat(if (size == "58mm") 32 else 48)
                        if (isTsplMode) {
                            // TODO: TSPL 分隔线 (e.g., BAR)
                        } else {
                            writer.write("$divider\n".toByteArray(charset("GBK")))
                        }
                    }
                    "feed" -> {
                        val lines = cmd.optInt("lines", 1)
                        if (isTsplMode) {
                            // TSPL 模式下 'feed' 通常不单独使用, 靠 PRINT 推进
                        } else {
                            for (l in 0 until lines) {
                                writer.writeBytes("\n")
                            }
                        }
                    }
                    "cut" -> {
                        if (!isTsplMode) {
                            // 规范 7.1: (收据模式) 发送切刀指令
                            writer.write(byteArrayOf(0x1D, 0x56, 0x01)) // GS V 1 (全切)
                        }
                    }
                }
            }

            // 6. 结束任务 (规范 7.1)
            if (isTsplMode) {
                // (标签模式): 发送最终 PRINT 指令
                writer.writeBytes("PRINT 1,1\n")
            } else {
                // (收据模式): 额外走纸以确保切刀
                writer.writeBytes("\n\n\n")
            }

            writer.flush()

        } catch (e: Exception) {
            // 7. [错误处理] 捕获所有异常并向上抛出
            throw Exception("Print job failed: ${e.message}", e)
        } finally {
            // 8. 关闭连接 (规范 7.1)
            withContext(Dispatchers.IO) {
                outputStream?.close()
                socket?.close()
            }
            // TODO: 关闭蓝牙和 USB 连接
        }
    }
}