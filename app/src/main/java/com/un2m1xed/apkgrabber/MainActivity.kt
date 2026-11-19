package com.un2m1xed.apkgrabber

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class AppResult(val title: String, val url: String, val iconUrl: String?)
private data class Finished(val id: Long, val name: String)

private sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data class Error(val message: String) : SearchState
    data class Done(val items: List<AppResult>) : SearchState
}

private const val APK_MIME = "application/vnd.android.package-archive"
private const val UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

// ---------- сеть ----------

private fun searchUrl(q: String) =
    "https://www.apkmirror.com/?post_type=app_release&searchtype=apk&s=" + URLEncoder.encode(q, "UTF-8")

private fun httpGet(url: String): String {
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        return c.inputStream.bufferedReader().use { it.readText() }
    } finally {
        c.disconnect()
    }
}

/** Ссылка Google Play -> название приложения (запасной вариант: имя пакета). */
private fun resolveQuery(raw: String): String {
    val uri = Uri.parse(raw)
    if (uri.host == "play.google.com" || uri.host == "market.android.com") {
        val id = uri.getQueryParameter("id") ?: return raw
        val name = runCatching {
            Jsoup.parse(httpGet("https://play.google.com/store/apps/details?id=$id&hl=en"))
                .title().substringBefore(" - Apps on Google Play").trim()
        }.getOrNull()
        return name?.takeIf { it.isNotBlank() && !it.contains("Not Found", true) } ?: id
    }
    return raw
}

private fun searchApps(query: String): List<AppResult> {
    val doc = Jsoup.parse(httpGet(searchUrl(query)), "https://www.apkmirror.com/")
    return doc.select("#content .appRow").mapNotNull { row ->
        val a = row.selectFirst("h5 a") ?: row.selectFirst("a[href]") ?: return@mapNotNull null
        val name = a.text().trim()
        val href = a.absUrl("href")
        if (name.isBlank() || href.isBlank()) return@mapNotNull null
        val icon = row.selectFirst("img")
            ?.let { i -> i.absUrl("src").ifBlank { i.absUrl("data-src") } }
            ?.takeIf { it.startsWith("http") }
        AppResult(name, href.substringBefore('#') + "#downloads", icon)
    }.distinctBy { it.url }.take(30)
}

// ---------- Activity ----------

