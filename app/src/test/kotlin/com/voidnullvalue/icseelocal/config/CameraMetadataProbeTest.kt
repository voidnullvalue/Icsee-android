package com.voidnullvalue.icseelocal.config

import com.voidnullvalue.icseelocal.crypto.NullSessionCrypto
import com.voidnullvalue.icseelocal.dvrip.DvripTransport
import com.voidnullvalue.icseelocal.session.CameraCredentials
import com.voidnullvalue.icseelocal.session.DvripCommandChannel
import com.voidnullvalue.icseelocal.session.DvripLoginNegotiator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * Probe a real camera to discover what metadata/constraints it returns for configs.
 * Run with: ./gradlew testDebugUnitTest -Dtest.single=CameraMetadataProbeTest
 */
class CameraMetadataProbeTest {
    private val host = "192.168.88.129"
    private val port = 34567
    private val username = "admin"
    private val password = ""

    @Test
    fun probe_camera_metadata() = runBlocking {
        println("\n[*] Connecting to $host:$port...")
        val transport = DvripTransport(host, port)
        transport.connect()

        try {
            println("[*] Authenticating as $username...")
            val negotiator = DvripLoginNegotiator()
            val session = negotiator.negotiate(transport, CameraCredentials(username, password))
            println("[+] Authenticated, SessionID: 0x%08X".format(session.sessionId.toLong()))

            // Query each config to see what the camera returns
            val commandChannel = DvripCommandChannel(transport, session.sessionId, NullSessionCrypto)
            val configChannel = DvripConfigChannel(transport, commandChannel, session.sessionId)

            val configs = listOf(
                "Camera.Param" to "image/sensor settings",
                "Camera.ParamEx" to "extended image settings",
                "Detect.MotionDetect" to "motion detection",
                "General.General" to "general settings",
                "NetWork.NetCommon" to "network settings",
                "Record" to "recording settings",
                "ExtRecord" to "extended recording",
            )

            println("\n[*] Querying configs for metadata...\n")
            configs.forEach { (configName, description) ->
                println("================================================================================")
                println("CONFIG: $configName ($description)")
                println("================================================================================")

                try {
                    val result = configChannel.getConfig(configName)
                    when (result) {
                        is ConfigResult.Success -> {
                            println("Status: SUCCESS")
                            println("Raw JSON:")
                            val json = Json { prettyPrint = true }
                            println(json.encodeToString(JsonElement.serializer(), result.value))

                            // Analyze constraints
                            val constraints = ConfigMetadataAnalyzer.analyze(configName, result.value)
                            println("\nDiscovered constraints:")
                            constraints.forEach { (path, constraint) ->
                                println("  $path: ${constraint.hint()}")
                            }
                        }
                        is ConfigResult.Failure -> {
                            println("Status: FAILED")
                            println("Ret: ${result.ret}")
                            println("Raw: ${result.raw}")
                        }
                    }
                } catch (e: Exception) {
                    println("ERROR: ${e.message}")
                    e.printStackTrace()
                }
                println()
            }
        } finally {
            transport.close()
        }
    }

}
