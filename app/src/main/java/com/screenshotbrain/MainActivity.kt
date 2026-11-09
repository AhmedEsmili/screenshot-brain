package com.screenshotbrain

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

@Composable
fun Screen(dao: ItemDao) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val list by remember(q) { dao.search(q.trim()) }.collectAsState(initial = emptyList())

    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
    else Manifest.permission.READ_EXTERNAL_STORAGE

    fun scan() {
        scope.launch {
            busy = true
            val n = withContext(Dispatchers.IO) { Scanner.scanScreenshots(ctx, dao) }
            status = "Added $n new"
            busy = false
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) scan() else status = "Photo permission needed"
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED) scan()
        else launcher.launch(perm)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp)) {
        Text("Screenshot Brain", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = q, onValueChange = { q = it }, singleLine = true,
            label = { Text("Search: flight, receipt, wifi...") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.padding(vertical = 6.dp)) {
            Button(onClick = { scan() }, enabled = !busy) { Text(if (busy) "Scanning..." else "Scan screenshots") }
            Spacer(Modifier.width(12.dp))
            Text(status, Modifier.align(androidx.compose.ui.Alignment.CenterVertically))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { item -> ItemCard(item) }
        }
    }
}

@Composable
fun ItemCard(item: Item) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth().clickable { act(ctx, item) }) {
        Row(Modifier.padding(10.dp)) {
            if (item.uri != null) {
                AsyncImage(
                    model = item.uri, contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.width(10.dp))
            }
            Column {
                Text(item.type, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                if (item.detail.isNotBlank()) Text(item.detail, style = MaterialTheme.typography.titleMedium)
                Text(
                    item.text.replace("\n", " ").take(90),
                    style = MaterialTheme.typography.bodySmall, maxLines = 2
                )
            }
        }
    }
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
            else -> {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("sb", item.detail.ifBlank { item.text }))
                Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
    } catch (e: Exception) {
        Toast.makeText(ctx, "No app to handle this", Toast.LENGTH_SHORT).show()
    }
}
