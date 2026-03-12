package com.example.echo

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
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
    sharedViewModel: SharedViewModel, // 🌟 接收全局设置以进行过滤
    bottomPadding: Dp
) {
    val context = LocalContext.current
    val scanner = remember { BleScanner(context) }
    val displayedDevices by scanner.scannedDevicesFlow.collectAsState()
    var isScanning by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val selectedDevices = sharedViewModel.selectedDevices
    val onSelectionChange: (Map<String, BeaconDevice>) -> Unit = { sharedViewModel.selectedDevices = it }

    // 🌟 在展示前直接接管数组，执行“无名设备过滤”逻辑
    val unselectedDevices = displayedDevices.filter {
        it.macAddress !in selectedDevices.keys &&
                (!sharedViewModel.isIgnoreUnnamedEnabled || !it.name.isNullOrBlank())
    }

    DisposableEffect(Unit) {
        if (isScanning) scanner.startScan()
        onDispose { scanner.stopScan() }
    }

    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp > 600

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val topBarBgColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.05f).compositeOver(MaterialTheme.colorScheme.surface)
    val scrolledTopBarBgColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f).compositeOver(MaterialTheme.colorScheme.surface)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("基站管理", fontWeight = FontWeight.Bold) },
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
                }
            }
        }

        val pullRefreshState = rememberPullToRefreshState()

        if (isWideScreen) {
            Row(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.weight(1.5f)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).padding(top = innerPadding.calculateTopPadding()), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { if (isScanning) scanner.stopScan() else scanner.startScan(); isScanning = !isScanning }, modifier = Modifier.weight(1f).height(64.dp), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = if (isScanning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)) {
                            Text(text = if (isScanning) "⏹ 停止扫描" else "▶ 开始扫描", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(20.dp))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("发现设备", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                            Text(text = "${unselectedDevices.size}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = { coroutineScope.launch { isRefreshing = true; scanner.clearDevices(); delay(500); isRefreshing = false } },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = bottomPadding)) {
                            items(unselectedDevices, key = { it.macAddress }) { device -> DeviceItemCard(device = device, modifier = Modifier.animateItem()) { onSelectionChange(selectedDevices + (device.macAddress to device)) } }
                        }
                    }
                }
                if (selectedDevices.isNotEmpty()) {
                    Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)).padding(top = innerPadding.calculateTopPadding()))
                    cartUI(Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)).padding(top = innerPadding.calculateTopPadding()), true)
                }
            }
        } else {
            // 🌟 终极三段式重构：告别按钮乱跑
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(modifier = Modifier.height(innerPadding.calculateTopPadding()))

                // --- 第 1 段：坚固的顶部扫描控制台 ---
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { if (isScanning) scanner.stopScan() else scanner.startScan(); isScanning = !isScanning }, modifier = Modifier.weight(1f).height(64.dp), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = if (isScanning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)) {
                        Text(text = if (isScanning) "⏹ 停止扫描" else "▶ 开始扫描", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(20.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("发现设备", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                        Text(text = "${unselectedDevices.size}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                    }
                }

                // --- 第 2 段：独立的弹性中间滑动区 ---
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { coroutineScope.launch { isRefreshing = true; scanner.clearDevices(); delay(500); isRefreshing = false } },
                    state = pullRefreshState,
                    indicator = {
                        Indicator(
                            modifier = Modifier.align(Alignment.TopCenter),
                            isRefreshing = isRefreshing,
                            state = pullRefreshState
                        )
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth() // 🌟 占满中间所有可用空间
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(bottom = 16.dp), // 列表最底部留出少许呼吸空间
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(unselectedDevices, key = { it.macAddress }) { device ->
                            DeviceItemCard(device = device, modifier = Modifier.animateItem()) { onSelectionChange(selectedDevices + (device.macAddress to device)) }
                        }
                    }
                }

                // --- 第 3 段：死死钉在底部的购物车区域 ---
                AnimatedVisibility(visible = selectedDevices.isNotEmpty()) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        cartUI(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)), false)
                    }
                }

                // --- 完美避让底部的系统导航栏 ---
                Spacer(modifier = Modifier.height(bottomPadding))
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