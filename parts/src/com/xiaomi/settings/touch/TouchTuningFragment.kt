/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.touch

import android.os.Bundle
import androidx.preference.Preference
import com.android.settingslib.widget.MainSwitchPreference
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import com.android.settingslib.widget.SliderPreference
import com.xiaomi.settings.R

class TouchTuningFragment :
    SettingsBasePreferenceFragment(),
    Preference.OnPreferenceChangeListener,
    Preference.OnPreferenceClickListener {

    private var gameModeSwitch: MainSwitchPreference? = null
    private var upThresholdSlider: SliderPreference? = null
    private var toleranceSlider: SliderPreference? = null
    private var edgeFilterSlider: SliderPreference? = null
    private var resetPref: Preference? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.touch_tuning, rootKey)
        val context = requireContext()
        val enabled = TouchTuningController.isGameModeEnabled(context)

        gameModeSwitch =
            findPreference<MainSwitchPreference>(KEY_GAME_MODE)?.apply {
                isChecked = enabled
                onPreferenceChangeListener = this@TouchTuningFragment
            }

        upThresholdSlider =
            findPreference<SliderPreference>(KEY_UP_THRESHOLD)?.apply {
                configureSlider(
                    mode = TouchFeatureWrapper.MODE_TOUCH_UP_THRESHOLD,
                    current = TouchTuningController.getUpThreshold(context),
                    enabled = enabled,
                )
            }

        toleranceSlider =
            findPreference<SliderPreference>(KEY_TOLERANCE)?.apply {
                configureSlider(
                    mode = TouchFeatureWrapper.MODE_TOUCH_TOLERANCE,
                    current = TouchTuningController.getTolerance(context),
                    enabled = enabled,
                )
            }

        edgeFilterSlider =
            findPreference<SliderPreference>(KEY_EDGE_FILTER)?.apply {
                configureSlider(
                    mode = TouchFeatureWrapper.MODE_TOUCH_EDGE_FILTER,
                    current = TouchTuningController.getEdgeFilter(context),
                    enabled = enabled,
                )
            }

        resetPref =
            findPreference<Preference>(KEY_RESET)?.apply {
                onPreferenceClickListener = this@TouchTuningFragment
            }
    }

    override fun onResume() {
        super.onResume()
        syncUi()
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        val context = requireContext()
        return when (preference.key) {
            KEY_GAME_MODE -> {
                val enabled = newValue as? Boolean ?: return false
                TouchTuningController.setGameModeEnabled(context, enabled)
                setSlidersEnabled(enabled)
                true
            }
            KEY_UP_THRESHOLD -> {
                val value = (newValue as? Int) ?: return false
                TouchTuningController.setUpThreshold(context, value)
                true
            }
            KEY_TOLERANCE -> {
                val value = (newValue as? Int) ?: return false
                TouchTuningController.setTolerance(context, value)
                true
            }
            KEY_EDGE_FILTER -> {
                val value = (newValue as? Int) ?: return false
                TouchTuningController.setEdgeFilter(context, value)
                true
            }
            else -> false
        }
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        if (preference.key != KEY_RESET) return false
        TouchTuningController.requestResetDefaults(requireContext())
        syncUi()
        return true
    }

    private fun SliderPreference.configureSlider(mode: Int, current: Int, enabled: Boolean) {
        val range = TouchTuningController.getModeRange(mode)
        min = 0
        max = range.max
        min = range.min
        sliderIncrement = 1
        setTickVisible(true)
        setHapticFeedbackMode(SliderPreference.HAPTIC_FEEDBACK_MODE_ON_TICKS)
        setShowSliderValue(true)
        setUpdatesContinuously(false)
        setTextStart(R.string.touch_tuning_low)
        setTextEnd(R.string.touch_tuning_high)
        value = current.coerceIn(range.min, range.max)
        isEnabled = enabled
        onPreferenceChangeListener = this@TouchTuningFragment
    }

    private fun setSlidersEnabled(enabled: Boolean) {
        upThresholdSlider?.isEnabled = enabled
        toleranceSlider?.isEnabled = enabled
        edgeFilterSlider?.isEnabled = enabled
    }

    private fun syncUi() {
        val context = requireContext()
        val enabled = TouchTuningController.isGameModeEnabled(context)
        gameModeSwitch?.isChecked = enabled
        setSlidersEnabled(enabled)
        upThresholdSlider?.value = TouchTuningController.getUpThreshold(context)
        toleranceSlider?.value = TouchTuningController.getTolerance(context)
        edgeFilterSlider?.value = TouchTuningController.getEdgeFilter(context)
    }

    companion object {
        private const val KEY_GAME_MODE = "touch_game_mode"
        private const val KEY_UP_THRESHOLD = "touch_up_threshold"
        private const val KEY_TOLERANCE = "touch_tolerance"
        private const val KEY_EDGE_FILTER = "touch_edge_filter"
        private const val KEY_RESET = "touch_tuning_reset"
    }
}
