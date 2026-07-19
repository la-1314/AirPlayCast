package com.miui.airplaycast.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.miui.airplaycast.MainViewModel
import com.miui.airplaycast.airplay.MediaCastState
import com.miui.airplaycast.airplay.MirrorState
import com.miui.airplaycast.capture.ScreenCaptureManager
import com.miui.airplaycast.discovery.AirPlayDevice
import com.miui.airplaycast.ui.components.*
import com.miui.airplaycast.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val devices by viewModel.devices.collectAsState()
    val selectedDevice by viewModel.selectedDevice.collectAsState()
    val isDiscovering by viewModel.isDiscovering.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val castUrl by viewModel.castUrl.collectAsState()
    val localUri by viewModel.localUri.collectAsState()
    val mediaSession by viewModel.mediaSession.collectAsState()
    val mirrorState by viewModel.mirrorState.collectAsState()
    val captureState by viewModel.captureState.collectAsState()

    var urlInput by remember(castUrl) { mutableStateOf(castUrl) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Toast 监听
    LaunchedEffect(Unit) {
        viewModel.toast.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    // 网络权限 (mDNS 多播需要)
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // MediaProjection 投屏权限
    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.startMirroring(result.resultCode, result.data!!)
        }
    }

    // 本地文件选择
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.setLocalUri(it) }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部标题
            MiTopBar(
                title = "AirPlay 投屏",
                actions = {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable { viewModel.refresh() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isDiscovering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(Icons.Rounded.Refresh, contentDescription = "刷新", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 模式选择
                item {
                    MiSegmentedControl(
                        options = listOf("屏幕镜像", "媒体投放"),
                        selectedIndex = mode,
                        onSelected = { viewModel.setMode(it) }
                    )
                }

                // Hero 区: 状态/简介
                item {
                    HeroCard(
                        mode = mode,
                        selectedDevice = selectedDevice,
                        mirrorState = mirrorState,
                        mediaState = mediaSession?.state?.collectAsState()?.value
                    )
                }

                // 设备列表
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "可用设备",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(Modifier.weight(1f))
                        if (isDiscovering) {
                            MiStatusBadge(text = "搜索中...", color = MaterialTheme.colorScheme.primary)
                        } else {
                            MiStatusBadge(text = "${devices.size} 台设备", color = MiSuccess)
                        }
                    }
                }

                if (devices.isEmpty()) {
                    item { EmptyDeviceState() }
                } else {
                    items(devices, key = { it.name + it.address }) { device ->
                        MiDeviceCard(
                            name = device.name,
                            address = device.displayAddress,
                            isSelected = selectedDevice?.name == device.name,
                            statusText = buildDeviceStatus(device),
                            onClick = { viewModel.selectDevice(device) }
                        )
                    }
                }

                // 操作区
                item {
                    Spacer(Modifier.height(8.dp))
                    when (mode) {
                        0 -> MirroringActions(
                            mirrorState = mirrorState,
                            captureState = captureState,
                            selectedDevice = selectedDevice,
                            onStart = {
                                if (selectedDevice == null) {
                                    scope.launch { snackbarHostState.showSnackbar("请先选择设备") }
                                    return@MirroringActions
                                }
                                val mpm = context.getSystemService(MediaProjectionManager::class.java)
                                mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
                            },
                            onStop = { viewModel.stopMirroring() }
                        )
                        1 -> MediaCastActions(
                            urlInput = urlInput,
                            onUrlChange = { urlInput = it; viewModel.setCastUrl(it) },
                            localUri = localUri,
                            onPickFile = { filePickerLauncher.launch("video/*") },
                            onCastUrl = {
                                if (selectedDevice == null) {
                                    scope.launch { snackbarHostState.showSnackbar("请先选择设备") }
                                    return@MediaCastActions
                                }
                                viewModel.castUrl()
                            },
                            onCastLocal = {
                                if (selectedDevice == null) {
                                    scope.launch { snackbarHostState.showSnackbar("请先选择设备") }
                                    return@MediaCastActions
                                }
                                val ip = getLocalIp(context) ?: return@MediaCastActions
                                viewModel.castLocalFile(localUri!!, ip)
                            },
                            mediaState = mediaSession?.state?.collectAsState()?.value,
                            onPlay = { mediaSession?.resume() },
                            onPause = { mediaSession?.pause() },
                            onStop = { viewModel.stopMediaCast() }
                        )
                    }
                }

                // 协议说明
                item { ProtocolInfoCard() }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        ) { data -> Snackbar(snackbarData = data) }
    }
}