class MainActivity : ComponentActivity() {
    private val pending = mutableSetOf<Long>()
    private var finished by mutableStateOf<Finished?>(null)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (!pending.remove(id)) return
            val dm = getSystemService(DownloadManager::class.java)
            dm.query(DownloadManager.Query().setFilterById(id)).use { cur ->
                if (cur.moveToFirst()) {
                    val status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        val title = cur.getString(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE))
                        finished = Finished(id, title ?: "APK")
                    } else toast("Загрузка не удалась")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), RECEIVER_EXPORTED)
        setContent {
            GrabberTheme {
                var pageUrl by rememberSaveable { mutableStateOf<String?>(null) }
                var pageTitle by rememberSaveable { mutableStateOf("") }
                val url = pageUrl
                if (url == null) {
                    HomeScreen { pageUrl = it.url; pageTitle = it.title }
                } else {
                    BrowserScreen(pageTitle, url, onBack = { pageUrl = null }, onDownload = ::enqueueDownload)
                }
                finished?.let { f ->
                    AlertDialog(
                        onDismissRequest = { finished = null },
                        title = { Text("APK загружен") },
                        text = { Text("Файл ${f.name} сохранён в Download/APKGrabber. Устанавливайте, только если доверяете источнику.") },
                        confirmButton = { TextButton(onClick = { finished = null; install(f.id) }) { Text("Установить") } },
                        dismissButton = { TextButton(onClick = { finished = null }) { Text("Позже") } },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    private fun enqueueDownload(url: String, userAgent: String?, disposition: String?, mime: String?) {
        try {
            val uri = Uri.parse(url)
            if (uri.scheme != "https") { toast("Загрузка разрешена только по HTTPS"); return }
            val filename = URLUtil.guessFileName(url, disposition, mime)
            if (!filename.endsWith(".apk", true)) {
                toast("Это не обычный APK (XAPK/APKM не поддерживаются)")
                return
            }
            val request = DownloadManager.Request(uri)
                .setTitle(filename)
                .setDescription("APK Grabber")
                .setMimeType(APK_MIME)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "APKGrabber/$filename")
            userAgent?.let { request.addRequestHeader("User-Agent", it) }
            CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
                ?.let { request.addRequestHeader("Cookie", it) }
            pending += getSystemService(DownloadManager::class.java).enqueue(request)
            toast("Загрузка началась")
        } catch (e: Exception) {
            toast("Не удалось начать загрузку: ${e.localizedMessage}")
        }
    }

    private fun install(id: Long) {
        if (!packageManager.canRequestPackageInstalls()) {
            toast("Разрешите установку из APK Grabber и повторите")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val uri = getSystemService(DownloadManager::class.java).getUriForDownloadedFile(id)
        if (uri == null) { toast("Не удалось найти файл"); return }
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (_: Exception) {
            toast("Не удалось открыть установщик. Откройте файл из Download/APKGrabber вручную")
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}

// ---------- UI ----------

@Composable
private fun GrabberTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(onOpen: (AppResult) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var state by remember { mutableStateOf<SearchState>(SearchState.Idle) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    fun go() {
        val raw = query.trim()
        if (raw.isEmpty() || state is SearchState.Loading) return
        focus.clearFocus()
        state = SearchState.Loading
        scope.launch {
            state = try {
                SearchState.Done(withContext(Dispatchers.IO) { searchApps(resolveQuery(raw)) })
            } catch (e: Exception) {
                SearchState.Error(e.localizedMessage ?: "нет соединения")
            }
        }
    }

    var taps by remember { mutableIntStateOf(0) }
    var secret by remember { mutableStateOf(false) }
    if (secret) SecretDialog { secret = false }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("APK Grabber") }) },
        bottomBar = {
            Text(
                "by un32m1xed",
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .clickable { if (++taps >= 5) { taps = 0; secret = true } }
                    .padding(16.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                placeholder = { Text("Название или ссылка Google Play") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, "Очистить")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { go() }),
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = ::go, modifier = Modifier.fillMaxWidth().height(52.dp), enabled = state !is SearchState.Loading) {
                Text("Найти приложение")
            }
            Spacer(Modifier.height(8.dp))
            if (state is SearchState.Loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))

            when (val s = state) {
                SearchState.Idle -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Info, null)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Поиск идёт по каталогу APKMirror. APK из сторонних источников могут быть небезопасны — проверяйте разработчика и подпись.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                SearchState.Loading -> {}
                is SearchState.Error -> Fallback("Ошибка поиска: ${s.message}") { onOpen(AppResult("Поиск: ${query.trim()}", searchUrl(query.trim()), null)) }
                is SearchState.Done ->
                    if (s.items.isEmpty()) Fallback("Ничего не найдено или сайт ограничил запросы.") {
                        onOpen(AppResult("Поиск: ${query.trim()}", searchUrl(query.trim()), null))
                    } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                        items(s.items, key = { it.url }) { item ->
                            Card(onClick = { onOpen(item) }) {
                                ListItem(
                                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                                    leadingContent = {
                                        AsyncImage(
                                            model = item.iconUrl, contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)),
                                        )
                                    },
                                    headlineContent = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = { Text("APKMirror · версии и загрузка") },
                                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                                )
                            }
                        }
                    }
            }
        }
    }
}

@Composable
private fun Fallback(message: String, onOpenSite: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = onOpenSite) { Text("Открыть поиск на APKMirror") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserScreen(
    title: String,
    startUrl: String,
    onBack: () -> Unit,
    onDownload: (String, String?, String?, String?) -> Unit,
) {
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    BackHandler { if (canGoBack) web?.goBack() else onBack() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
        )
    }) { pad ->
        Column(Modifier.padding(pad)) {
            if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = UA
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = r.url.scheme != "https"
                            override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) {
                                canGoBack = v.canGoBack()
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(v: WebView, p: Int) { progress = p }
                        }
                        setDownloadListener { url, ua, cd, mime, _ -> onDownload(url, ua, cd, mime) }
                        web = this
                        loadUrl(startUrl)
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}

@Composable
private fun SecretDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var playing by remember { mutableStateOf(false) }
    val player = remember {
        MediaPlayer.create(ctx, R.raw.gabagool2026)?.apply { setOnCompletionListener { playing = false } }
    }
    DisposableEffect(Unit) { onDispose { player?.release() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Секрет найден \uD83C\uDF89") },
        text = { Text("Привет от un32m1xed") },
        confirmButton = {
            Button(onClick = {
                if (playing) { player?.pause(); player?.seekTo(0); playing = false }
                else { player?.start(); playing = true }
            }) { Text(if (playing) "Стоп" else "▶ Включить звук") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
