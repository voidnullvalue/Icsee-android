package com.voidnullvalue.icseelocal.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import com.voidnullvalue.icseelocal.ui.theme.IcseeTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.voidnullvalue.icseelocal.ui.blepairing.BlePairingScreen
import com.voidnullvalue.icseelocal.ui.cameralist.CameraListScreen
import com.voidnullvalue.icseelocal.ui.devicemanagement.ConfigEditorScreen
import com.voidnullvalue.icseelocal.ui.devicemanagement.DeviceManagementViewModel
import com.voidnullvalue.icseelocal.ui.devicemanagement.ImageSettingsScreen
import com.voidnullvalue.icseelocal.ui.devicemanagement.PlaybackBrowserScreen
import com.voidnullvalue.icseelocal.ui.devicemanagement.DeviceManagementScreen
import com.voidnullvalue.icseelocal.ui.diagnostics.DiagnosticsScreen
import com.voidnullvalue.icseelocal.ui.live.LiveControlScreen
import com.voidnullvalue.icseelocal.ui.live.LiveControlViewModel
import com.voidnullvalue.icseelocal.ui.settings.CameraSettingsScreen

/**
 * Manual, single-Activity navigation (no Navigation-Compose dependency --
 * four screens with no deep-linking needs don't warrant it). All state
 * lives in per-screen ViewModels scoped to this Activity by default Compose
 * `viewModel()` behavior.
 */
class MainActivity : ComponentActivity() {
    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IcseeTheme {
                Surface {
                    var screen by remember { mutableStateOf<Screen>(Screen.CameraList) }

                    // Both control-session ViewModels are Activity-scoped and outlive any
                    // one screen (navigating "back" only changes which screen is drawn --
                    // it never clears a ViewModel), so something has to own the decision of
                    // when their DVRIP sessions should actually exist. That's here: each
                    // family's session is connected only while `screen` is a member of that
                    // family, and torn down the instant navigation leaves it -- this camera's
                    // firmware is touchy about login *rate*, so the app must never let a
                    // session it isn't looking at anymore quietly sit there reconnecting on
                    // its own schedule. `viewModel()` here resolves to the SAME instances the
                    // leaf screens below retrieve (same ViewModelStoreOwner, same class).
                    val liveControlViewModel: LiveControlViewModel = viewModel()
                    val deviceManagementViewModel: DeviceManagementViewModel = viewModel()

                    val liveFamilyCameraId = when (val s = screen) {
                        is Screen.LiveControl -> s.cameraId
                        is Screen.Diagnostics -> s.cameraId
                        else -> null
                    }
                    val deviceManagementFamilyCameraId = when (val s = screen) {
                        is Screen.DeviceManagement -> s.cameraId
                        is Screen.ConfigEditor -> s.cameraId
                        is Screen.ImageSettings -> s.cameraId
                        is Screen.PlaybackBrowser -> s.cameraId
                        else -> null
                    }

                    // These keys only change value on a genuine transition into/out of the
                    // family (bouncing between e.g. LiveControl and Diagnostics for the same
                    // camera leaves the key unchanged, so the session is left alone), so each
                    // effect body runs exactly once per real focus change.
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
                            onOpenCamera = { id -> screen = Screen.LiveControl(id) },
                            onAddManual = { screen = Screen.CameraSettings(null) },
                            onAddDiscovered = { beacon -> screen = Screen.CameraSettings(null, prefillBeacon = beacon) },
                            onPairBluetooth = { screen = Screen.BlePairing },
                            onOpenSettings = { id -> screen = Screen.CameraSettings(id) },
                        )
                        is Screen.CameraSettings -> CameraSettingsScreen(
                            cameraId = current.cameraId,
                            prefillBeacon = current.prefillBeacon,
                            prefillBle = current.prefillBle,
                            onDone = { screen = Screen.CameraList },
                        )
                        is Screen.LiveControl -> LiveControlScreen(
                            cameraId = current.cameraId,
                            onOpenDiagnostics = { screen = Screen.Diagnostics(current.cameraId) },
                            onOpenDeviceManagement = { screen = Screen.DeviceManagement(current.cameraId) },
                            onBack = { screen = Screen.CameraList },
                        )
                        is Screen.Diagnostics -> DiagnosticsScreen(
                            onBack = { screen = Screen.LiveControl(current.cameraId) },
                        )
                        is Screen.DeviceManagement -> DeviceManagementScreen(
                            cameraId = current.cameraId,
                            onOpenConfig = { name, label -> screen = Screen.ConfigEditor(current.cameraId, name, label) },
                            onOpenImageSettings = { screen = Screen.ImageSettings(current.cameraId) },
                            onOpenRecordings = { screen = Screen.PlaybackBrowser(current.cameraId) },
                            onBack = { screen = Screen.LiveControl(current.cameraId) },
                        )
                        is Screen.ConfigEditor -> ConfigEditorScreen(
                            configName = current.configName,
                            label = current.label,
                            onBack = { screen = Screen.DeviceManagement(current.cameraId) },
                        )
                        is Screen.ImageSettings -> ImageSettingsScreen(
                            onBack = { screen = Screen.DeviceManagement(current.cameraId) },
                        )
                        is Screen.PlaybackBrowser -> PlaybackBrowserScreen(
                            onBack = { screen = Screen.DeviceManagement(current.cameraId) },
                        )
                        is Screen.BlePairing -> BlePairingScreen(
                            onPaired = { paired -> screen = Screen.CameraSettings(null, prefillBle = paired) },
                            onCancel = { screen = Screen.CameraList },
                        )
                    }
                }
            }
        }
    }
}
