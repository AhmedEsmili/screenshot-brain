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
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val dao = AppDb.get(this).dao()
        lifecycleScope.launch(Dispatchers.IO) { Reclassify.runOnce(this@MainActivity, dao) }
        handleShare(intent, dao)
        setContent {
            SbTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Screen(dao) }
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
                        type = type, detail = detail, folder = type,
                        addedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }
}

// ---------- helpers ----------

private val palette = listOf(0xFF7C8BD0, 0xFF6FB3A8, 0xFFE0A15A, 0xFFC97BA3, 0xFF7FB27A, 0xFFA486C9).map { Color(it) }

private fun dotColor(n: String): Color {
    val c = Cats.find(n)
    return if (c != null) Color(c.color) else palette[abs(n.hashCode()) % palette.size]
}

private fun actionLabel(t: String) = when (t) {
    "Address" -> "Open in Maps"
    "Event", "Flight" -> "Add to calendar"
    "Link" -> "Open link"
    else -> "Copy"
}

private fun title(item: Item) =
    if (item.type == "Code") "Verification code" else item.detail.ifBlank { item.type }

private fun toggle(s: Set<Long>, id: Long) = if (id in s) s - id else s + id

// ---------- screen ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Screen(dao: ItemDao) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var viewing by remember { mutableStateOf<Int?>(null) }
    var folder by remember { mutableStateOf(Cats.ALL) }
    var confirm by remember { mutableStateOf<List<Long>?>(null) }
    var pending by remember { mutableStateOf<List<Long>>(emptyList()) }
    var moveIds by remember { mutableStateOf<List<Long>?>(null) }
    var nameDialog by remember { mutableStateOf<List<Long>?>(null) }
    var nameText by remember { mutableStateOf("") }
    var confirmFolder by remember { mutableStateOf(false) }

    val all by remember { dao.search("") }.collectAsState(initial = emptyList())
    val results by remember(q) { dao.search(q.trim()) }.collectAsState(initial = emptyList())
    val customFolders by remember { dao.customFolders() }.collectAsState(initial = emptyList())
    val customNames = customFolders.map { it.name }.toSet()
    val active = if (q.isNotBlank()) Cats.ALL else folder
    val list = if (active == Cats.ALL) results else results.filter { it.folder == active }
    val counts = all.groupBy { it.folder }.mapValues { it.value.size }
    val names = (counts.keys + customNames).distinct()
        .sortedWith(compareByDescending<String> { counts[it] ?: 0 }.thenBy { it })

    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
    else Manifest.permission.READ_EXTERNAL_STORAGE

    fun scan() {
        scope.launch {
            busy = true
            val n = withContext(Dispatchers.IO) { Scanner.scanScreenshots(ctx, dao).size }
            if (n > 0) status = "Added $n new"
            busy = false
        }
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun onMediaGranted() {
        scan()
        Watcher.start(ctx)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) onMediaGranted() else status = "Photo permission needed"
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
        status = "Removed"
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

    fun moveTo(ids: List<Long>, name: String) {
        scope.launch(Dispatchers.IO) { dao.move(ids, name) }
        selected = setOf(); viewing = null
        status = "Moved to $name"
    }

    fun createFolder(name: String, ids: List<Long>) {
        scope.launch(Dispatchers.IO) {
            dao.addFolder(Folder(name, System.currentTimeMillis()))
            if (ids.isNotEmpty()) dao.move(ids, name)
        }
        if (ids.isNotEmpty()) {
            selected = setOf(); viewing = null
            status = "Moved to $name"
        } else folder = name
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED) onMediaGranted()
        else permLauncher.launch(perm)
    }

    LaunchedEffect(status) {
        if (status.isNotEmpty()) { delay(2600); status = "" }
    }

    BackHandler(enabled = folder != Cats.ALL) { folder = Cats.ALL }
    BackHandler(enabled = selected.isNotEmpty()) { selected = setOf() }

    LaunchedEffect(list.size, folder, q) {
        if (folder != Cats.ALL && list.isEmpty() && q.isBlank() && folder !in customNames) folder = Cats.ALL
    }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 140.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalItemSpacing = 16.dp
        ) {
            item(key = "header", span = StaggeredGridItemSpan.FullLine) {
                Header(
                    q = q, onQ = { q = it }, busy = busy, onScan = { scan() },
                    names = names, counts = counts, total = all.size, active = active,
                    isCustom = active in customNames,
                    onPick = { folder = it; q = "" },
                    onNew = { nameText = ""; nameDialog = emptyList() },
                    onDeleteFolder = { confirmFolder = true }
                )
            }
            if (list.isEmpty()) {
                item(key = "empty", span = StaggeredGridItemSpan.FullLine) {
                    Text(
                        if (busy) "" else "Nothing here yet",
                        color = cs.onSurfaceVariant, fontSize = 15.sp,
                        modifier = Modifier.padding(vertical = 48.dp).fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
            itemsIndexed(list, key = { _, x -> x.id }) { idx, item ->
                Card(
                    item = item,
                    selected = item.id in selected,
                    selecting = selected.isNotEmpty(),
                    onClick = {
                        if (selected.isNotEmpty()) selected = toggle(selected, item.id)
                        else viewing = idx
                    },
                    onLong = { selected = toggle(selected, item.id) }
                )
            }
        }

        if (selected.isNotEmpty()) {
            Surface(
                shape = CircleShape, color = cs.onSurface, shadowElevation = 6.dp,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp)
            ) {
                Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selected = setOf() }) { SbIcon(Ic.Close, cs.background) }
                    Text("${selected.size} selected", color = cs.background, fontSize = 15.sp)
                    TextButton(onClick = { moveIds = selected.toList() }) {
                        Text("Move", color = cs.background, fontWeight = FontWeight.Medium)
                    }
                    IconButton(onClick = { confirm = selected.toList() }) { SbIcon(Ic.Trash, cs.background) }
                }
            }
        } else if (status.isNotEmpty()) {
            Surface(
                shape = CircleShape, color = cs.onSurface, shadowElevation = 6.dp,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 22.dp)
            ) {
                Text(
                    status, color = cs.background, fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                )
            }
        }

        viewing?.let { start ->
            Viewer(
                list = list, start = start,
                onClose = { viewing = null },
                onDelete = { id -> confirm = listOf(id) },
                onMove = { id -> moveIds = listOf(id) }
            )
        }

        val dialogShape = RoundedCornerShape(24.dp)

        confirm?.let { ids ->
            AlertDialog(
                onDismissRequest = { confirm = null },
                shape = dialogShape, containerColor = cs.surface,
                title = { Text(if (ids.size == 1) "Delete screenshot?" else "Delete ${ids.size} screenshots?") },
                text = {
                    Column {
                        TextButton(onClick = { confirm = null; removeFromApp(ids) }) { Text("Remove from app only") }
                        TextButton(onClick = { confirm = null; deleteFromPhone(ids) }) { Text("Delete from phone too") }
                    }
                },
                confirmButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
            )
        }

        moveIds?.let { ids ->
            val options = (customNames.toList().sorted() + Cats.list.map { it.name }).distinct()
            AlertDialog(
                onDismissRequest = { moveIds = null },
                shape = dialogShape, containerColor = cs.surface,
                title = { Text("Move to") },
                text = {
                    Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                        TextButton(onClick = { moveIds = null; nameText = ""; nameDialog = ids }) { Text("+ New folder") }
                        options.forEach { n ->
                            TextButton(onClick = { moveIds = null; moveTo(ids, n) }) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor(n)))
                                Spacer(Modifier.width(10.dp))
                                Text(n)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { moveIds = null }) { Text("Cancel") } }
            )
        }

        nameDialog?.let { ids ->
            AlertDialog(
                onDismissRequest = { nameDialog = null },
                shape = dialogShape, containerColor = cs.surface,
                title = { Text("New folder") },
                text = {
                    OutlinedTextField(
                        value = nameText, onValueChange = { nameText = it.take(30) },
                        singleLine = true, placeholder = { Text("e.g. Trip to Rome") }
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = nameText.isNotBlank() && nameText.trim() != Cats.ALL,
                        onClick = { val n = nameText.trim(); nameDialog = null; createFolder(n, ids) }
                    ) { Text("Create") }
                },
                dismissButton = { TextButton(onClick = { nameDialog = null }) { Text("Cancel") } }
            )
        }

        if (confirmFolder) {
            val n = folder
            AlertDialog(
                onDismissRequest = { confirmFolder = false },
                shape = dialogShape, containerColor = cs.surface,
                title = { Text("Delete \"$n\"?") },
                text = { Text("Screenshots inside go back to their categories. Nothing is deleted from your phone.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmFolder = false
                        folder = Cats.ALL
                        scope.launch(Dispatchers.IO) { dao.releaseFolder(n); dao.removeFolder(n) }
                    }) { Text("Delete folder") }
                },
                dismissButton = { TextButton(onClick = { confirmFolder = false }) { Text("Cancel") } }
            )
        }
    }
}

