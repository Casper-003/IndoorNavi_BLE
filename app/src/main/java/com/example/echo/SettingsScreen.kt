package com.example.echo

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(sharedViewModel: SharedViewModel, bottomPadding: Dp) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    var showClearConfirm by remember { mutableStateOf(false) }
    var showLicenseDialog by remember { mutableStateOf(false) }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val topBarBgColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.05f).compositeOver(MaterialTheme.colorScheme.surface)
    val scrolledTopBarBgColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f).compositeOver(MaterialTheme.colorScheme.surface)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("系统设置", fontWeight = FontWeight.Bold) },
                scrollBehavior = scrollBehavior,
                windowInsets = WindowInsets.statusBars,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = topBarBgColor,
                    scrolledContainerColor = scrolledTopBarBgColor,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            contentPadding = PaddingValues(top = innerPadding.calculateTopPadding(), bottom = bottomPadding + 24.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {

            // ================= 1. 全局主题与外观 =================
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text("全局主题与外观", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
                    Column(modifier = Modifier.padding(20.dp)) {

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

                        Text("深色模式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))
                        SegmentedButton(
                            options = DarkModeConfig.values().map { it.title },
                            selectedIndex = DarkModeConfig.values().indexOf(sharedViewModel.darkModeConfig),
                            onOptionSelected = { sharedViewModel.updateDarkModeConfig(DarkModeConfig.values()[it]) }
                        )
                    }
                }
            }

            // ================= 2. 雷达引擎配置 =================
            item {
                Text("雷达引擎配置", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
                    Column {
                        SettingSwitchItem(icon = Icons.Default.LocationOn, title = "第一人称沉浸导航", subtitle = "开启后地图将反向平移与旋转，当前定位坐标始终固定在屏幕中央", checked = sharedViewModel.isMapFollowingModeEnabled, onCheckedChange = { sharedViewModel.setMapFollowingMode(it) })
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        SettingSwitchItem(icon = Icons.Default.Search, title = "启动时自动扫描", subtitle = "进入应用后自动开启低功耗蓝牙 (BLE) 扫描", checked = sharedViewModel.autoScan, onCheckedChange = { sharedViewModel.setAutoScanState(it) })
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        SettingSwitchItem(icon = Icons.Default.CheckCircle, title = "过滤无名设备", subtitle = "在基站管理页屏蔽未广播名称的隐藏或乱码设备，保持列表整洁", checked = sharedViewModel.isIgnoreUnnamedEnabled, onCheckedChange = { sharedViewModel.setIgnoreUnnamed(it) })
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        SettingSwitchItem(icon = Icons.Default.Refresh, title = "360° 全向高精度采集", subtitle = "开启后，采集指纹时需原地旋转一圈以获取抗遮挡的平均信号", checked = sharedViewModel.is360CollectionModeEnabled, onCheckedChange = { sharedViewModel.set360CollectionMode(it) })
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        SettingSwitchItem(icon = Icons.Default.Build, title = "开发者性能评估模式", subtitle = "开启后可在定位页手动调节 WKNN 算法参数并测算误差", checked = sharedViewModel.isAdvancedModeEnabled, onCheckedChange = { sharedViewModel.setAdvancedMode(it) })
                    }
                }
            }

            // ================= 3. 动态折叠：系统操作手册 (🌟 UI/点击重塑版) =================
            item {
                // 将手册标题行与下方卡片剥离，保持排版一致性
                Text("系统操作手册", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))

                // 🌟 核心修复 1：将原先四个独立的卡片项统统塞入这一个大圆角卡片（shape = 24.dp, surfaceVariant）中，彻底解决“丑”的问题。
                Card(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)), // 严格物理裁剪卡片
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {

                        // 🌟 核心修复 2：为了让内部看起来像一个整体设置项群组，我们将原先具有独立卡片的【ManualSectionCard】
                        // 替换为不含卡片的【ManualExpandableItem】内部组件，不仅排版与设置项一致，
                        // 而且彻底根治了那个“巨大的圆”点击问题，只在该章节项区域产生精致的波纹特效。

                        ManualExpandableItem(
                            title = "一、 基站部署与锁定",
                            content = "1. 硬件建议：推荐 ESP32 广播频率设为 10Hz (100ms)，发射功率降至 0dBm 或 -3dBm 以构建良好的室内空间信号衰减梯度。\n\n" +
                                    "2. 基站锁定：在【基站】页面等待扫描出物理基站，务必手动勾选至少 3 个基站。锁定后，指纹采集与定位解算将严格基于这几个基站的信号源。"
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        ManualExpandableItem(
                            title = "二、 指纹采集与建库",
                            content = "1. 地图初始化：在【指纹】页面设定物理空间的宽、长和网格间距（建议 1~2 米）。\n\n" +
                                    "2. 单点采集 (⚡)：点击地图选定格子，点击采集，系统瞬间记录当前 RSSI 均值入库。\n\n" +
                                    "3. 360° 发条模式 (↻)：勾选后，选定点位点击开始，需手持设备朝着同一个方向（不可左右来回晃）缓慢旋转 360 度。系统会收集全方位的极化信号，大幅提高 KNN 精度。\n\n" +
                                    "4. 数据备份：所有打点完成后，务必点击【导出备份】生成 CSV 存档，防止重装 App 导致辛苦打点的数据丢失。"
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        ManualExpandableItem(
                            title = "三、 避障绘图与导航",
                            content = "1. 绘制墙体：进入【定位】页的“避障编辑”模式，通过手指划动可在地图上浇筑灰色的物理墙体。系统自带绝对防穿模的 0.4s 体积膨胀及空气墙边缘防护。\n\n" +
                                    "2. A* 平滑导航：在“定位导航”模式下选定紫色的目标点。系统会自动避开墙体、走在走廊正中间。右上角 HUD 将实时计算真实的“折线行走总路程”。"
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        ManualExpandableItem(
                            title = "四、 实验评估与导出",
                            content = "请先开启上方【开发者性能评估模式】以解锁算法面板。\n\n" +
                                    "1. 误差测绘：对比生数据(RAW)、传统平滑(EMA)、多模态融合(PDR)对漂移的抑制效果。选定地图真值后，右上角会实时显示漂移误差。\n\n" +
                                    "2. 定点空间实验 (50次)：验证环境变化对定点精度的影响。自动高频采集 50 个样本。\n\n" +
                                    "3. 定点时间实验 (60秒)：手机静置 60 秒连续录制，用于在论文中绘制“时间-坐标(X/Y)”的滤波效果折线图。\n\n" +
                                    "4. 自由轨迹录制 (2Hz)：无需点选地图，直接开启录制并在场地内走动，系统会以 2Hz 的频率连续记录输出的 X/Y 轨迹日志，用于绘制最终的 2D 走动轨迹图。\n\n" +
                                    "* 提示：测试产生的数据存放于 Android/data/com.example.echo/files/ 目录下。"
                        )
                    }
                }
            }

            // ================= 4. 关于与帮助 =================
            item {
                Text("关于与帮助", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
                    Column {
                        SettingClickableItem(icon = Icons.Default.Info, title = "开源声明与技术栈", subtitle = "Jetpack Compose & PDR Fusion Engine", onClick = { showLicenseDialog = true })
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
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

            // ================= 5. 数据与存储 =================
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

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("警告", fontWeight = FontWeight.Bold) },
            text = { Text("您确定要清空所有数据吗？此操作无法撤销。") },
            confirmButton = { Button(onClick = { sharedViewModel.updateRecordedPoints(emptyList()); showClearConfirm = false; Toast.makeText(context, "缓存已清空", Toast.LENGTH_SHORT).show() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("确认清空") } },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("取消") } }
        )
    }

    if (showLicenseDialog) {
        AlertDialog(
            onDismissRequest = { showLicenseDialog = false },
            title = { Text("⚖️ 开源技术声明", fontWeight = FontWeight.Bold) },
            text = { Text("本系统构建于以下现代移动开发技术栈：\n\n• Kotlin Coroutines & Flow\n• Jetpack Compose Material 3\n• Android BLE API & Sensors\n• Room Database\n\n核心定位引擎完全由开发者自主实现，采用了针对 RSSI 信号优化的 WKNN 算法及 PDR (航位推算) 多传感器融合技术。") },
            confirmButton = { TextButton(onClick = { showLicenseDialog = false }) { Text("关闭") } }
        )
    }
}

// ================= 组件区 (重塑版) =================

// 🌟 核心修复 2 的底层实现：此组件不再包含卡片，而是作为大卡片内部的“章节项”
@Composable
fun ManualExpandableItem(title: String, content: String) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(targetValue = if (expanded) 180f else 0f, label = "icon_rotate")

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 16.dp, horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface
            )
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.rotate(rotation),
                tint = Color.Gray
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            // 🌟 修复：使用链式 padding
            Column(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.3f
                )
            }
        }
    }
}

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