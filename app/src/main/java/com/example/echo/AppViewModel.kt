package com.example.echo

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
val DARK_MODE_KEY = stringPreferencesKey("dark_mode_config")
val MAP_FOLLOW_KEY = booleanPreferencesKey("map_follow")

// 🌟 新增：过滤无名设备的持久化 Key
val IGNORE_UNNAMED_KEY = booleanPreferencesKey("ignore_unnamed")

enum class ThemePreset(val title: String, val color: Color) {
    DYNAMIC("动态取色", Color.Transparent), BILIBILI("Bilibili Pink", Color(0xFFFF6699)), MIKU("Miku Green", Color(0xFF39C5BB)),
    BEYERDYNAMIC("Beyer Orange", Color(0xFFFF6600)), INTEL("Intel Blue", Color(0xFF0071C5)), NVIDIA("Nvidia Green", Color(0xFF76B900)), SAMSUNG("Samsung Blue", Color(0xFF1429A0))
}
enum class DockAlignment(val title: String) { LEFT("靠左"), CENTER("居中"), RIGHT("靠右") }
enum class DarkModeConfig(val title: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }

enum class InteractionState(val title: String) {
    EVALUATION_MODE("误差测绘"),
    NAVIGATION_MODE("定位导航"),
    OBSTACLE_MODE("避障编辑"),
    BENCHMARK_MODE("基准测试")
}

class SharedViewModel(application: Application) : AndroidViewModel(application) {
    var selectedDevices by mutableStateOf(mapOf<String, BeaconDevice>())
    var recordedPoints by mutableStateOf(listOf<ReferencePoint>()); private set

    var obstacles by mutableStateOf(listOf<ObstacleEntity>()); private set
    var navigationTarget by mutableStateOf<Point?>(null)
    var currentPath by mutableStateOf<List<Point>>(emptyList())

    var currentInteractionState by mutableStateOf(InteractionState.NAVIGATION_MODE)

    var isBenchmarking by mutableStateOf(false)

    // 🌟 新增：为实验三（轨迹录制）和实验四（上下文标签）准备的全局状态
    var isContinuousLogging by mutableStateOf(false)
    var currentEnvLabel by mutableStateOf("开阔静止")
    val envLabels = listOf("开阔静止", "开阔走动", "盲区静止", "盲区走动")

    var currentThemePreset by mutableStateOf(ThemePreset.DYNAMIC);
    var autoScan by mutableStateOf(true); private set
    var isAdvancedModeEnabled by mutableStateOf(false);
    var is360CollectionModeEnabled by mutableStateOf(false); private set
    var isMapFollowingModeEnabled by mutableStateOf(false); private set
    var darkModeConfig by mutableStateOf(DarkModeConfig.SYSTEM);

    // 🌟 新增：过滤无名设备的状态
    var isIgnoreUnnamedEnabled by mutableStateOf(false); private set

    var isCollectingMode by mutableStateOf(false)
    var spaceWidth by mutableStateOf("10")
    var spaceLength by mutableStateOf("10")
    var gridSpacing by mutableStateOf("2")

    private val fingerprintDao = AppDatabase.getDatabase(application).fingerprintDao()
    private val obstacleDao = AppDatabase.getDatabase(application).obstacleDao()

    init {
        viewModelScope.launch {
            getApplication<Application>().dataStore.data
                .catch { exception -> if (exception is IOException) emit(emptyPreferences()) else throw exception }
                .collect { preferences ->
                    currentThemePreset = runCatching { ThemePreset.valueOf(preferences[THEME_PRESET_KEY] ?: ThemePreset.DYNAMIC.name) }.getOrDefault(ThemePreset.DYNAMIC)
                    autoScan = preferences[AUTO_SCAN_KEY] ?: true
                    isAdvancedModeEnabled = preferences[ADVANCED_MODE_KEY] ?: false
                    is360CollectionModeEnabled = preferences[COLLECTION_360_KEY] ?: false
                    isMapFollowingModeEnabled = preferences[MAP_FOLLOW_KEY] ?: false
                    darkModeConfig = runCatching { DarkModeConfig.valueOf(preferences[DARK_MODE_KEY] ?: DarkModeConfig.SYSTEM.name) }.getOrDefault(DarkModeConfig.SYSTEM)

                    // 🌟 初始化过滤状态
                    isIgnoreUnnamedEnabled = preferences[IGNORE_UNNAMED_KEY] ?: false
                }
        }

        viewModelScope.launch(Dispatchers.IO) { fingerprintDao.getAllFingerprintsStream().collect { entities -> recordedPoints = entities.map { it.toDomainModel() } } }
        viewModelScope.launch(Dispatchers.IO) { obstacleDao.getAllObstaclesStream().collect { entities -> obstacles = entities } }
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

    fun toggleObstacle(point: Point, gridResolution: Double, forceErase: Boolean? = null) {
        val c = (point.x / gridResolution).toInt()
        val r = (point.y / gridResolution).toInt()
        val id = "OBS_${c}_${r}"
        val exists = obstacles.any { it.id == id }

        val shouldErase = forceErase ?: exists

        viewModelScope.launch(Dispatchers.IO) {
            if (shouldErase && exists) {
                obstacles.find { it.id == id }?.let { obstacleDao.deleteObstacle(it) }
                obstacles = obstacles.filter { it.id != id }
            } else if (!shouldErase && !exists) {
                val alignedX = c * gridResolution
                val alignedY = r * gridResolution
                val newObs = ObstacleEntity(id, alignedX, alignedY)
                obstacleDao.insertObstacle(newObs)
                obstacles = obstacles + newObs
            }
        }
    }

    fun clearAllObstacles() {
        viewModelScope.launch(Dispatchers.IO) {
            obstacleDao.clearAllObstacles()
            obstacles = emptyList()
        }
    }

    fun changeTheme(preset: ThemePreset) { currentThemePreset = preset; viewModelScope.launch { getApplication<Application>().dataStore.edit { it[THEME_PRESET_KEY] = preset.name } } }
    fun setAutoScanState(enabled: Boolean) { autoScan = enabled; viewModelScope.launch { getApplication<Application>().dataStore.edit { it[AUTO_SCAN_KEY] = enabled } } }
    fun setAdvancedMode(enabled: Boolean) { isAdvancedModeEnabled = enabled; viewModelScope.launch { getApplication<Application>().dataStore.edit { it[ADVANCED_MODE_KEY] = enabled } } }
    fun set360CollectionMode(enabled: Boolean) { is360CollectionModeEnabled = enabled; viewModelScope.launch { getApplication<Application>().dataStore.edit { it[COLLECTION_360_KEY] = enabled } } }
    fun setMapFollowingMode(enabled: Boolean) { isMapFollowingModeEnabled = enabled; viewModelScope.launch { getApplication<Application>().dataStore.edit { it[MAP_FOLLOW_KEY] = enabled } } }
    fun updateDarkModeConfig(config: DarkModeConfig) { darkModeConfig = config; viewModelScope.launch { getApplication<Application>().dataStore.edit { it[DARK_MODE_KEY] = config.name } } }

    // 🌟 新增：写过滤设置
    fun setIgnoreUnnamed(enabled: Boolean) { isIgnoreUnnamedEnabled = enabled; viewModelScope.launch { getApplication<Application>().dataStore.edit { it[IGNORE_UNNAMED_KEY] = enabled } } }
}