// ---------- header ----------

@Composable
fun Header(
    q: String, onQ: (String) -> Unit, busy: Boolean, onScan: () -> Unit,
    names: List<String>, counts: Map<String, Int>, total: Int, active: String,
    isCustom: Boolean, onPick: (String) -> Unit, onNew: () -> Unit, onDeleteFolder: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.statusBarsPadding().padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "screenshot brain", color = cs.onBackground, fontSize = 22.sp,
                fontWeight = FontWeight.Light, letterSpacing = (-0.3).sp,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onScan, enabled = !busy) {
                SbIcon(Ic.Refresh, if (busy) cs.outline else cs.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        SearchBar(q, onQ)
        Spacer(Modifier.height(14.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Chip("All", total, active == Cats.ALL, null) { onPick(Cats.ALL) } }
            items(names, key = { it }) { n ->
                Chip(n, counts[n] ?: 0, active == n, dotColor(n)) { onPick(n) }
            }
            item { Chip("+ New", null, false, null) { onNew() } }
        }
        if (isCustom) {
            TextButton(onClick = onDeleteFolder, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text("Delete this folder", color = cs.onSurfaceVariant, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (busy) LinearProgressIndicator(
                modifier = Modifier.fillMaxSize(), color = cs.onSurface, trackColor = cs.outline
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun SearchBar(q: String, onQ: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(50.dp).clip(CircleShape).background(cs.surface)
            .border(1.dp, cs.outline, CircleShape).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SbIcon(Ic.Search, cs.onSurfaceVariant, dp = 20.dp)
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = q, onValueChange = onQ, singleLine = true,
            textStyle = TextStyle(color = cs.onSurface, fontSize = 16.sp),
            cursorBrush = SolidColor(cs.onSurface),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (q.isEmpty()) Text("Search your screenshots", color = cs.onSurfaceVariant, fontSize = 16.sp)
                    inner()
                }
            }
        )
        if (q.isNotEmpty()) {
            Box(Modifier.size(28.dp).clip(CircleShape).clickable { onQ("") }, contentAlignment = Alignment.Center) {
                SbIcon(Ic.Close, cs.onSurfaceVariant, dp = 16.dp)
            }
        }
    }
}

@Composable
fun Chip(label: String, count: Int?, selected: Boolean, dot: Color?, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg = if (selected) cs.onSurface else cs.surface
    val fg = if (selected) cs.background else cs.onSurface
    Row(
        Modifier.clip(CircleShape).background(bg)
            .then(if (!selected) Modifier.border(1.dp, cs.outline, CircleShape) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dot != null) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(7.dp))
        }
        Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text("$count", color = fg.copy(alpha = 0.5f), fontSize = 13.sp)
        }
    }
}

