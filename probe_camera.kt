#!/usr/bin/env kotlin

// Quick script to probe camera config metadata
// Usage: kotlinc -cp app/build/outputs/apk/debug/app-debug.apk:app/libs/* probe_camera.kt -include-runtime -d probe.jar && java -jar probe.jar

import java.net.Socket
import kotlinx.serialization.json.*

fun main() {
    val host = "192.168.88.129"
    val port = 34567
    val username = "admin"
    val password = ""

    println("[*] Connecting to $host:$port...")
    val socket = Socket(host, port)
    val input = socket.inputStream
    val output = socket.outputStream

    // Send login (simplified DVRIP frame construction)
    println("[*] Authenticating as $username...")

    val loginJson = """
    {
      "EncryptType": "MD5",
      "LoginType": "DVRIP-Web",
      "PassWord": "${password.md5()}",
      "UserName": "$username"
    }
    """.trimIndent()

    // For now, just print the queries we'd make
    println("\n[*] Configs to query for metadata:")
    val configs = listOf(
        "Camera.Param",
        "Camera.ParamEx",
        "Detect.MotionDetect",
        "General.General",
        "NetWork.NetCommon",
        "Record",
        "ExtRecord",
    )

    configs.forEach { config ->
        println("  - $config")
    }

    println("\n[*] To query from camera, we'd send:")
    configs.forEach { config ->
        val json = """{"Name":"$config","$config":{},"SessionID":"0x00000000"}"""
        println("  PUT $config: $json")
    }

    socket.close()
}

// Simple MD5 for password hashing
fun String.md5(): String {
    val bytes = toByteArray()
    val digest = java.security.MessageDigest.getInstance("MD5")
    val hash = digest.digest(bytes)
    return hash.joinToString("") { "%02x".format(it) }
}