@Composable
private fun HeroCard(
    mode: Int,
    selectedDevice: AirPlayDevice?,
    mirrorState: MirrorState,
    mediaState: MediaCastState?
) {
    val shapes = LocalMiShapes.current
    val gradient = Brush.linearGradient(
        if (mode == 0) listOf(MiGradientStart, MiGradientEnd)
        else listOf(MiGradientOrangeStart, MiGradientOrangeEnd)
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(shapes.extraLarge)
            .background(gradient)
            .padding(20.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (mode == 0) Icons.Rounded.ScreenShare else Icons.Rounded.Cast,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (mode == 0) "屏幕镜像" else "媒体投放",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    mode == 0 && mirrorState is MirrorState.Running -> "镜像到 ${mirrorState.deviceName}"
                    mode == 0 && mirrorState is MirrorState.Connecting -> "连接中..."
                    mode == 1 && mediaState is MediaCastState.Playing -> "正在投放媒体"
                    mode == 1 && mediaState is MediaCastState.Paused -> "已暂停"
                    selectedDevice != null -> "已选择 ${selectedDevice.name}"
                    else -> "请选择 AirPlay 接收设备"
                },
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 14.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (mode == 0)
                    "通过 AirPlay 协议将本机屏幕实时投射到接收端"
                else
                    "支持 HTTP URL 与本地媒体文件 (mp4 / m4a / mp3)",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun EmptyDeviceState() {
    val shapes = LocalMiShapes.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shapes.cardShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Rounded.WifiTethering,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "暂未发现 AirPlay 设备",
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "请确保设备已连接同一 WiFi 网络且接收端已开启",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun MirroringActions(
    mirrorState: MirrorState,
    captureState: ScreenCaptureManager.CaptureState,
    selectedDevice: AirPlayDevice?,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val running = mirrorState is MirrorState.Running
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!running) {
            MiPrimaryButton(
                text = "开始镜像",
                icon = Icons.Rounded.ScreenShare,
                modifier = Modifier.fillMaxWidth(),
                onClick = onStart
            )
        } else {
            MiSecondaryButton(
                text = "停止镜像",
                icon = Icons.Rounded.Stop,
                modifier = Modifier.fillMaxWidth(),
                onClick = onStop
            )
        }

        if (captureState is ScreenCaptureManager.CaptureState.Running) {
            MiCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Videocam, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("正在捕获屏幕", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text(
                            "分辨率 ${captureState.width} × ${captureState.height} · 30fps",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaCastActions(
    urlInput: String,
    onUrlChange: (String) -> Unit,
    localUri: Uri?,
    onPickFile: () -> Unit,
    onCastUrl: () -> Unit,
    onCastLocal: () -> Unit,
    mediaState: MediaCastState?,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit
) {
    val shapes = LocalMiShapes.current
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // URL 输入
        MiCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("视频 / 音频 URL", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = onUrlChange,
                    placeholder = { Text("http://example.com/video.mp4") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Spacer(Modifier.height(10.dp))
                MiPrimaryButton(
                    text = "投放 URL",
                    icon = Icons.Rounded.Cast,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onCastUrl
                )
            }
        }

        // 本地文件
        MiCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("本地媒体文件", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onPickFile() }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = localUri?.lastPathSegment ?: "选择文件",
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                            maxLines = 1
                        )
                        Text(
                            text = "支持 mp4 / mov / mp3 / m4a",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (localUri != null) {
                    Spacer(Modifier.height(10.dp))
                    MiPrimaryButton(
                        text = "开始投放",
                        icon = Icons.Rounded.PlayArrow,
                        modifier = Modifier.fillMaxWidth(),
                        useGradient = false,
                        onClick = onCastLocal
                    )
                }
            }
        }

        // 播放控制
        if (mediaState != null && mediaState !is MediaCastState.Idle) {
            MiCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val stateText = when (mediaState) {
                            is MediaCastState.Playing -> "正在播放"
                            is MediaCastState.Paused -> "已暂停"
                            is MediaCastState.Connecting -> "连接中..."
                            is MediaCastState.Error -> mediaState.message
                            MediaCastState.Idle -> "已停止"
                        }
                        MiStatusBadge(
                            text = stateText,
                            color = when (mediaState) {
                                is MediaCastState.Playing -> MiSuccess
                                is MediaCastState.Error -> MiError
                                else -> MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (mediaState is MediaCastState.Playing) {
                            MiSecondaryButton(
                                text = "暂停",
                                icon = Icons.Rounded.Pause,
                                modifier = Modifier.weight(1f),
                                onClick = onPause
                            )
                        } else if (mediaState is MediaCastState.Paused) {
                            MiSecondaryButton(
                                text = "继续",
                                icon = Icons.Rounded.PlayArrow,
                                modifier = Modifier.weight(1f),
                                onClick = onPlay
                            )
                        }
                        MiSecondaryButton(
                            text = "停止",
                            icon = Icons.Rounded.Stop,
                            modifier = Modifier.weight(1f),
                            onClick = onStop
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtocolInfoCard() {
    val shapes = LocalMiShapes.current
    MiCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("协议说明", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "• 媒体投放使用 AirPlay 1 HTTP 协议 (/play, /rate, /stop)\n" +
                        "• 屏幕镜像基于 RTSP + H.264 NAL 流\n" +
                        "• AirPlay 2 镜像涉及 FairPlay 加密，部分 Apple TV 可能拒绝未加密镜像流\n" +
                        "• 开源接收端 (UxPlay / ESP32 AirPlay) 兼容性更好",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }
    }
}

private fun buildDeviceStatus(device: AirPlayDevice): String? {
    val caps = mutableListOf<String>()
    if (device.supportsMirroring) caps.add("镜像")
    if (device.supportsAirPlayVideo) caps.add("视频")
    if (device.supportsAudio) caps.add("音频")
    return if (caps.isEmpty()) null else "支持: ${caps.joinToString(" · ")}"
}

private fun getLocalIp(context: android.content.Context): String? {
    return runCatching {
        // 使用 NetworkInterface 获取本机 IP
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        for (intf in interfaces) {
            if (!intf.isUp || intf.isLoopback) continue
            for (addr in intf.inetAddresses) {
                if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                    return addr.hostAddress
                }
            }
        }
        null
    }.getOrNull()
}
