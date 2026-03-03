package com.example.indoornavi

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BaseStationManagerScreen(
    selectedDevices: Map<String, BeaconDevice>,
    onSelectionChange: (Map<String, BeaconDevice>) -> Unit,
    bottomPadding: Dp
) {
    val context = LocalContext.current
    val scanner = remember { BleScanner(context) }
    val displayedDevices by scanner.scannedDevicesFlow.collectAsState()
    var isScanning by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val unselectedDevices = displayedDevices.filter { it.macAddress !in selectedDevices.keys }

    DisposableEffect(Unit) {
        if (isScanning) scanner.startScan()
        onDispose { scanner.stopScan() }
    }

    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp > 600

    val scanListUI = @Composable { modifier: Modifier ->
        Column(modifier = modifier) {
            // 🌟 优化：重构顶部的扫描控制台
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 🌟 1. 改造成类似 Dock 栏的果冻胶囊按钮
                Button(
                    onClick = {
                        if (isScanning) scanner.stopScan() else scanner.startScan()
                        isScanning = !isScanning
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp), // 强制高度等于 Dock 栏的 64dp
                    shape = CircleShape, // 强制变成完美半圆弧胶囊
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isScanning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = if (isScanning) "⏹ 停止扫描" else "▶ 开始扫描",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(20.dp))

                // 🌟 2. 旁边的计数器重新排版，压住大按钮的气场
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("发现设备", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                    Text(
                        text = "${unselectedDevices.size}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { coroutineScope.launch { isRefreshing = true; scanner.clearDevices(); delay(500); isRefreshing = false } },
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = bottomPadding)
                ) {
                    items(unselectedDevices, key = { it.macAddress }) { device ->
                        DeviceItemCard(device = device, modifier = Modifier.animateItem()) { onSelectionChange(selectedDevices + (device.macAddress to device)) }
                    }
                }
            }
        }
    }

    val cartUI = @Composable { modifier: Modifier, isLandscape: Boolean ->
        Column(modifier = modifier.padding(16.dp)) {
            Text("已锁定基站 (${selectedDevices.size})", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            if (isLandscape) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                    items(selectedDevices.values.toList(), key = { "sel_${it.macAddress}" }) { device -> SelectedDeviceChip(device = device, modifier = Modifier.animateItem().fillMaxWidth()) { onSelectionChange(selectedDevices - device.macAddress) } }
                }
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    items(selectedDevices.values.toList(), key = { "sel_${it.macAddress}" }) { device -> SelectedDeviceChip(device = device, modifier = Modifier.animateItem()) { onSelectionChange(selectedDevices - device.macAddress) } }
                }
                HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
            }
        }
    }

    // 🌟 终极外层包裹：找回丢失的大标题！
    Column(modifier = Modifier.fillMaxSize()) {
        // 与 SettingsScreen 采用完全相同的 headlineMedium 字体与边距规范
        Text(
            text = "基站管理",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
        )

        if (isWideScreen) {
            Row(modifier = Modifier.weight(1f)) {
                scanListUI(Modifier.weight(1.5f))
                if (selectedDevices.isNotEmpty()) {
                    Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)))
                    cartUI(Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)), true)
                }
            }
        } else {
            Column(modifier = Modifier.weight(1f)) {
                AnimatedVisibility(visible = selectedDevices.isNotEmpty()) { cartUI(Modifier.fillMaxWidth(), false) }
                scanListUI(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun DeviceItemCard(device: BeaconDevice, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val hasName = !device.name.isNullOrEmpty(); val displayName = if (hasName) device.name!! else "未知设备"; val nameColor = if (hasName) MaterialTheme.colorScheme.onPrimaryContainer else Color.Gray
    Card(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = if (hasName) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = displayName, color = nameColor, fontWeight = FontWeight.Bold)
                Text(text = device.macAddress, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Text(text = "${device.smoothedRssi} dBm", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = if (device.smoothedRssi > -60) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun SelectedDeviceChip(device: BeaconDevice, modifier: Modifier = Modifier, onRemove: () -> Unit) {
    Card(onClick = onRemove, modifier = modifier, shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column { Text(text = device.name ?: "未知设备", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(text = device.macAddress.takeLast(8), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)) }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.Default.Close, contentDescription = "移除", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp).background(MaterialTheme.colorScheme.onPrimary, CircleShape))
        }
    }
}