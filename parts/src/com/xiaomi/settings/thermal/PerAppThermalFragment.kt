/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.thermal

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.xiaomi.settings.R
import com.xiaomi.settings.thermal.ThermalController.ThermalProfile
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PerAppThermalFragment : Fragment(R.layout.fragment_per_app_thermal) {

    data class AppEntry(
        val packageName: String,
        val label: String,
        val icon: Drawable,
        val isSystem: Boolean,
        val isLauncher: Boolean,
        val profile: ThermalProfile?,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var loadExecutor: ExecutorService? = null

    private var allApps: List<AppEntry> = emptyList()
    private var searchQuery: String = ""
    private var showSystemApps: Boolean = false

    private lateinit var masterSwitch: Switch
    private lateinit var searchInput: EditText
    private lateinit var showSystemSwitch: Switch
    private lateinit var resetAllButton: Button
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var emptyView: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AppListAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val context = requireContext()
        val thermalSupported = ThermalController.isSupported()

        masterSwitch = view.findViewById(R.id.per_app_master_switch)
        searchInput = view.findViewById(R.id.per_app_search)
        showSystemSwitch = view.findViewById(R.id.per_app_show_system)
        resetAllButton = view.findViewById(R.id.per_app_reset_all)
        loadingSpinner = view.findViewById(R.id.per_app_loading)
        emptyView = view.findViewById(R.id.per_app_empty)
        recyclerView = view.findViewById(R.id.per_app_recycler)

        masterSwitch.isEnabled = thermalSupported
        masterSwitch.isChecked = ThermalController.isPerAppEnabled(context)
        masterSwitch.setOnCheckedChangeListener { _, checked ->
            ThermalController.setPerAppEnabled(requireContext(), checked)
            updateControlsEnabled(checked && thermalSupported)
        }

        showSystemSwitch.setOnCheckedChangeListener { _, checked ->
            showSystemApps = checked
            applyFilter()
        }

        searchInput.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString()?.trim().orEmpty()
                    applyFilter()
                }

                override fun afterTextChanged(s: Editable?) {}
            }
        )

        resetAllButton.setOnClickListener { confirmResetAll() }

        adapter = AppListAdapter { entry -> showProfilePicker(entry) }
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter

        updateControlsEnabled(masterSwitch.isChecked && thermalSupported)
        loadInstalledApps()
    }

    override fun onDestroyView() {
        loadExecutor?.shutdownNow()
        loadExecutor = null
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    private fun updateControlsEnabled(enabled: Boolean) {
        recyclerView.alpha = if (enabled) 1.0f else 0.5f
        resetAllButton.isEnabled = ThermalController.getCustomizedAppCount(requireContext()) > 0
    }

    private fun loadInstalledApps() {
        val appContext = requireContext().applicationContext
        loadingSpinner.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        recyclerView.visibility = View.GONE

        loadExecutor?.shutdownNow()
        val executor = Executors.newSingleThreadExecutor()
        loadExecutor = executor
        executor.execute {
            val pm = appContext.packageManager
            val assigned = ThermalController.getAllAppProfiles(appContext)
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val launcherPkgs =
                pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0L))
                    .mapNotNull { it.activityInfo?.packageName }
                    .toSet()

            val installed = pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
            ThermalController.prunePackages(
                appContext,
                installed.map { it.packageName }.toSet(),
            )
            val entries =
                installed
                    .asSequence()
                    .filter { it.packageName != appContext.packageName }
                    .map { info ->
                        val isSystem =
                            (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                                (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                        val label = runCatching { pm.getApplicationLabel(info).toString() }
                            .getOrDefault(info.packageName)
                        val icon = runCatching { pm.getApplicationIcon(info) }
                            .getOrElse { pm.defaultActivityIcon }
                        AppEntry(
                            packageName = info.packageName,
                            label = label,
                            icon = icon,
                            isSystem = isSystem,
                            isLauncher = info.packageName in launcherPkgs,
                            profile = assigned[info.packageName],
                        )
                    }
                    .toList()

            mainHandler.post {
                if (!isAdded || view == null) return@post
                allApps = entries
                loadingSpinner.visibility = View.GONE
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val query = searchQuery.lowercase(Locale.ROOT)
        val filtered =
            allApps
                .asSequence()
                .filter { entry ->
                    showSystemApps || entry.isLauncher || entry.profile != null
                }
                .filter { entry ->
                    query.isEmpty() ||
                        entry.label.lowercase(Locale.ROOT).contains(query) ||
                        entry.packageName.lowercase(Locale.ROOT).contains(query)
                }
                .sortedWith(
                    compareByDescending<AppEntry> { it.profile != null }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
                )
                .toList()

        adapter.submitList(filtered)
        val hasItems = filtered.isNotEmpty()
        recyclerView.visibility = if (hasItems) View.VISIBLE else View.GONE
        emptyView.visibility = if (hasItems) View.GONE else View.VISIBLE
        resetAllButton.isEnabled = ThermalController.getCustomizedAppCount(requireContext()) > 0
    }

    private fun showProfilePicker(entry: AppEntry) {
        val context = requireContext()
        val profiles = ThermalProfile.entries
        val labels =
            Array(profiles.size + 1) { idx ->
                if (idx == 0) {
                    getString(R.string.thermal_profile_follow_global)
                } else {
                    getString(profiles[idx - 1].labelRes)
                }
            }
        val currentIdx =
            if (entry.profile == null) 0 else profiles.indexOf(entry.profile) + 1

        AlertDialog.Builder(context)
            .setTitle(entry.label)
            .setSingleChoiceItems(labels, currentIdx) { dialog, which ->
                val selected = if (which == 0) null else profiles[which - 1]
                ThermalController.setAppProfile(context, entry.packageName, selected)
                allApps =
                    allApps.map {
                        if (it.packageName == entry.packageName) it.copy(profile = selected) else it
                    }
                applyFilter()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmResetAll() {
        val context = requireContext()
        AlertDialog.Builder(context)
            .setTitle(R.string.per_app_thermal_reset_title)
            .setMessage(R.string.per_app_thermal_reset_message)
            .setPositiveButton(R.string.per_app_thermal_reset_all) { _, _ ->
                ThermalController.clearAllAppProfiles(context)
                allApps = allApps.map { if (it.profile != null) it.copy(profile = null) else it }
                applyFilter()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private class AppListAdapter(
        private val onClick: (AppEntry) -> Unit,
    ) : ListAdapter<AppEntry, AppListAdapter.ViewHolder>(DIFF_CALLBACK) {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.app_icon)
            val label: TextView = view.findViewById(R.id.app_label)
            val pkg: TextView = view.findViewById(R.id.app_package)
            val badge: TextView = view.findViewById(R.id.app_profile_badge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view =
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_per_app_thermal, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = getItem(position)
            val ctx = holder.itemView.context
            holder.icon.setImageDrawable(item.icon)
            holder.label.text = item.label
            holder.pkg.text = item.packageName
            if (item.profile != null) {
                holder.badge.text = ctx.getString(item.profile.labelRes)
                holder.badge.setBackgroundResource(R.drawable.bg_profile_pill)
                holder.badge.setTextColor(
                    ctx.getColor(R.color.settingslib_materialColorOnPrimaryContainer)
                )
            } else {
                holder.badge.text = ctx.getString(R.string.thermal_profile_follow_global)
                holder.badge.background = null
                holder.badge.setTextColor(
                    ctx.getColor(R.color.settingslib_materialColorOnSurfaceVariant)
                )
            }
            holder.itemView.setOnClickListener { onClick(item) }
        }

        companion object {
            private val DIFF_CALLBACK =
                object : DiffUtil.ItemCallback<AppEntry>() {
                    override fun areItemsTheSame(oldItem: AppEntry, newItem: AppEntry): Boolean =
                        oldItem.packageName == newItem.packageName

                    override fun areContentsTheSame(oldItem: AppEntry, newItem: AppEntry): Boolean =
                        oldItem.label == newItem.label && oldItem.profile == newItem.profile
                }
        }
    }
}
