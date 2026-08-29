package com.voidnullvalue.icseelocal.ui

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.voidnullvalue.icseelocal.ui.grid.CameraGridScreen
import com.voidnullvalue.icseelocal.ui.live.LiveControlViewModel
import com.voidnullvalue.icseelocal.ui.live.LiveControlWithAvTalk
import com.voidnullvalue.icseelocal.ui.settings.CameraSettingsScreen
import com.voidnullvalue.icseelocal.ui.theme.IcseeTheme

/**
 * Single-Activity host with an explicit [NavStack] so system back and toolbar
 * back share one pop path. Session families (Live / DeviceManagement) still
 * connect only while a matching screen is on the stack.
 *
 * Edge-to-edge + optional PiP when the user backgrounds a live stream.
 */
class MainActivity : ComponentActivity() {

    @Volatile
    private var pipEligible: Boolean = false

    fun setPipEligible(eligible: Boolean) {
        pipEligible = eligible
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                setPictureInPictureParams(buildPipParams())
            } catch (_: IllegalStateException) {
                // Activity not ready for PiP params yet (pre-resume / finishing).
            } catch (_: IllegalArgumentException) {
                // Device rejected aspect ratio / PiP params.
            }
        }
    }

    private fun buildPipParams(): PictureInPictureParams {
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
    }

    fun enterPipIfEligible(): Boolean {
        if (!pipEligible || Build.VERSION.SDK_INT < 26) return false
        return try {
            enterPictureInPictureMode(buildPipParams())
        } catch (_: IllegalStateException) {
            false
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPipIfEligible()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    }

    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            IcseeTheme {
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
                        onOpenGrid = { nav.push(Screen.CameraGrid) },
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
                    is Screen.LiveControl -> LiveControlWithAvTalk(
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
                        liveControlViewModel = liveControlViewModel,
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
                    is Screen.CameraGrid -> CameraGridScreen(
                        onOpenLive = { id -> nav.push(Screen.LiveControl(id)) },
                        onBack = { nav.pop() },
                    )
                }
            }
        }
    }
}
