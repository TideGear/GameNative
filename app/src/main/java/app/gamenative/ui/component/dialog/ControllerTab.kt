package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.data.TouchGestureConfig
import app.gamenative.ui.component.settings.SettingsListDropdown
import app.gamenative.ui.theme.settingsTileColors
import app.gamenative.ui.theme.settingsTileColorsAlt
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsSwitch
import com.alorma.compose.settings.ui.SettingsMenuLink
import com.winlator.container.Container
import kotlin.math.roundToInt

@Composable
fun ControllerTabContent(state: ContainerConfigState, default: Boolean) {
    val config = state.config.value
    var showGestureDialog by remember { mutableStateOf(false) }

    SettingsGroup() {
        if (!default) {
            SettingsSwitch(
                colors = settingsTileColorsAlt(),
                title = { Text(text = stringResource(R.string.use_sdl_api)) },
                state = config.sdlControllerAPI,
                onCheckedChange = { state.config.value = config.copy(sdlControllerAPI = it) },
            )
        }
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.use_steam_input)) },
            state = config.useSteamInput,
            onCheckedChange = { state.config.value = config.copy(useSteamInput = it) },
        )
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.enable_xinput_api)) },
            state = config.enableXInput,
            onCheckedChange = { state.config.value = config.copy(enableXInput = it) },
        )
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.enable_directinput_api)) },
            state = config.enableDInput,
            onCheckedChange = { state.config.value = config.copy(enableDInput = it) },
        )
        SettingsListDropdown(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.directinput_mapper_type)) },
            value = if (config.dinputMapperType == 1.toByte()) 0 else 1,
            items = listOf("Standard", "XInput Mapper"),
            onItemSelected = { index ->
                state.config.value = config.copy(dinputMapperType = if (index == 0) 1 else 2)
            },
        )
        val vibrationModes = listOf("Off", "Controller", "Device", "Both")
        val vibrationModeValues = listOf("off", "controller", "device", "both")
        val vibrationModeIndex = vibrationModeValues.indexOf(config.vibrationMode).coerceAtLeast(0)
        SettingsListDropdown(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.vibration_mode)) },
            value = vibrationModeIndex,
            items = vibrationModes,
            onItemSelected = { index ->
                state.config.value = config.copy(vibrationMode = vibrationModeValues[index])
            },
        )
        if (config.vibrationMode != "off") {
            var intensitySlider by remember(config.vibrationIntensity) {
                mutableIntStateOf(config.vibrationIntensity.coerceIn(0, 100))
            }
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(text = stringResource(R.string.vibration_intensity))
                Slider(
                    value = intensitySlider.toFloat(),
                    onValueChange = { newValue ->
                        val clamped = newValue.roundToInt().coerceIn(0, 100)
                        intensitySlider = clamped
                        state.config.value = config.copy(vibrationIntensity = clamped)
                    },
                    valueRange = 0f..100f,
                )
                Text(text = "$intensitySlider%")
            }
        }
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.disable_mouse_input)) },
            state = config.disableMouseInput,
            onCheckedChange = { state.config.value = config.copy(disableMouseInput = it) },
        )
        SettingsMenuLink(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.touchscreen_mode)) },
            subtitle = { Text(text = stringResource(R.string.touchscreen_mode_description)) },
            onClick = { state.config.value = config.copy(touchscreenMode = !config.touchscreenMode) },
            action = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showGestureDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.gesture_settings),
                        )
                    }
                    Switch(
                        checked = config.touchscreenMode,
                        onCheckedChange = { state.config.value = config.copy(touchscreenMode = it) },
                    )
                }
            },
        )
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.shooter_mode_toggle)) },
            subtitle = { Text(text = stringResource(R.string.shooter_mode_toggle_description)) },
            state = config.shooterMode,
            onCheckedChange = { state.config.value = config.copy(shooterMode = it) },
        )
        SettingsListDropdown(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.external_display_input)) },
            subtitle = { Text(text = stringResource(R.string.external_display_input_subtitle)) },
            value = state.externalDisplayModeIndex.value,
            items = state.externalDisplayModes,
            onItemSelected = { index ->
                state.externalDisplayModeIndex.value = index
                state.config.value = config.copy(
                    externalDisplayMode = when (index) {
                        1 -> Container.EXTERNAL_DISPLAY_MODE_TOUCHPAD
                        2 -> Container.EXTERNAL_DISPLAY_MODE_KEYBOARD
                        3 -> Container.EXTERNAL_DISPLAY_MODE_HYBRID
                        else -> Container.EXTERNAL_DISPLAY_MODE_OFF
                    },
                )
            },
        )
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.external_display_swap)) },
            subtitle = { Text(text = stringResource(R.string.external_display_swap_subtitle)) },
            state = config.externalDisplaySwap,
            onCheckedChange = { state.config.value = config.copy(externalDisplaySwap = it) },
        )
    }

    // Gesture settings full-screen dialog
    if (showGestureDialog) {
        val gestureConfig = remember(config.gestureConfig) {
            TouchGestureConfig.fromJson(config.gestureConfig)
        }
        TouchGestureSettingsDialog(
            gestureConfig = gestureConfig,
            onDismiss = { showGestureDialog = false },
            onSave = { updatedGesture ->
                // Read the latest config to avoid overwriting concurrent changes
                val latest = state.config.value
                state.config.value = latest.copy(gestureConfig = updatedGesture.toJson())
                showGestureDialog = false
            },
        )
    }
}
