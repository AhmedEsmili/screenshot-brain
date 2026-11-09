package com.screenshotbrain

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = AppDb.get(this).dao()
        handleShare(intent, dao)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen(dao) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent, AppDb.get(this).dao())
    }

    private fun handleShare(i: Intent?, dao: ItemDao) {
        if (i?.action != Intent.ACTION_SEND) return
        lifecycleScope.launch(Dispatchers.IO) {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= 33)
                i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
            val txt = i.getStringExtra(Intent.EXTRA_TEXT)
            if (uri != null) {
                Scanner.ingest(this@MainActivity, dao, uri, uri.toString())
            } else if (!txt.isNullOrBlank()) {
                val (type, detail) = Extractor.classify(txt)
                dao.insert(
                    Item(
                        source = "text:" + txt.hashCode(), uri = null, text = txt,
                        type = type, detail = detail, addedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }
}

private fun typeColor(t: String) = when (t) {
    "Code" -> Color(0xFF7E57C2)
    "Package" -> Color(0xFF8D6E63)
    "Flight" -> Color(0xFF29B6F6)
    "Receipt" -> Color(0xFF43A047)
    "Event" -> Color(0xFFFB8C00)
    "Address" -> Color(0xFFE53935)
    "Wi-Fi" -> Color(0xFF00897B)
    else -> Color(0xFF607D8B)
}

private fun actionLabel(t: String) = when (t) {
    "Address" -> "Open in Maps"
    "Event", "Flight" -> "Add to calendar"
    else -> "Copy"
}

private fun toggle(s: Set<Long>, id: Long) = if (id in s) s - id else s + id

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Screen(dao: ItemDao) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var viewing by remember { mutableStateOf<Int?>(null) }
    var confirm by remember { mutableStateOf<List<Long>?>(null) }
    var pending by remember { mutableStateOf<List<Long>>(emptyList()) }
    val list by remember(q) { dao.search(q.trim()) }.collectAsState(initial = emptyList())

    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
    else Manifest.permission.READ_EXTERNAL_STORAGE

    fun scan() {
        scope.launch {
            busy = true
            val n = withContext(Dispatchers.IO) { Scanner.scanScreenshots(ctx, dao) }
            status = if (n > 0) "Added $n new" else ""
            busy = false
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) scan() else status = "Photo permission needed"
    }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val ids = pending
            scope.launch(Dispatchers.IO) { dao.deleteByIds(ids) }
            selected = setOf(); viewing = null
            status = "Deleted from phone"
        } else status = "Delete cancelled"
    }

    fun removeFromApp(ids: List<Long>) {
        scope.launch(Dispatchers.IO) { dao.hide(ids) }
        selected = setOf(); viewing = null
        status = "Removed from app"
    }

    fun deleteFromPhone(ids: List<Long>) {
        val uris = list.filter { it.id in ids && it.uri?.startsWith("content://media") == true }
            .map { Uri.parse(it.uri) }
        if (uris.isEmpty()) {
            scope.launch(Dispatchers.IO) { dao.deleteByIds(ids) }
            selected = setOf(); viewing = null
            return
        }
        pending = ids
        try {
            val sender = MediaStore.createDeleteRequest(ctx.contentResolver, uris).intentSender
            deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
        } catch (e: Exception) {
            status = "Couldn't delete the file"
        }
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED) scan()
        else permLauncher.launch(perm)
    }

    BackHandler(enabled = selected.isNotEmpty()) { selected = setOf() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 12.dp)) {
            if (selected.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selected = setOf() }) { Icon(Icons.Default.Close, "Cancel") }
                    Text("${selected.size} selected", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { confirm = selected.toList() }) { Icon(Icons.Default.Delete, "Delete") }
                }
            } else {
                Text(
                    "Screenshot Brain", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 12.dp)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = q, onValueChange = { q = it }, singleLine = true,
                    placeholder = { Text("Search: flight, receipt, wifi...") },
                    shape = RoundedCornerShape(28.dp), modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { scan() }, enabled = !busy) { Icon(Icons.Default.Refresh, "Scan") }
            }
            if (busy || status.isNotEmpty()) {
                Text(
                    if (busy) "Scanning..." else status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                itemsIndexed(list, key = { _, x -> x.id }) { idx, item ->
                    Tile(
                        item = item,
                        selected = item.id in selected,
                        onClick = {
                            if (selected.isNotEmpty()) selected = toggle(selected, item.id)
                            else viewing = idx
                        },
                        onLong = { selected = toggle(selected, item.id) }
                    )
                }
            }
        }

        viewing?.let { start ->
            Viewer(
                list = list, start = start,
                onClose = { viewing = null },
                onDelete = { id -> confirm = listOf(id) }
            )
        }

        confirm?.let { ids ->
            AlertDialog(
                onDismissRequest = { confirm = null },
                title = { Text(if (ids.size == 1) "Delete screenshot?" else "Delete ${ids.size} screenshots?") },
                text = {
                    Column {
                        Text("Choose how to delete.")
                        TextButton(onClick = { confirm = null; removeFromApp(ids) }) { Text("Remove from app only") }
                        TextButton(onClick = { confirm = null; deleteFromPhone(ids) }) { Text("Delete from phone too") }
                    }
                },
                confirmButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tile(item: Item, selected: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier.fillMaxWidth().aspectRatio(0.66f).clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
    ) {
        if (item.uri != null) {
            AsyncImage(
                model = item.uri, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(item.text.take(160), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
        Surface(
            shape = RoundedCornerShape(50), color = typeColor(item.type),
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
        ) {
            Text(
                item.type, color = Color.White, style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        if (item.detail.isNotBlank()) {
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(
                    item.detail, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        if (selected) {
            Icon(
                Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color.White, CircleShape)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Viewer(list: List<Item>, start: Int, onClose: () -> Unit, onDelete: (Long) -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onClose)
    if (list.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val pager = rememberPagerState(initialPage = start.coerceIn(0, list.size - 1)) { list.size }
    val cur = list[pager.currentPage.coerceIn(0, list.size - 1)]

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager, modifier = Modifier.fillMaxSize(),
            key = { list.getOrNull(it)?.id ?: it }
        ) { p ->
            val item = list.getOrNull(p) ?: return@HorizontalPager
            if (item.uri != null) {
                AsyncImage(
                    model = item.uri, contentDescription = null,
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                    Text(item.text, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent)))
                .statusBarsPadding().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
            Text(
                "${pager.currentPage + 1} / ${list.size}", color = Color.White,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { onDelete(cur.id) }) { Icon(Icons.Default.Delete, "Delete", tint = Color.White) }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                .navigationBarsPadding().padding(16.dp)
        ) {
            Surface(shape = RoundedCornerShape(50), color = typeColor(cur.type)) {
                Text(
                    cur.type, color = Color.White, style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }
            if (cur.detail.isNotBlank()) {
                Text(
                    cur.detail, color = Color.White, style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { act(ctx, cur) }) { Text(actionLabel(cur.type)) }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { copy(ctx, cur.text) }) { Text("Copy text", color = Color.White) }
            }
        }
    }
}

fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("sb", text))
    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}

fun act(ctx: Context, item: Item) {
    try {
        when (item.type) {
            "Address" -> ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(item.detail)))
            )
            "Event", "Flight" -> ctx.startActivity(
                Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, item.type + ": " + item.detail)
            )
            else -> copy(ctx, item.detail.ifBlank { item.text })
        }
    } catch (e: Exception) {
        Toast.makeText(ctx, "No app to handle this", Toast.LENGTH_SHORT).show()
    }
}
