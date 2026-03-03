package com.example.indoornavi

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(sharedViewModel: SharedViewModel, bottomPadding: Dp) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    var showClearConfirm by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }
    var showLicenseDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(top = 16.dp, start = 16.dp, end = 16.dp)) {
        Text("系统设置", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = bottomPadding + 24.dp)) {

            // ================= 1. 浮岛定制 =================
            item {
                Text("Dock 浮岛定制", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("对齐方式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))
                        SegmentedButton(options = DockAlignment.values().map { it.title }, selectedIndex = DockAlignment.values().indexOf(sharedViewModel.dockAlignment), onOptionSelected = { sharedViewModel.updateDockAlignment(DockAlignment.values()[it]) })
                        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Text("宽度占比 (${(sharedViewModel.dockWidthRatio * 100).toInt()}%)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Slider(value = sharedViewModel.dockWidthRatio, onValueChange = { sharedViewModel.setDockWidth(it) }, valueRange = 0.2f..1.0f, steps = 7)
                    }
                }
            }

            // ================= 2. 全局主题 =================
            item {
                Text("全局主题与外观", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
                    Column(modifier = Modifier.padding(20.dp)) {

                        // 🌟 主题颜色提取器保持不变
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(ThemePreset.values()) { preset ->
                                val isSelected = sharedViewModel.currentThemePreset == preset
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        modifier = Modifier.size(48.dp).clip(CircleShape).background(if (preset == ThemePreset.DYNAMIC) MaterialTheme.colorScheme.surfaceVariant else preset.color).border(width = if (isSelected) 3.dp else 1.dp, color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Gray.copy(alpha = 0.3f), shape = CircleShape).clickable { sharedViewModel.changeTheme(preset) },
                                        contentAlignment = Alignment.Center
                                    ) { if (preset == ThemePreset.DYNAMIC) Text("🌈", style = MaterialTheme.typography.titleMedium) else if (isSelected) Icon(Icons.Default.Check, contentDescription = null, tint = Color.White) }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(text = preset.title, style = MaterialTheme.typography.labelSmall, color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Gray, textAlign = TextAlign.Center, maxLines = 2, lineHeight = 12.sp, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 76.dp))
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // 🌟 新增深色模式切换接口
                        Text("深色模式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))
                        SegmentedButton(
                            options = DarkModeConfig.values().map { it.title },
                            selectedIndex = DarkModeConfig.values().indexOf(sharedViewModel.darkModeConfig),
                            onOptionSelected = { sharedViewModel.updateDarkModeConfig(DarkModeConfig.values()[it]) }                        )
                    }
                }
            }

            // ================= 3. 引擎配置 =================
            item {
                Text("雷达引擎配置", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
                    Column {
                        SettingSwitchItem(icon = Icons.Default.Search, title = "启动时自动扫描", subtitle = "进入应用后自动开启低功耗蓝牙 (BLE) 扫描", checked = sharedViewModel.autoScan, onCheckedChange = { sharedViewModel.setAutoScanState(it) })
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        SettingSwitchItem(icon = Icons.Default.Refresh, title = "360° 全向高精度采集", subtitle = "开启后，采集指纹时需原地旋转一圈以获取抗遮挡的平均信号", checked = sharedViewModel.is360CollectionModeEnabled, onCheckedChange = { sharedViewModel.set360CollectionMode(it) })
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        SettingSwitchItem(icon = Icons.Default.Build, title = "开发者性能评估模式", subtitle = "开启后可在定位页手动调节 WKNN 算法参数并测算误差", checked = sharedViewModel.isAdvancedModeEnabled, onCheckedChange = { sharedViewModel.setAdvancedMode(it) })
                    }
                }
            }

            // ================= 4. 关于与帮助 (你的专属名片) =================
            item {
                Text("关于与帮助", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
                    Column {
                        SettingClickableItem(icon = Icons.Default.Menu, title = "系统使用手册", subtitle = "了解如何构建指纹库及标定误差", onClick = { showManualDialog = true })
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        SettingClickableItem(icon = Icons.Default.Info, title = "开源声明与技术栈", subtitle = "Jetpack Compose & PDR Fusion Engine", onClick = { showLicenseDialog = true })
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // 开发者名片区
                        SettingClickableItem(icon = Icons.Default.Person, title = "开发者", subtitle = "Casper-003", onClick = { Toast.makeText(context, "感谢使用 IndoorNavi！", Toast.LENGTH_SHORT).show() })
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        SettingClickableItem(icon = Icons.Default.Email, title = "联系邮箱", subtitle = "casper-003@outlook.com", onClick = { Toast.makeText(context, "期待您的技术交流与反馈", Toast.LENGTH_SHORT).show() })
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        SettingClickableItem(icon = Icons.Default.Share, title = "开源仓库 (GitHub)", subtitle = "IndoorNavi_BLE", onClick = {
                            try { uriHandler.openUri("https://github.com/Casper-003/IndoorNavi_BLE") } catch (e: Exception) { Toast.makeText(context, "无法打开浏览器", Toast.LENGTH_SHORT).show() }
                        })
                    }
                }
            }

            // ================= 5. 数据管理 (危险区) =================
            item {
                Text("数据与存储", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))) {
                    SettingClickableItem(icon = Icons.Default.Delete, title = "清空所有本地缓存", subtitle = "清除已锁定的基站与所有未导出的指纹快照", isDestructive = true, onClick = { showClearConfirm = true })
                }
                Spacer(modifier = Modifier.height(32.dp))
                Text("IndoorNavi_BLE v3.0\nPowered by Kotlin & Jetpack Compose", style = MaterialTheme.typography.bodySmall, color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    // ================= 弹窗区域 =================
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("警告", fontWeight = FontWeight.Bold) },
            text = { Text("您确定要清空所有数据吗？此操作无法撤销。") },
            confirmButton = { Button(onClick = { sharedViewModel.updateRecordedPoints(emptyList()); showClearConfirm = false; Toast.makeText(context, "缓存已清空", Toast.LENGTH_SHORT).show() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("确认清空") } },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("取消") } }
        )
    }

    if (showManualDialog) {
        AlertDialog(
            onDismissRequest = { showManualDialog = false },
            title = { Text("📖 系统使用手册", fontWeight = FontWeight.Bold) },
            text = { Text("1. 【基站管理】：请先在此页面扫描并锁定至少 3 个用于定位的信标设备。\n\n2. 【指纹管理】：配置物理空间大小，在指定网格点上点击“极速单点”或“360°全向”采集环境信号。\n\n3. 【定位引擎】：红点代表算法实时解算的坐标。开启开发者模式后，可点击地图放置真实基准点，用以对比不同算法（WKNN、EMA、PDR）的动态误差。") },
            confirmButton = { TextButton(onClick = { showManualDialog = false }) { Text("我已了解") } }
        )
    }

    if (showLicenseDialog) {
        AlertDialog(
            onDismissRequest = { showLicenseDialog = false },
            title = { Text("⚖️ 开源技术声明", fontWeight = FontWeight.Bold) },
            text = { Text("本系统构建于以下现代移动开发技术栈：\n\n• Kotlin Coroutines & Flow\n• Jetpack Compose Material 3\n• Android BLE API & Sensors\n• Haze (UI 毛玻璃特效引擎)\n• Room Database\n\n核心定位引擎完全由开发者自主实现，采用了针对 RSSI 信号优化的 WKNN 算法及 PDR (航位推算) 多传感器融合技术。") },
            confirmButton = { TextButton(onClick = { showLicenseDialog = false }) { Text("关闭") } }
        )
    }
}

// ================= 通用设置项组件 =================
@Composable
fun SettingSwitchItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray, lineHeight = 14.sp)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun SettingClickableItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, isDestructive: Boolean = false, onClick: () -> Unit) {
    val contentColor = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val iconColor = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = contentColor)
            Spacer(modifier = Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = if (isDestructive) contentColor.copy(alpha = 0.7f) else Color.Gray, lineHeight = 14.sp)
        }
    }
}