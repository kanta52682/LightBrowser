package com.example.lightbrowser

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.lightbrowser.ui.theme.LightBrowserTheme
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Collections

@Serializable
data class Bookmark(val title: String, val url: String)

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
    private val KEY_HOMEPAGE = "homepage_url"
    private val DEFAULT_HOMEPAGE = "https://lite.duckduckgo.com"
    private val KEY_MEDIA_BLOCKING = "media_blocking_enabled"

    fun saveHomepage(url: String) {
        prefs.edit().putString(KEY_HOMEPAGE, url).apply()
    }

    fun loadHomepage(): String {
        return prefs.getString(KEY_HOMEPAGE, DEFAULT_HOMEPAGE) ?: DEFAULT_HOMEPAGE
    }

    fun saveMediaBlocking(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MEDIA_BLOCKING, enabled).apply()
    }

    fun isMediaBlockingEnabled(): Boolean {
        return prefs.getBoolean(KEY_MEDIA_BLOCKING, true) // Default to true (enabled)
    }
}

class BookmarkManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
    private val KEY_BOOKMARKS = "bookmarks_list"
    private val json = Json { ignoreUnknownKeys = true }

    private fun saveBookmarks(bookmarks: List<Bookmark>) {
        val jsonString = json.encodeToString(bookmarks)
        prefs.edit().putString(KEY_BOOKMARKS, jsonString).apply()
    }

    fun getBookmarks(): List<Bookmark> {
        val jsonString = prefs.getString(KEY_BOOKMARKS, null)
        return if (jsonString != null) {
            try {
                json.decodeFromString(jsonString)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    fun addBookmark(bookmark: Bookmark) {
        val currentBookmarks = getBookmarks().toMutableList()
        if (!currentBookmarks.any { it.url == bookmark.url }) {
            currentBookmarks.add(0, bookmark) // Add to top
            saveBookmarks(currentBookmarks)
        }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        val currentBookmarks = getBookmarks().toMutableList()
        currentBookmarks.removeAll { it.url == bookmark.url }
        saveBookmarks(currentBookmarks)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LightBrowserTheme {
                App()
            }
        }
    }
}

@Composable
fun App() {
    var currentScreen by remember { mutableStateOf("browser") }
    var urlToLoad by remember { mutableStateOf<String?>(null) }

    when (currentScreen) {
        "browser" -> BrowserScreen(
            initialUrl = urlToLoad,
            onShowBookmarks = {
                urlToLoad = null // Reset url to load
                currentScreen = "bookmarks"
            }
        )
        "bookmarks" -> BookmarksScreen(
            onBookmarkClicked = { url ->
                urlToLoad = url
                currentScreen = "browser"
            },
            onNavigateBack = {
                urlToLoad = null
                currentScreen = "browser"
            }
        )
    }
}


// Injects JavaScript into the WebView to replace media elements with placeholders.
const val INJECT_JS = """
javascript:(function() {
    // This script now focuses only on creating placeholders for images/videos
    // that have a src attribute but haven't been loaded.
    let elementId = 0;
    function processElement(el) {
        if (!el.getAttribute('data-processed') && el.getAttribute('src')) {
            const originalSrc = el.getAttribute('src');
            const uniqueId = 'media-' + (elementId++);
            el.setAttribute('data-id', uniqueId);
            el.setAttribute('data-src', originalSrc);

            const width = el.clientWidth;
            const height = el.clientHeight;
            if (width > 0 && height > 0) {
                el.style.width = width + 'px';
                el.style.height = height + 'px';
            }
            
            el.style.backgroundColor = '#f0f0f0';
            el.style.border = '1px solid #ccc';
            el.style.minWidth = '50px';
            el.style.minHeight = '50px';

            el.setAttribute('data-processed', 'true');
        }
    }

    document.querySelectorAll('img, video').forEach(processElement);

    const observer = new MutationObserver((mutations) => {
        mutations.forEach((mutation) => {
            mutation.addedNodes.forEach((node) => {
                if (node.nodeType === 1) { // ELEMENT_NODE
                    if (node.matches('img, video')) {
                       processElement(node);
                    }
                    node.querySelectorAll('img, video').forEach(processElement);
                }
            });
        });
    });

    observer.observe(document.body, { childList: true, subtree: true });
})();
"""



@Composable
fun BrowserControls(
    url: String,
    onUrlChange: (String) -> Unit,
    onGo: (androidx.compose.foundation.text.KeyboardActionScope) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onSetHome: () -> Unit,
    onGoHome: () -> Unit,
    onAddBookmark: () -> Unit,
    onShowBookmarks: () -> Unit,
    onToggleMediaBlocking: () -> Unit,
    mediaBlockingEnabled: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = onUrlChange,
                        label = { Text("Enter URL") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = onGo),
                        singleLine = true,
                        maxLines = 1
                    )
                    Spacer(Modifier.width(8.dp))
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More options")
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(if (mediaBlockingEnabled) "Disable Media Blocking" else "Enable Media Blocking") },
                    onClick = {
                        onToggleMediaBlocking()
                        menuExpanded = false
                    }
                )
                Divider()
                DropdownMenuItem(
                    text = { Text("Back") },
                    onClick = {
                        onBack()
                        menuExpanded = false
                    },
                    enabled = canGoBack
                )
                DropdownMenuItem(
                    text = { Text("Forward") },
                    onClick = {
                        onForward()
                        menuExpanded = false
                    },
                    enabled = canGoForward
                )
                DropdownMenuItem(
                    text = { Text("Reload") },
                    onClick = {
                        onReload()
                        menuExpanded = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Go to Homepage") },
                    onClick = {
                        onGoHome()
                        menuExpanded = false
                    }
                )
                Divider()
                DropdownMenuItem(
                    text = { Text("Add Bookmark") },
                    onClick = {
                        onAddBookmark()
                        menuExpanded = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Set as Homepage") },
                    onClick = {
                        onSetHome()
                        menuExpanded = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Bookmarks") },
                    onClick = {
                        onShowBookmarks()
                        menuExpanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(
    onBookmarkClicked: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val bookmarkManager = remember { BookmarkManager(context) }
    var bookmarks by remember { mutableStateOf(bookmarkManager.getBookmarks()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bookmarks") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(modifier = Modifier.padding(innerPadding)) {
            items(bookmarks, key = { it.url }) { bookmark ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onBookmarkClicked(bookmark.url) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(bookmark.title, style = MaterialTheme.typography.bodyLarge)
                        Text(bookmark.url, style = MaterialTheme.typography.bodyMedium)
                    }
                    IconButton(onClick = {
                        bookmarkManager.deleteBookmark(bookmark)
                        bookmarks = bookmarkManager.getBookmarks() // Refresh the list
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete bookmark")
                    }
                }
            }
        }
    }
}


private fun isMediaRequest(request: WebResourceRequest): Boolean {
    val acceptHeader = request.requestHeaders["Accept"]?.lowercase() ?: ""
    // Do not block document navigation
    if (acceptHeader.contains("text/html")) {
        return false
    }
    // Block if the primary content type is an image or video
    if (acceptHeader.startsWith("image/") || acceptHeader.startsWith("video/")) {
        return true
    }
    // Or if the URL extension looks like media
    val url = request.url.toString().lowercase()
    val mediaExtensions = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg", ".mp4", ".webm", ".ogg")
    return mediaExtensions.any { url.endsWith(it) }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(initialUrl: String?, onShowBookmarks: () -> Unit) {
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager(context) }
    val bookmarkManager = remember { BookmarkManager(context) }

    var url by remember { mutableStateOf(initialUrl ?: settingsManager.loadHomepage()) }
    var mediaBlockingEnabled by remember { mutableStateOf(true) }

    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, newUrl: String?) {
                    super.onPageFinished(view, newUrl)
                    newUrl?.let { url = it }
                    canGoBack = view?.canGoBack() ?: false
                    canGoForward = view?.canGoForward() ?: false
                    if (mediaBlockingEnabled) {
                        view?.evaluateJavascript(INJECT_JS, null)
                    }
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    if (request == null) return null
                    if (!mediaBlockingEnabled) return null // Allow all if blocking is disabled

                    if (isMediaRequest(request)) {
                        // Block media requests by returning a valid, empty response
                        return WebResourceResponse(
                            "application/octet-stream",
                            "UTF-8",
                            ByteArray(0).inputStream()
                        )
                    }

                    return null // Proceed with all other requests
                }
            }
        }
    }

    LaunchedEffect(webView.url, webView.canGoBack(), webView.canGoForward()) {
        url = webView.url ?: url
        canGoBack = webView.canGoBack()
        canGoForward = webView.canGoForward()
    }

    BackHandler(enabled = canGoBack) {
        webView.goBack()
    }
    
    Scaffold { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            BrowserControls(
                url = url,
                onUrlChange = { url = it },
                onGo = { _ ->
                    val correctedUrl = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
                    webView.loadUrl(correctedUrl)
                },
                onBack = { webView.goBack() },
                onForward = { webView.goForward() },
                onReload = { webView.reload() },
                onSetHome = {
                    settingsManager.saveHomepage(webView.url ?: url)
                    Toast.makeText(context, "Homepage set!", Toast.LENGTH_SHORT).show()
                },
                onGoHome = {
                    webView.loadUrl(settingsManager.loadHomepage())
                },
                onAddBookmark = {
                    val currentTitle = webView.title ?: "No Title"
                    val currentUrl = webView.url ?: url
                    bookmarkManager.addBookmark(Bookmark(title = currentTitle, url = currentUrl))
                    Toast.makeText(context, "Bookmark added!", Toast.LENGTH_SHORT).show()
                },
                onShowBookmarks = onShowBookmarks,
                onToggleMediaBlocking = {
                    val newState = !mediaBlockingEnabled
                    mediaBlockingEnabled = newState
                    webView.reload()
                },
                mediaBlockingEnabled = mediaBlockingEnabled,
                canGoBack = canGoBack,
                canGoForward = canGoForward
            )

            AndroidView(
                factory = {
                    webView.loadUrl(url)
                    webView
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}
