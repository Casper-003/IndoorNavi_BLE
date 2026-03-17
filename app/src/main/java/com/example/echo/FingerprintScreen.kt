package com.example.echo

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class, ExperimentalFoundationApi::class)
@Composable
fun FingerprintManagerScreen(sharedViewModel: SharedViewModel, bottomPadding: Dp) {
    val maps = sharedViewModel.mapList
    val context = LocalContext.current
    var selectedMap by remember { mutableStateOf<MapEntity?>(null) }
    var showNewMapDialog by remember { mutableStateOf(false) }
    var showManualMapDialog by remember { mutableStateOf(false) }

    // 管理模式
    var isManageMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }

    var isArScanning by remember { mutableStateOf(false) }
    var rawPolygonToEdit by remember { mutableStateOf<List<Point>?>(null) }
    var pendingPolygon by remember { mutableStateOf<List<Point>?>(null) }

    // 退出管理模式时清空选择
    LaunchedEffect(isManageMode) { if (!isManageMode) selectedIds = emptySet() }

    LaunchedEffect(pendingPolygon) {
        if (pendingPolygon != null) {
            rawPolygonToEdit = pendingPolygon
            pendingPolygon = null
            isArScanning = false
        }
    }

    LaunchedEffect(isArScanning, rawPolygonToEdit) {
        sharedViewModel.isBottomBarVisible = !(isArScanning || rawPolygonToEdit != null)
    }

    AnimatedVisibility(
        visible = isArScanning,
        enter = slideInVertically(animationSpec = tween(350)) { it / 6 } + fadeIn(tween(350)),
        exit  = slideOutVertically(animationSpec = tween(250)) { it / 6 } + fadeOut(tween(250))
    ) {
        BackHandler(enabled = isArScanning) { isArScanning = false }
        ArScannerScreen(
            sharedViewModel = sharedViewModel,
            onComplete = { rawPolygon -> pendingPolygon = rawPolygon },
            onCancel = { isArScanning = false }
        )
    }

    if (!isArScanning) {
        if (rawPolygonToEdit != null) {
            BackHandler { rawPolygonToEdit = null }
            MapVerificationScreen(
                rawPolygon = rawPolygonToEdit!!,
                sharedViewModel = sharedViewModel,
                onSaveSuccess = { rawPolygonToEdit = null },
                onDiscard = { rawPolygonToEdit = null },
                onRescan = { rawPolygonToEdit = null; isArScanning = true }
            )
        } else if (sharedViewModel.isCollectingMode) {
            CollectionMapScreen(
                sharedViewModel = sharedViewModel,
                onBackClick = { sharedViewModel.isCollectingMode = false },
                bottomPadding = bottomPadding
            )
        } else {
            // 管理模式拦截物理返回
            if (isManageMode) BackHandler { isManageMode = false }

            SharedTransitionLayout {
                AnimatedContent(
                    targetState = selectedMap,
                    label = "MapTransition",
                    transitionSpec = { fadeIn(tween(500)) togetherWith fadeOut(tween(500)) }
                ) { targetMap ->
                    if (targetMap == null) {
                        Scaffold(
                            containerColor = Color.Transparent,
                            topBar = {
                                if (isManageMode) {
                                    // ── 管理模式顶栏 ──
                                    TopAppBar(
                                        title = {
                                            Text(
                                                if (selectedIds.isEmpty()) "选择地图"
                                                else "已选 ${selectedIds.size} 个",
                                                fontWeight = FontWeight.Bold
                                            )
                                        },
                                        navigationIcon = {
                                            IconButton(onClick = { isManageMode = false }) {
                                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "退出管理")
                                            }
                                        },
                                        actions = {
                                            // 全选 / 取消全选
                                            val allSelected = selectedIds.size == maps.size
                                            TextButton(onClick = {
                                                selectedIds = if (allSelected) emptySet()
                                                else maps.map { it.mapId }.toSet()
                                            }) {
                                                Text(if (allSelected) "取消全选" else "全选")
                                            }
                                            // 批量分享
                                            IconButton(
                                                onClick = {
                                                    val toShare = maps.filter { it.mapId in selectedIds }
                                                    shareMapFiles(context, toShare)
                                                },
                                                enabled = selectedIds.isNotEmpty()
                                            ) {
                                                Icon(Icons.Default.Share, contentDescription = "分享")
                                            }
                                            // 批量删除
                                            IconButton(
                                                onClick = { showBatchDeleteDialog = true },
                                                enabled = selectedIds.isNotEmpty()
                                            ) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = "删除",
                                                    tint = if (selectedIds.isNotEmpty())
                                                        MaterialTheme.colorScheme.error
                                                    else LocalContentColor.current.copy(alpha = 0.38f)
                                                )
                                            }
                                        },
                                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                                    )
                                } else {
                                    // ── 普通顶栏 ──
                                    TopAppBar(
                                        title = { Text("地图资产库", fontWeight = FontWeight.Bold) },
                                        actions = {
                                            IconButton(onClick = { isManageMode = true }) {
                                                Icon(Icons.Default.Checklist, contentDescription = "管理")
                                            }
                                            FilledTonalIconButton(
                                                onClick = { showNewMapDialog = true },
                                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                    containerColor = MaterialTheme.colorScheme.primary,
                                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                                )
                                            ) {
                                                Icon(Icons.Default.Add, contentDescription = "新建")
                                            }
                                        },
                                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                                    )
                                }
                            }
                        ) { innerPadding ->
                            if (maps.isEmpty()) {
                                EmptyMapState(Modifier.fillMaxSize().padding(innerPadding)) { showNewMapDialog = true }
                            } else {
                                LazyVerticalGrid(
                                    columns = GridCells.Adaptive(minSize = 160.dp),
                                    contentPadding = PaddingValues(
                                        top = innerPadding.calculateTopPadding() + 8.dp,
                                        bottom = bottomPadding + 88.dp,
                                        start = 16.dp, end = 16.dp
                                    ),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(maps, key = { it.mapId }) { map ->
                                        MapCardItem(
                                            map = map,
                                            animatedVisibilityScope = this@AnimatedContent,
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            isManageMode = isManageMode,
                                            isSelected = map.mapId in selectedIds,
                                            onClick = {
                                                if (isManageMode) {
                                                    selectedIds = if (map.mapId in selectedIds)
                                                        selectedIds - map.mapId
                                                    else selectedIds + map.mapId
                                                } else {
                                                    selectedMap = map
                                                }
                                            },
                                            onLongClick = {
                                                if (!isManageMode) {
                                                    isManageMode = true
                                                    selectedIds = setOf(map.mapId)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        MapDetailScreen(
                            map = targetMap,
                            sharedViewModel = sharedViewModel,
                            animatedVisibilityScope = this@AnimatedContent,
                            sharedTransitionScope = this@SharedTransitionLayout,
                            bottomPadding = bottomPadding,
                            onBack = { selectedMap = null }
                        )
                    }
                }
            }

            if (showNewMapDialog) {
                NewMapSelectionDialog(
                    onDismiss = { showNewMapDialog = false },
                    onSelectAR = { showNewMapDialog = false; isArScanning = true },
                    onSelectManual = { showNewMapDialog = false; showManualMapDialog = true }
                )
            }
            if (showManualMapDialog) {
                ManualMapCreationDialog(
                    onDismiss = { showManualMapDialog = false },
                    onCreate = { name, w, l ->
                        sharedViewModel.createNewMap(name = name, w = w, l = l, isAr = false)
                        showManualMapDialog = false
                    }
                )
            }
            // 批量删除确认弹窗
            if (showBatchDeleteDialog) {
                AlertDialog(
                    onDismissRequest = { showBatchDeleteDialog = false },
                    icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    title = { Text("删除 ${selectedIds.size} 张地图", fontWeight = FontWeight.Bold) },
                    text = { Text("所选地图及其全部指纹数据将被永久删除，无法恢复。确认继续？") },
                    confirmButton = {
                        Button(
                            onClick = {
                                selectedIds.forEach { sharedViewModel.deleteMap(it) }
                                showBatchDeleteDialog = false
                                isManageMode = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) { Text("确认删除") }
                    },
                    dismissButton = { TextButton(onClick = { showBatchDeleteDialog = false }) { Text("取消") } }
                )
            }
        } // end else (normal map list UI)
    } // end if (!isArScanning)
}

// ---------------- 卡片组件 ----------------
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class)
@Composable
fun MapCardItem(
    map: MapEntity,
    animatedVisibilityScope: AnimatedVisibilityScope,
    sharedTransitionScope: SharedTransitionScope,
    isManageMode: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }

    with(sharedTransitionScope) {
        Box {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.85f)
                    .sharedBounds(
                        sharedContentState = rememberSharedContentState(key = "card_${map.mapId}"),
                        animatedVisibilityScope = animatedVisibilityScope,
                        resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds
                    )
                    .combinedClickable(onClick = onClick, onLongClick = onLongClick),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                    else
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                )
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = if (isSelected) 0.18f else 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (map.isArScanned) Icons.Default.ViewInAr else Icons.Default.Map,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                            modifier = Modifier.size(48.dp)
                        )
                    }
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = map.mapName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = "${String.format("%.1f", map.width)}m × ${String.format("%.1f", map.length)}m", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(text = "创建于 ${formatter.format(Date(map.createdAt))}", style = MaterialTheme.typography.labelSmall, color = Color.Gray.copy(alpha = 0.8f))
                    }
                }
            }
            // 管理模式勾选标记
            if (isManageMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .size(24.dp)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.35f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

// ---------------- 全屏详情组件 ----------------
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MapDetailScreen(
    map: MapEntity,
    sharedViewModel: SharedViewModel,
    animatedVisibilityScope: AnimatedVisibilityScope,
    sharedTransitionScope: SharedTransitionScope,
    bottomPadding: Dp,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val isActive = sharedViewModel.currentActiveMapId.collectAsState().value == map.mapId
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    with(sharedTransitionScope) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .sharedBounds(
                    sharedContentState = rememberSharedContentState(key = "card_${map.mapId}"),
                    animatedVisibilityScope = animatedVisibilityScope,
                    resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds
                ),
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = { Text("地图详情") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showRenameDialog = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "重命名")
                        }
                        IconButton(onClick = { shareMapFiles(context, listOf(map)) }) {
                            Icon(Icons.Default.Share, contentDescription = "分享")
                        }
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { innerPadding ->
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 24.dp)) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(32.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("雷达引擎高精度渲染区", color = Color.Gray)
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text(map.mapName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("生成方式: ${if (map.isArScanned) "AR 激光雷达扫描" else "手动尺寸构建"}", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                    Text("尺寸: ${String.format("%.1f", map.width)}m × ${String.format("%.1f", map.length)}m", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text("建图时间: ${formatter.format(Date(map.createdAt))}", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)

                Spacer(modifier = Modifier.height(24.dp))

                Row(modifier = Modifier.fillMaxWidth().padding(bottom = bottomPadding + 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(
                        onClick = {
                            sharedViewModel.switchActiveMap(map.mapId)
                            Toast.makeText(context, "已切换导航地图至：${map.mapName}", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f).height(64.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isActive) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primaryContainer,
                            contentColor = if (isActive) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    ) {
                        Icon(imageVector = if (isActive) Icons.Default.CheckCircle else Icons.Default.Navigation, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isActive) "当前使用中" else "激活地图", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            if (!isActive) sharedViewModel.switchActiveMap(map.mapId)
                            sharedViewModel.isCollectingMode = true
                        },
                        modifier = Modifier.weight(1f).height(64.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.Radar, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("指纹采集", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showRenameDialog) {
        RenameMapDialog(
            currentName = map.mapName,
            onDismiss = { showRenameDialog = false },
            onConfirm = { newName ->
                sharedViewModel.renameMap(map.mapId, newName)
                showRenameDialog = false
            }
        )
    }

    // 删除确认弹窗
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("删除地图", fontWeight = FontWeight.Bold) },
            text = {
                Text("确定要删除「${map.mapName}」吗？该地图的所有指纹数据将被永久删除，此操作不可恢复。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        sharedViewModel.deleteMap(map.mapId)
                        showDeleteDialog = false
                        onBack()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
fun RenameMapDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var nameInput by remember { mutableStateOf(currentName) }
    val isValid = nameInput.isNotBlank() && nameInput != currentName

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名地图", fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                label = { Text("地图名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (isValid) onConfirm(nameInput.trim()) })
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(nameInput.trim()) },
                enabled = isValid
            ) { Text("确认") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ---------------- 分享/导出工具函数 ----------------
/**
 * 将地图列表序列化为 JSON 文件，通过系统分享 Sheet 发送。
 * 格式：JSON 数组，每项包含地图基本信息，兼容后续导入解析。
 */
fun shareMapFiles(context: Context, maps: List<MapEntity>) {
    if (maps.isEmpty()) return
    val sb = StringBuilder("[\n")
    maps.forEachIndexed { index, map ->
        sb.append("""  {
    "mapId": "${map.mapId}",
    "mapName": "${map.mapName.replace("\"", "\\\"")}",
    "createdAt": ${map.createdAt},
    "width": ${map.width},
    "length": ${map.length},
    "isArScanned": ${map.isArScanned},
    "polygonBounds": "${map.polygonBounds.replace("\"", "\\\"")}"
  }""")
        if (index < maps.size - 1) sb.append(",")
        sb.append("\n")
    }
    sb.append("]")

    val fileName = if (maps.size == 1) "${maps[0].mapName}.echo.json" else "echo_maps_export.json"
    val file = File(context.cacheDir, fileName)
    file.writeText(sb.toString())

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Echo 地图数据导出")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享地图文件"))
}

// ---------------- 空状态与对话框 ----------------
@Composable
fun EmptyMapState(modifier: Modifier, onAddClick: () -> Unit) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.surfaceVariant)
        Spacer(modifier = Modifier.height(16.dp))
        Text("你的空间资产库空空如也", style = MaterialTheme.typography.titleMedium, color = Color.Gray, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text("使用 AR 扫描房间，或手动建立平面图", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onAddClick) { Text("立即创建第一张地图") }
    }
}

@Composable
fun NewMapSelectionDialog(onDismiss: () -> Unit, onSelectAR: () -> Unit, onSelectManual: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建空间地图", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Card(onClick = onSelectAR, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ViewInAr, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.width(16.dp))
                        Column { Text("AR 实景测绘 (推荐)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer); Text("开启相机扫描房间角落生成真实多边形边界。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)) }
                    }
                }
                Card(onClick = onSelectManual, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Straighten, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.width(16.dp))
                        Column { Text("手动输入尺寸", fontWeight = FontWeight.Bold); Text("输入房间长宽，建立矩形平面图。", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}


@Composable
fun ManualMapCreationDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, w: Double, l: Double) -> Unit
) {
    var mapName by remember { mutableStateOf("") }
    var widthInput by remember { mutableStateOf("") }
    var lengthInput by remember { mutableStateOf("") }

    val width = widthInput.toDoubleOrNull()
    val length = lengthInput.toDoubleOrNull()
    val isValid = width != null && width > 0 && length != null && length > 0 && mapName.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动建图", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = mapName,
                    onValueChange = { mapName = it },
                    label = { Text("地图名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = widthInput,
                        onValueChange = { widthInput = it },
                        label = { Text("宽度 (m)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                        isError = widthInput.isNotEmpty() && (width == null || width <= 0)
                    )
                    OutlinedTextField(
                        value = lengthInput,
                        onValueChange = { lengthInput = it },
                        label = { Text("长度 (m)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (isValid) onCreate(mapName.trim(), width!!, length!!) }),
                        isError = lengthInput.isNotEmpty() && (length == null || length <= 0)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(mapName.trim(), width!!, length!!) },
                enabled = isValid
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ========================================================================================================
// 🌟 原版雷达采集页面 (完美兼容新架构，从 sharedViewModel.currentMap 读取地图尺寸)
// ========================================================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionMapScreen(sharedViewModel: SharedViewModel, onBackClick: () -> Unit, bottomPadding: Dp) {
    val context = LocalContext.current
    val scanner = remember { BleScanner(context) }
    val liveDevices by scanner.scannedDevicesFlow.collectAsState()
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val rotationSensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) }

    // 🌟 动态读取当前激活的地图长宽
    val currentMap = sharedViewModel.currentMap
    val w = currentMap?.width?.toFloat() ?: 10f
    val l = currentMap?.length?.toFloat() ?: 10f
    val s = (sharedViewModel.gridSpacing.toFloatOrNull() ?: 1f).coerceAtLeast(0.1f)

    val gridCoordinates = remember(w, l, s) {
        val pts = mutableListOf<Point>(); var x = 0f
        while (x <= w) { var y = 0f; while (y <= l) { pts.add(Point(x.toDouble(), y.toDouble())); y += s }; x += s }
        pts
    }

    var selectedPoint by remember { mutableStateOf<Point?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var collectionProgress by remember { mutableFloatStateOf(0f) }
    var accumulatedYaw by remember { mutableFloatStateOf(0f) }
    var lastYaw by remember { mutableStateOf<Float?>(null) }
    var showClearAllDialog by remember { mutableStateOf(false) }
    var lastTapPoint by remember { mutableStateOf<Point?>(null) }
    var lastTapTime by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) { scanner.startScan(); onDispose { scanner.stopScan() } }
    DisposableEffect(isRecording, sharedViewModel.is360CollectionModeEnabled) {
        var listener: SensorEventListener? = null
        if (isRecording && sharedViewModel.is360CollectionModeEnabled && rotationSensor != null) {
            accumulatedYaw = 0f; lastYaw = null; collectionProgress = 0f
            listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                        val rotationMatrix = FloatArray(9); SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values); val orientation = FloatArray(3); SensorManager.getOrientation(rotationMatrix, orientation)
                        val currentYaw = orientation[0]
                        if (lastYaw != null) {
                            var delta = currentYaw - lastYaw!!
                            if (delta > Math.PI) delta -= (2 * Math.PI).toFloat(); if (delta < -Math.PI) delta += (2 * Math.PI).toFloat()
                            accumulatedYaw += delta; collectionProgress = (abs(accumulatedYaw) / (2 * Math.PI).toFloat()).coerceIn(0f, 1f)
                        }
                        lastYaw = currentYaw
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
        }
        onDispose { listener?.let { sensorManager.unregisterListener(it) } }
    }

    LaunchedEffect(collectionProgress) {
        if (isRecording && sharedViewModel.is360CollectionModeEnabled && collectionProgress >= 1f) {
            val fingerprintMap = sharedViewModel.selectedDevices.keys.associateWith { mac -> liveDevices.find { it.macAddress == mac }?.smoothedRssi ?: -100 }
            val newPoint = ReferencePoint(id = "PT_${selectedPoint!!.x}_${selectedPoint!!.y}", coordinate = selectedPoint!!, fingerprint = fingerprintMap)
            sharedViewModel.updateRecordedPoints(sharedViewModel.recordedPoints.filter { it.coordinate != selectedPoint } + newPoint)
            isRecording = false; collectionProgress = 0f
        }
    }

    val configuration = LocalConfiguration.current; val isWideScreen = configuration.screenWidthDp > 600
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val mapContent = @Composable {
        Box(modifier = Modifier.padding(all = 16.dp).aspectRatio(1f, matchHeightConstraintsFirst = isWideScreen).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)).border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(24.dp))) {
            InteractiveRadarMap(
                gridCoordinates = gridCoordinates,
                recordedPoints = sharedViewModel.recordedPoints.map { it.coordinate },
                selectedPoint = selectedPoint, predictedPoint = null,
                onPointSelected = { pt ->
                    val currentTime = System.currentTimeMillis()
                    if (pt == lastTapPoint && (currentTime - lastTapTime) < 300) { if (sharedViewModel.recordedPoints.any { it.coordinate == pt }) { sharedViewModel.updateRecordedPoints(sharedViewModel.recordedPoints.filter { it.coordinate != pt }); selectedPoint = null } } else { selectedPoint = pt }
                    lastTapPoint = pt; lastTapTime = currentTime
                }
            )
        }
    }

    val controlContent = @Composable {
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("当前选定坐标", style = MaterialTheme.typography.labelMedium, color = Color.Gray); Spacer(modifier = Modifier.height(4.dp))
                        Text(text = if (selectedPoint != null) "X: ${selectedPoint!!.x}, Y: ${selectedPoint!!.y}" else "请在地图上选点", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = if (selectedPoint != null) MaterialTheme.colorScheme.primary else Color.Gray); Spacer(modifier = Modifier.height(4.dp))
                        Text(text = if (sharedViewModel.selectedDevices.isEmpty()) "❌ 未锁定基站" else if (sharedViewModel.is360CollectionModeEnabled) "↻ 360° 旋转采集模式" else "⚡ 极速单点模式", style = MaterialTheme.typography.labelMedium, color = if (sharedViewModel.selectedDevices.isEmpty()) MaterialTheme.colorScheme.error else Color.Gray)
                        Spacer(modifier = Modifier.height(12.dp)); Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary); Spacer(modifier = Modifier.width(4.dp)); Text("双击已采集点可直接删除", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
                    }
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(88.dp)) {
                        if (isRecording && sharedViewModel.is360CollectionModeEnabled) {
                            CircularProgressIndicator(progress = { 1f }, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), strokeWidth = 8.dp, modifier = Modifier.fillMaxSize())
                            CircularProgressIndicator(progress = { collectionProgress }, color = MaterialTheme.colorScheme.primary, strokeWidth = 8.dp, modifier = Modifier.fillMaxSize())
                            Text(text = "${(collectionProgress * 100).toInt()}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                        } else {
                            val canRecord = selectedPoint != null && sharedViewModel.selectedDevices.isNotEmpty()
                            FilledIconButton(onClick = {
                                if (canRecord) {
                                    isRecording = true
                                    if (!sharedViewModel.is360CollectionModeEnabled) {
                                        val fingerprintMap = sharedViewModel.selectedDevices.keys.associateWith { mac -> liveDevices.find { it.macAddress == mac }?.smoothedRssi ?: -100 }
                                        val newPoint = ReferencePoint(id = "PT_${selectedPoint!!.x}_${selectedPoint!!.y}", coordinate = selectedPoint!!, fingerprint = fingerprintMap)
                                        sharedViewModel.updateRecordedPoints(sharedViewModel.recordedPoints.filter { it.coordinate != selectedPoint } + newPoint)
                                        isRecording = false
                                    }
                                }
                            }, enabled = canRecord, modifier = Modifier.fillMaxSize(), shape = CircleShape, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary, disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Icon(imageVector = if (sharedViewModel.is360CollectionModeEnabled) Icons.Default.Refresh else Icons.Default.Add, contentDescription = "采集", modifier = Modifier.size(44.dp), tint = if (canRecord) MaterialTheme.colorScheme.onPrimary else Color.Gray)
                            }
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection), containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("采集雷达: ${currentMap?.mapName ?: "未知"}", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBackClick) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } },
                actions = {
                    if (sharedViewModel.recordedPoints.isNotEmpty()) {
                        FilledTonalButton(onClick = { showClearAllDialog = true }, colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer), modifier = Modifier.padding(end = 16.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(modifier = Modifier.width(4.dp)); Text("清空本图", fontWeight = FontWeight.Bold)
                        }
                    }
                },
                scrollBehavior = scrollBehavior, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface, scrolledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f).compositeOver(MaterialTheme.colorScheme.surface))
            )
        }
    ) { innerPadding ->
        if (isWideScreen) {
            Row(modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding())) { Box(modifier = Modifier.weight(1.5f).fillMaxHeight(), contentAlignment = Alignment.Center) { mapContent() }; Column(modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = bottomPadding)) { controlContent() } }
        } else {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { Spacer(modifier = Modifier.height(innerPadding.calculateTopPadding())); Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { mapContent() }; controlContent(); Spacer(modifier = Modifier.height(bottomPadding + 24.dp)) }
        }
    }

    if (showClearAllDialog) {
        AlertDialog(onDismissRequest = { showClearAllDialog = false }, title = { Text("清空本图点位") }, text = { Text("确定要清空该地图上的所有指纹点位吗？该操作不可恢复。") }, confirmButton = { Button(onClick = { sharedViewModel.updateRecordedPoints(emptyList()); showClearAllDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("全部清空") } }, dismissButton = { TextButton(onClick = { showClearAllDialog = false }) { Text("取消") } })
    }
}