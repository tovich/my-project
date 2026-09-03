package com.example.dnsspeed

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import java.util.UUID

sealed class CheckStatus {
    data object Pending : CheckStatus()
    data object Checking : CheckStatus()
    data class Ok(val latencyMs: Long) : CheckStatus()
    data object Unreachable : CheckStatus()
}

data class ServerUiState(
    val server: DnsServer,
    val status: CheckStatus = CheckStatus.Pending
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DnsSpeedTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DnsSpeedApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsSpeedApp() {
    val context = LocalContext.current
    val servers = remember { mutableStateListOf<ServerUiState>() }
    var isTesting by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun updateStatus(id: String, status: CheckStatus) {
        val index = servers.indexOfFirst { it.server.id == id }
        if (index >= 0) servers[index] = servers[index].copy(status = status)
    }

    /** Fastest first; unreachable and untested sink to the bottom. */
    fun sortBySpeed() {
        val sorted = servers.sortedWith(
            compareBy(
                { if (it.status is CheckStatus.Ok) 0 else 1 },
                { (it.status as? CheckStatus.Ok)?.latencyMs ?: Long.MAX_VALUE },
                { it.server.provider }
            )
        )
        servers.clear()
        servers.addAll(sorted)
    }

    fun testAll() {
        if (isTesting) return
        scope.launch {
            isTesting = true
            servers.indices.forEach { i -> servers[i] = servers[i].copy(status = CheckStatus.Checking) }

            val ids = servers.map { it.server.id to it.server.ip }
            val results = ids.map { (id, ip) ->
                scope.async {
                    val result = DnsChecker.check(ip)
                    id to when (result) {
                        is DnsCheckResult.Success -> CheckStatus.Ok(result.latencyMs)
                        else -> CheckStatus.Unreachable
                    }
                }
            }.awaitAll()

            results.forEach { (id, status) -> updateStatus(id, status) }
            sortBySpeed()
            isTesting = false
        }
    }

    fun testOne(id: String) {
        val server = servers.firstOrNull { it.server.id == id }?.server ?: return
        scope.launch {
            updateStatus(id, CheckStatus.Checking)
            val result = DnsChecker.check(server.ip)
            updateStatus(
                id,
                when (result) {
                    is DnsCheckResult.Success -> CheckStatus.Ok(result.latencyMs)
                    else -> CheckStatus.Unreachable
                }
            )
        }
    }

    fun addCustomServer(name: String, ip: String) {
        val server = DnsServer(
            id = "custom:${UUID.randomUUID()}",
            provider = name.trim().ifEmpty { "Свой DNS" },
            label = "Свой сервер",
            ip = ip.trim(),
            isCustom = true
        )
        servers.add(ServerUiState(server))
        CustomServersStore.save(context, servers.map { it.server })
        testOne(server.id)
    }

    fun deleteServer(id: String) {
        servers.removeAll { it.server.id == id }
        CustomServersStore.save(context, servers.map { it.server })
    }

    LaunchedEffect(Unit) {
        if (servers.isEmpty()) {
            servers.addAll(POPULAR_DNS_SERVERS.map { ServerUiState(it) })
            servers.addAll(CustomServersStore.load(context).map { ServerUiState(it) })
            testAll()
        }
    }

    val bestServer = servers
        .mapNotNull { state -> (state.status as? CheckStatus.Ok)?.let { state.server to it.latencyMs } }
        .minByOrNull { it.second }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DNS Speed Test", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    IconButton(onClick = { testAll() }, enabled = !isTesting) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Обновить все")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Свой DNS") }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (isTesting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Spacer(modifier = Modifier.height(4.dp))
            }

            if (bestServer != null) {
                BestServerBanner(
                    provider = bestServer.first.provider,
                    ip = bestServer.first.ip,
                    latencyMs = bestServer.second
                )
            }

            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(servers.size) { index ->
                    val state = servers[index]
                    ServerRow(
                        state = state,
                        onRetry = { testOne(state.server.id) },
                        onDelete = { deleteServer(state.server.id) }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddServerDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, ip ->
                addCustomServer(name, ip)
                showAddDialog = false
            }
        )
    }
}

@Composable
fun AddServerDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf("") }
    var touched by remember { mutableStateOf(false) }

    val ipIsValid = isValidDnsAddress(ip)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить свой DNS") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    placeholder = { Text("Например: Домашний роутер") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it; touched = true },
                    label = { Text("IP-адрес") },
                    placeholder = { Text("192.168.1.1") },
                    singleLine = true,
                    isError = touched && ip.isNotEmpty() && !ipIsValid,
                    supportingText = {
                        if (touched && ip.isNotEmpty() && !ipIsValid) {
                            Text("Введите корректный IPv4 или IPv6 адрес")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, ip) },
                enabled = ipIsValid
            ) { Text("Добавить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
fun BestServerBanner(provider: String, ip: String, latencyMs: Long) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp)),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Speed,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    "Самый быстрый: $provider",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    "$ip · $latencyMs мс",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
fun ServerRow(state: ServerUiState, onRetry: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(state.server.provider, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Text(
                    "${state.server.label} · ${state.server.ip}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            StatusBadge(status = state.status, onRetry = onRetry)

            if (state.server.isCustom) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Удалить",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun StatusBadge(status: CheckStatus, onRetry: () -> Unit) {
    when (status) {
        is CheckStatus.Pending -> {
            Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is CheckStatus.Checking -> {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        is CheckStatus.Ok -> {
            val speedColor = when {
                status.latencyMs < 50 -> Color(0xFF2E7D32)
                status.latencyMs < 150 -> Color(0xFFF9A825)
                else -> Color(0xFFEF6C00)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = speedColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("${status.latencyMs} мс", color = speedColor, fontWeight = FontWeight.SemiBold)
            }
        }
        is CheckStatus.Unreachable -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(color = Color(0xFFFFEBEE), shape = RoundedCornerShape(50))
                    .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            ) {
                Icon(
                    Icons.Filled.Error,
                    contentDescription = null,
                    tint = Color(0xFFC62828),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Недоступен", color = Color(0xFFC62828), fontSize = 12.sp)
                IconButton(onClick = onRetry, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Повторить",
                        tint = Color(0xFFC62828),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun DnsSpeedTheme(content: @Composable () -> Unit) {
    val colorScheme = lightColorScheme(
        primary = Color(0xFF1565C0),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD3E3FD),
        onPrimaryContainer = Color(0xFF0B3D82),
        surface = Color(0xFFFAFAFE),
        surfaceVariant = Color(0xFFF0F2F8),
        onSurfaceVariant = Color(0xFF5B6270),
        background = Color(0xFFFAFAFE)
    )
    MaterialTheme(colorScheme = colorScheme, content = content)
}
