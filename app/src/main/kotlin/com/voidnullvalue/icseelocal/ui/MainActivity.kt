package com.voidnullvalue.icseelocal.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.voidnullvalue.icseelocal.ui.blepairing.BlePairingScreen
import com.voidnullvalue.icseelocal.ui.cameralist.CameraListScreen
import com.voidnullvalue.icseelocal.ui.devicemanagement.ConfigEditorScreen
import com.voidnullvalue.icseelocal.ui.devicemanagement.DeviceManagementScreen
import com.voidnullvalue.icseelocal.ui.devicemanagement.DeviceManagementViewModel
import com.voidnullvalue.icseelocal.ui.devicemanagement.ImageSettingsScreen
import com.voidnullvalue.icseelocal.ui.devicemanagement.PlaybackBrowserScreen
import com.voidnullvalue.icseelocal.ui.diagnostics.DiagnosticsScreen
import com.voidnullvalue.icseelocal.ui.live.LiveControlScreen
import com.voidnullvalue.icseelocal.ui.live.LiveControlViewModel
import com.voidnullvalue.icseelocal.ui.settings.CameraSettingsScreen
import com.voidnullvalue.icseelocal.ui.theme.IcseeTheme

/**
 * Single-Activity host with an explicit [NavStack] so system back and toolbar
 * back share one pop path. Session families (Live / DeviceManagement) still
 * connect only while a matching screen is on the stack.
 */
class MainActivity : ComponentActivity() {
    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IcseeTheme {
                Surface {
                    val nav = rememberNavStack()
                    NavBackHandler(nav) { finish() }

                    val liveControlViewModel: LiveControlViewModel = viewModel()
                    val deviceManagementViewModel: DeviceManagementViewModel = viewModel()

                    val screen = nav.current
                    val liveFamilyCameraId = when (val s = screen) {
                        is Screen.LiveControl -> s.cameraId
                        is Screen.Diagnostics -> s.cameraId
                        else -> null
                    }
                    val deviceManagementFamilyCameraId = when (val s = screen) {
                        // Live also holds the device-mgmt session so recordings can
                        // load under the stream without navigating away.
                        is Screen.LiveControl -> s.cameraId
                        is Screen.DeviceManagement -> s.cameraId
                        is Screen.ConfigEditor -> s.cameraId
                        is Screen.ImageSettings -> s.cameraId
                        is Screen.PlaybackBrowser -> s.cameraId
                        else -> null
                    }

                    LaunchedEffect(liveFamilyCameraId) {
                        if (liveFamilyCameraId != null) {
                            liveControlViewModel.enterFocus(liveFamilyCameraId)
                        } else {
                            liveControlViewModel.leaveFocus()
                        }
                    }
                    LaunchedEffect(deviceManagementFamilyCameraId) {
                        if (deviceManagementFamilyCameraId != null) {
                            deviceManagementViewModel.enterFocus(deviceManagementFamilyCameraId)
                        } else {
                            deviceManagementViewModel.leaveFocus()
                        }
                    }

                    when (val current = screen) {
                        is Screen.CameraList -> CameraListScreen(
                            onOpenCamera = { id -> nav.push(Screen.LiveControl(id)) },
                            onAddManual = { nav.push(Screen.CameraSettings(null)) },
                            onAddDiscovered = { beacon -> nav.push(Screen.CameraSettings(null, prefillBeacon = beacon)) },
                            onPairBluetooth = { nav.push(Screen.BlePairing) },
                            onOpenSettings = { id -> nav.push(Screen.CameraSettings(id)) },
                        )
                        is Screen.CameraSettings -> CameraSettingsScreen(
                            cameraId = current.cameraId,
                            prefillBeacon = current.prefillBeacon,
                            prefillBle = current.prefillBle,
                            onDone = { nav.pop() },
                        )
                        is Screen.LiveControl -> LiveControlScreen(
                            cameraId = current.cameraId,
                            onOpenDiagnostics = { nav.push(Screen.Diagnostics(current.cameraId)) },
                            onOpenDeviceManagement = { nav.push(Screen.DeviceManagement(current.cameraId)) },
                            onOpenImageSettings = { nav.push(Screen.ImageSettings(current.cameraId)) },
                            onOpenMotionDetect = {
                                nav.push(
                                    Screen.ConfigEditor(
                                        current.cameraId,
                                        "Detect.MotionDetect",
                                        "Motion detection",
                                    ),
                                )
                            },
                            onOpenFullRecordings = { nav.push(Screen.PlaybackBrowser(current.cameraId)) },
                            onBack = { nav.pop() },
                            deviceManagementViewModel = deviceManagementViewModel,
                        )
                        is Screen.Diagnostics -> DiagnosticsScreen(
                            onBack = { nav.pop() },
                        )
                        is Screen.DeviceManagement -> DeviceManagementScreen(
                            cameraId = current.cameraId,
                            onOpenConfig = { name, label -> nav.push(Screen.ConfigEditor(current.cameraId, name, label)) },
                            onOpenImageSettings = { nav.push(Screen.ImageSettings(current.cameraId)) },
                            onOpenRecordings = { nav.push(Screen.PlaybackBrowser(current.cameraId)) },
                            onBack = { nav.pop() },
                        )
                        is Screen.ConfigEditor -> ConfigEditorScreen(
                            configName = current.configName,
                            label = current.label,
                            onBack = { nav.pop() },
                        )
                        is Screen.ImageSettings -> ImageSettingsScreen(
                            onBack = { nav.pop() },
                        )
                        is Screen.PlaybackBrowser -> PlaybackBrowserScreen(
                            onBack = { nav.pop() },
                        )
                        is Screen.BlePairing -> BlePairingScreen(
                            onPaired = { paired -> nav.replaceTop(Screen.CameraSettings(null, prefillBle = paired)) },
                            onCancel = { nav.pop() },
                        )
                    }
                }
            }
        }
    }
}
