/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import com.xiaomi.settings.display.AodBrightnessController
import com.xiaomi.settings.display.AodBrightnessController.Mode as AodBrightnessMode
import com.xiaomi.settings.display.DisplayFeatureController
import com.xiaomi.settings.speaker.SpeakerCleaner
import com.xiaomi.settings.thermal.PerAppThermalActivity
import com.xiaomi.settings.thermal.ThermalController
import com.xiaomi.settings.thermal.ThermalController.ThermalProfile
import com.xiaomi.settings.touch.TouchPollingController
import com.xiaomi.settings.touch.TouchTuningActivity
import com.xiaomi.settings.touch.TouchTuningController

class XiaomiSettingsFragment :
    SettingsBasePreferenceFragment(),
    Preference.OnPreferenceChangeListener,
    Preference.OnPreferenceClickListener {

    private var aodBrightnessPref: ListPreference? = null
    private var trueTonePref: SwitchPreferenceCompat? = null
    private var thermalProfilePref: ListPreference? = null
    private var perAppThermalPref: Preference? = null
    private var smartChargingPref: SwitchPreferenceCompat? = null
    private var touchPollingPref: SwitchPreferenceCompat? = null
    private var touchTuningPref: Preference? = null
    private var clearSpeakerPref: Preference? = null

    private lateinit var speakerCleaner: SpeakerCleaner

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.xiaomi_settings, rootKey)
        speakerCleaner = SpeakerCleaner(requireContext().applicationContext)

        val context = requireContext()
        val thermalSupported = ThermalController.isSupported()

        aodBrightnessPref =
            findPreference<ListPreference>(KEY_AOD_BRIGHTNESS)?.apply {
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                value = AodBrightnessController.getMode(context).prefValue
                onPreferenceChangeListener = this@XiaomiSettingsFragment
            }

        trueTonePref =
            findPreference<SwitchPreferenceCompat>(KEY_TRUE_TONE)?.apply {
                isChecked = DisplayFeatureController.isTrueToneEnabled(context)
                onPreferenceChangeListener = this@XiaomiSettingsFragment
            }

        thermalProfilePref =
            findPreference<ListPreference>(KEY_THERMAL_PROFILE)?.apply {
                isEnabled = thermalSupported
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                value = ThermalController.getSelectedProfile(context).prefValue
                onPreferenceChangeListener = this@XiaomiSettingsFragment
            }

        perAppThermalPref =
            findPreference<Preference>(KEY_PER_APP_THERMAL)?.apply {
                isEnabled = thermalSupported
                onPreferenceClickListener = this@XiaomiSettingsFragment
            }

        smartChargingPref =
            findPreference<SwitchPreferenceCompat>(KEY_SMART_CHARGING_THERMAL)?.apply {
                isEnabled = thermalSupported
                isChecked = ThermalController.isSmartChargingThermalEnabled(context)
                onPreferenceChangeListener = this@XiaomiSettingsFragment
            }

        touchPollingPref =
            findPreference<SwitchPreferenceCompat>(KEY_HIGH_TOUCH_POLLING)?.apply {
                isChecked = TouchPollingController.isEnabled(context)
                onPreferenceChangeListener = this@XiaomiSettingsFragment
            }

        touchTuningPref =
            findPreference<Preference>(KEY_TOUCH_TUNING)?.apply {
                onPreferenceClickListener = this@XiaomiSettingsFragment
            }

        clearSpeakerPref =
            findPreference<Preference>(KEY_CLEAR_SPEAKER)?.apply {
                onPreferenceClickListener = this@XiaomiSettingsFragment
            }

        updateDynamicSummaries()
    }

    override fun onResume() {
        super.onResume()
        val context = requireContext()
        aodBrightnessPref?.value = AodBrightnessController.getMode(context).prefValue
        trueTonePref?.isChecked = DisplayFeatureController.isTrueToneEnabled(context)
        thermalProfilePref?.value = ThermalController.getSelectedProfile(context).prefValue
        smartChargingPref?.isChecked = ThermalController.isSmartChargingThermalEnabled(context)
        touchPollingPref?.isChecked = TouchPollingController.isEnabled(context)
        updateDynamicSummaries()
        if (!speakerCleaner.isRunning) {
            clearSpeakerPref?.setSummary(R.string.clear_speaker_summary)
        }
    }

    override fun onPause() {
        speakerCleaner.stop()
        super.onPause()
    }

    override fun onDestroy() {
        speakerCleaner.stop()
        super.onDestroy()
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        val context = requireContext()
        return when (preference.key) {
            KEY_AOD_BRIGHTNESS -> {
                val mode = AodBrightnessMode.fromPrefValue(newValue as? String)
                AodBrightnessController.setMode(context, mode)
                true
            }
            KEY_TRUE_TONE -> {
                val enabled = newValue as? Boolean ?: return false
                DisplayFeatureController.setTrueToneEnabled(context, enabled)
                true
            }
            KEY_THERMAL_PROFILE -> {
                val profile = ThermalProfile.fromGlobalPrefValue(newValue as? String)
                ThermalController.setSelectedProfile(context, profile)
                true
            }
            KEY_SMART_CHARGING_THERMAL -> {
                val enabled = newValue as? Boolean ?: return false
                ThermalController.setSmartChargingThermalEnabled(context, enabled)
                true
            }
            KEY_HIGH_TOUCH_POLLING -> {
                val enabled = newValue as? Boolean ?: return false
                TouchPollingController.setEnabled(context, enabled)
                true
            }
            else -> false
        }
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        return when (preference.key) {
            KEY_PER_APP_THERMAL -> {
                startActivity(Intent(requireContext(), PerAppThermalActivity::class.java))
                true
            }
            KEY_TOUCH_TUNING -> {
                startActivity(Intent(requireContext(), TouchTuningActivity::class.java))
                true
            }
            KEY_CLEAR_SPEAKER -> {
                toggleSpeakerCleaner()
                true
            }
            else -> false
        }
    }

    private fun updateDynamicSummaries() {
        val context = requireContext()
        val perAppEnabled = ThermalController.isPerAppEnabled(context)
        val count = ThermalController.getCustomizedAppCount(context)
        perAppThermalPref?.summary =
            when {
                !perAppEnabled -> getString(R.string.per_app_thermal_summary_off)
                count == 0 -> getString(R.string.per_app_thermal_summary_none)
                else ->
                    resources.getQuantityString(
                        R.plurals.per_app_thermal_summary_count,
                        count,
                        count,
                    )
            }

        touchTuningPref?.summary =
            getString(
                if (TouchTuningController.isGameModeEnabled(context)) {
                    R.string.touch_tuning_summary_on
                } else {
                    R.string.touch_tuning_summary_off
                }
            )
    }

    private fun toggleSpeakerCleaner() {
        if (speakerCleaner.isRunning) {
            speakerCleaner.stop()
            clearSpeakerPref?.setSummary(R.string.clear_speaker_summary)
            return
        }
        val result =
            speakerCleaner.start(
                onTick = { remaining ->
                    if (isAdded && speakerCleaner.isRunning) {
                        clearSpeakerPref?.summary =
                            getString(R.string.clear_speaker_running_summary, remaining)
                    }
                },
                onComplete = {
                    if (isAdded) {
                        clearSpeakerPref?.setSummary(R.string.clear_speaker_summary)
                    }
                },
            )
        if (result == SpeakerCleaner.StartResult.BLOCKED_BY_HEADSET) {
            Toast.makeText(
                requireContext(),
                R.string.clear_speaker_headset_blocked,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    companion object {
        private const val KEY_AOD_BRIGHTNESS = "aod_brightness"
        private const val KEY_TRUE_TONE = "true_tone"
        private const val KEY_THERMAL_PROFILE = "thermal_profile"
        private const val KEY_PER_APP_THERMAL = "per_app_thermal"
        private const val KEY_SMART_CHARGING_THERMAL = "smart_charging_thermal"
        private const val KEY_HIGH_TOUCH_POLLING = "high_touch_polling"
        private const val KEY_TOUCH_TUNING = "touch_tuning"
        private const val KEY_CLEAR_SPEAKER = "clear_speaker"
    }
}
