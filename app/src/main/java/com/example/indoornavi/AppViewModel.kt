package com.example.indoornavi

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.io.IOException

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")
val THEME_PRESET_KEY = stringPreferencesKey("theme_preset")
val AUTO_SCAN_KEY = booleanPreferencesKey("auto_scan")
val ADVANCED_MODE_KEY = booleanPreferencesKey("advanced_mode")
val DOCK_WIDTH_KEY = floatPreferencesKey("dock_width")
val DOCK_ALIGNMENT_KEY = stringPreferencesKey("dock_alignment")
val COLLECTION_360_KEY = booleanPreferencesKey("collection_360")
val DARK_MODE_KEY = stringPreferencesKey("dark_mode_config") // 🌟 新增深色模式存储键

enum class ThemePreset(val title: String, val color: Color) {
    DYNAMIC("动态取色", Color.Transparent), BILIBILI("Bilibili Pink", Color(0xFFFF6699)), MIKU("Miku Green", Color(0xFF39C5BB)),
    BEYERDYNAMIC("Beyerdynamic Orange", Color(0xFFFF6600)), INTEL("Intel Blue", Color(0xFF0071C5)), NVIDIA("Nvidia Green", Color(0xFF76B900)), SAMSUNG("Samsung Blue", Color(0xFF1429A0))
}
enum class DockAlignment(val title: String) { LEFT("靠左"), CENTER("居中"), RIGHT("靠右") }
enum class DarkModeConfig(val title: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") } // 🌟 新增枚举

class SharedViewModel(application: Application) : AndroidViewModel(application) {
    var selectedDevices by mutableStateOf(mapOf<String, BeaconDevice>())
    var recordedPoints by mutableStateOf(listOf<ReferencePoint>()); private set

    var currentThemePreset by mutableStateOf(ThemePreset.DYNAMIC); private set
    var autoScan by mutableStateOf(true); private set
    var isAdvancedModeEnabled by mutableStateOf(false); private set
    var is360CollectionModeEnabled by mutableStateOf(false); private set

    var dockWidthRatio by mutableFloatStateOf(0.7f); private set
    var dockAlignment by mutableStateOf(DockAlignment.CENTER); private set
    var darkModeConfig by mutableStateOf(DarkModeConfig.SYSTEM); private set // 🌟 新增深色模式状态

    var isCollectingMode by mutableStateOf(false)
    var spaceWidth by mutableStateOf("10")
    var spaceLength by mutableStateOf("10")
    var gridSpacing by mutableStateOf("2")

    private val fingerprintDao = AppDatabase.getDatabase(application).fingerprintDao()

    init {
        viewModelScope.launch {
            getApplication<Application>().dataStore.data
                .catch { exception -> if (exception is IOException) emit(emptyPreferences()) else throw exception }
                .collect { preferences ->
                    currentThemePreset = runCatching { ThemePreset.valueOf(preferences[THEME_PRESET_KEY] ?: ThemePreset.DYNAMIC.name) }.getOrDefault(ThemePreset.DYNAMIC)
                    autoScan = preferences[AUTO_SCAN_KEY] ?: true
                    isAdvancedModeEnabled = preferences[ADVANCED_MODE_KEY] ?: false
                    is360CollectionModeEnabled = preferences[COLLECTION_360_KEY] ?: false
                    dockWidthRatio = preferences[DOCK_WIDTH_KEY] ?: 0.7f
                    dockAlignment = runCatching { DockAlignment.valueOf(preferences[DOCK_ALIGNMENT_KEY] ?: DockAlignment.CENTER.name) }.getOrDefault(DockAlignment.CENTER)
                    darkModeConfig = runCatching { DarkModeConfig.valueOf(preferences[DARK_MODE_KEY] ?: DarkModeConfig.SYSTEM.name) }.getOrDefault(DarkModeConfig.SYSTEM) // 读取
                }
        }
        viewModelScope.launch(Dispatchers.IO) { fingerprintDao.getAllFingerprintsStream().collect { entities -> recordedPoints = entities.map { it.toDomainModel() } } }
    }

    fun updateRecordedPoints(newList: List<ReferencePoint>) {
        viewModelScope.launch(Dispatchers.IO) {
            if (newList.isEmpty()) { fingerprintDao.clearAll() } else {
                val currentIds = recordedPoints.map { it.id }.toSet(); val newIds = newList.map { it.id }.toSet()
                (currentIds - newIds).forEach { id -> recordedPoints.find { it.id == id }?.toEntity()?.let { fingerprintDao.deleteFingerprint(it) } }
                newList.forEach { fingerprintDao.insertFingerprint(it.toEntity()) }
            }
        }
    }

    fun changeTheme(preset: ThemePreset) { currentThemePreset = preset; viewModelScope.launch { getApplication<Application>().dataStore.edit { it[THEME_PRESET_KEY] = preset.name } } }
    fun setAutoScanState(enabled: Boolean) { autoScan = enabled; viewModelScope.launch { getApplication<Application>().dataStore.edit { it[AUTO_SCAN_KEY] = enabled } } }
    fun setAdvancedMode(enabled: Boolean) { isAdvancedModeEnabled = enabled; viewModelScope.launch { getApplication<Application>().dataStore.edit { it[ADVANCED_MODE_KEY] = enabled } } }
    fun set360CollectionMode(enabled: Boolean) { is360CollectionModeEnabled = enabled; viewModelScope.launch { getApplication<Application>().dataStore.edit { it[COLLECTION_360_KEY] = enabled } } }
    fun updateDockAlignment(alignment: DockAlignment) { dockAlignment = alignment; viewModelScope.launch { getApplication<Application>().dataStore.edit { it[DOCK_ALIGNMENT_KEY] = alignment.name } } }
    fun setDockWidth(ratio: Float) { dockWidthRatio = ratio; viewModelScope.launch { getApplication<Application>().dataStore.edit { it[DOCK_WIDTH_KEY] = ratio } } }
    fun updateDarkModeConfig(config: DarkModeConfig) { darkModeConfig = config; viewModelScope.launch { getApplication<Application>().dataStore.edit { it[DARK_MODE_KEY] = config.name } } }}