// ---------- cards ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Card(item: Item, selected: Boolean, selecting: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLong)) {
        Box(
            Modifier.fillMaxWidth().clip(shape).background(cs.surfaceVariant)
                .then(if (selected) Modifier.border(2.dp, cs.onSurface, shape) else Modifier)
        ) {
            if (item.uri != null) {
                AsyncImage(
                    model = item.uri, contentDescription = null,
                    contentScale = ContentScale.FillWidth, alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 300.dp)
                )
            } else {
                Text(
                    item.text.take(220), color = cs.onSurface, fontSize = 13.sp,
                    modifier = Modifier.padding(14.dp)
                )
            }
            if (selecting) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp).clip(CircleShape)
                        .background(if (selected) cs.onSurface else Color(0x66000000))
                        .then(if (!selected) Modifier.border(1.5.dp, Color.White, CircleShape) else Modifier),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) SbIcon(Ic.Check, cs.background, dp = 14.dp)
                }
            }
        }
        Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp)) {
            Text(
                title(item), color = cs.onBackground, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor(item.type)))
                Spacer(Modifier.width(6.dp))
                Text(
                    item.type.lowercase() + if (item.folder != item.type && item.folder.isNotBlank()) "  ·  " + item.folder else "",
                    color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ---------- viewer ----------

@Composable
fun PillButton(text: String, filled: Boolean, onClick: () -> Unit) {
    val shape = CircleShape
    Box(
        Modifier.clip(shape)
            .background(if (filled) Color(0xFFF6F5F2) else Color.Transparent)
            .then(if (!filled) Modifier.border(1.dp, Color(0x66FFFFFF), shape) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Text(
            text, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            color = if (filled) Color(0xFF1B1B1A) else Color.White
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Viewer(
    list: List<Item>, start: Int,
    onClose: () -> Unit, onDelete: (Long) -> Unit, onMove: (Long) -> Unit
) {
    val ctx = LocalContext.current
    BackHandler(onBack = onClose)
    if (list.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val pager = rememberPagerState(initialPage = start.coerceIn(0, list.size - 1)) { list.size }
    val cur = list[pager.currentPage.coerceIn(0, list.size - 1)]

    Box(Modifier.fillMaxSize().background(Color(0xFF0E0E0E))) {
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
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp)) {
                    Text(item.text, color = Color.White, fontSize = 17.sp)
                }
            }
        }

        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent)))
                .statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) { SbIcon(Ic.Back, Color.White) }
            Text(
                "${pager.currentPage + 1} / ${list.size}", color = Color(0xCCFFFFFF), fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { onDelete(cur.id) }) { SbIcon(Ic.Trash, Color.White) }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                .navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 20.dp)
        ) {
            Text(
                title(cur), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Light,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor(cur.type)))
                Spacer(Modifier.width(8.dp))
                Text(
                    cur.type.lowercase() + if (cur.folder != cur.type && cur.folder.isNotBlank()) "  ·  " + cur.folder else "",
                    color = Color(0xB3FFFFFF), fontSize = 13.sp
                )
            }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(actionLabel(cur.type), true) { act(ctx, cur) }
                PillButton("Move", false) { onMove(cur.id) }
                PillButton("Copy text", false) { copy(ctx, cur.text) }
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
            "Link" -> {
                val u = item.detail.let { if (it.startsWith("http")) it else "https://$it" }
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
            }
            else -> copy(ctx, item.detail.ifBlank { item.text })
        }
    } catch (e: Exception) {
        Toast.makeText(ctx, "No app to handle this", Toast.LENGTH_SHORT).show()
    }
}
