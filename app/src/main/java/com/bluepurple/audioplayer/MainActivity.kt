package com.bluepurple.audioplayer

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.ffmpeg.FFmpeg
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        runCatching { YoutubeDL.getInstance().init(this); FFmpeg.getInstance().init(this) }
        setContent { AudioPlayerApp() }
    }
}

@Composable
private fun AudioPlayerApp() {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("audio-player", Context.MODE_PRIVATE) }
    val player = remember { ExoPlayer.Builder(context).build() }
    var permission by remember { mutableStateOf(audioPermission(context)) }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var queue by remember { mutableStateOf<List<Song>>(emptyList()) }
    var page by remember { mutableStateOf("Player") }
    var libraryKind by remember { mutableStateOf("Songs") }
    var selectedFolders by remember { mutableStateOf(setOf<String>()) }
    var filter by remember { mutableStateOf("") }
    var coversOnly by remember { mutableStateOf(false) }
    var themeIndex by remember { mutableIntStateOf(prefs.getInt("theme", 0).coerceIn(palettes.indices)) }
    var themeOpen by remember { mutableStateOf(false) }
    var shuffle by remember { mutableStateOf(prefs.getBoolean("shuffle", true)) }
    var repeatMode by remember { mutableIntStateOf(prefs.getInt("repeat", 0).coerceIn(0, 2)) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var playlists by remember { mutableStateOf(loadPlaylists(prefs)) }
    var playlistDialog by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }
    var expandedPlaylist by remember { mutableStateOf("") }
    var downloadUrl by remember { mutableStateOf("") }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var downloadStatus by remember { mutableStateOf("") }
    var downloading by remember { mutableStateOf(false) }
    var downloadRetry by remember { mutableIntStateOf(0) }
    var savedFolderUri by remember { mutableStateOf(prefs.getString("download_folder", null)?.let(Uri::parse)) }
    var savedFolderName by remember { mutableStateOf(prefs.getString("download_folder_name", null) ?: "Music / Audio Player") }
    var completedDownload by remember { mutableStateOf<Song?>(null) }
    var downloadedPlaylistUri by remember { mutableStateOf<String?>(null) }
    var downloadedSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    val palette = palettes[themeIndex]
    val permissionName = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    SideEffect {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !palette.dark
            controller.isAppearanceLightNavigationBars = !palette.dark
        }
    }

    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    LaunchedEffect(permission) {
        if (permission) songs = withContext(Dispatchers.IO) { scanSongs(context) }
    }
    LaunchedEffect(player, queue) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.takeIf { it > 0 } ?: 0L
            playing = player.isPlaying
            delay(300)
        }
    }
    LaunchedEffect(shuffle, repeatMode) {
        player.shuffleModeEnabled = shuffle
        player.repeatMode = when (repeatMode) { 1 -> Player.REPEAT_MODE_ALL; 2 -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            savedFolderUri = uri
            savedFolderName = DocumentFile.fromTreeUri(context, uri)?.name ?: "Selected folder"
            prefs.edit().putString("download_folder", uri.toString()).putString("download_folder_name", savedFolderName).apply()
        }
    }
    val writePermissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) downloadRetry++ else downloadStatus = "Storage permission is needed to save the MP3."
    }

    fun startDownload() {
        val link = downloadUrl.trim()
        if (!isSupportedMediaUrl(link)) { downloadStatus = "Paste a YouTube or SoundCloud link."; return }
        if (downloading) return
        if (Build.VERSION.SDK_INT < 29 && savedFolderUri == null && !audioWritePermission(context)) {
            writePermissionRequest.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        downloading = true
        downloadProgress = 0
        downloadStatus = "Preparing download…"
        scope.launch {
            try {
                val song = withContext(Dispatchers.IO) {
                    val taskDir = File(context.cacheDir, "downloads/${System.currentTimeMillis()}").apply { mkdirs() }
                    val before = taskDir.listFiles()?.toSet().orEmpty()
                    val request = YoutubeDLRequest(link).apply {
                        addOption("--no-playlist")
                        addOption("--extract-audio")
                        addOption("--audio-format", "mp3")
                        addOption("--audio-quality", "0")
                        addOption("--embed-thumbnail")
                        addOption("--convert-thumbnails", "jpg")
                        addOption("--embed-metadata")
                        addOption("--no-mtime")
                        addOption("-o", File(taskDir, "%(title).150B-%(id)s.%(ext)s").absolutePath)
                    }
                    YoutubeDL.getInstance().execute(request) { progress, eta, _ ->
                        scope.launch {
                            downloadProgress = progress.toInt().coerceIn(0, 100)
                            downloadStatus = if (eta > 0) "Downloading · ${eta}s left" else "Downloading MP3…"
                        }
                    }
                    val mp3 = taskDir.listFiles()?.filter { it !in before && it.extension.equals("mp3", true) }?.maxByOrNull { it.lastModified() }
                        ?: throw IllegalStateException("The MP3 file was not created. Try another link or update the download engine.")
                    val uri = saveDownloadedAudio(context, mp3, savedFolderUri)
                    val meta = readAudioMetadata(mp3)
                    Song(uri, meta.title.ifBlank { mp3.nameWithoutExtension }, meta.artist, meta.album, savedFolderName, meta.cover)
                }
                val refreshed = withContext(Dispatchers.IO) { scanSongs(context) }
                songs = if (refreshed.any { it.uri == song.uri }) refreshed else refreshed + song
                downloadedSongs = listOf(song) + downloadedSongs.filterNot { it.uri == song.uri }
                completedDownload = song
                downloadProgress = 100
                downloadStatus = if (song.cover != null) "Downloaded · cover art embedded and checked" else "Downloaded · no cover art was provided by the source"
                downloadUrl = ""
            } catch (error: Exception) {
                downloadStatus = error.message?.lineSequence()?.firstOrNull()?.take(130) ?: "Download failed. Check the link and try again."
            } finally {
                downloading = false
            }
        }
    }
    LaunchedEffect(downloadRetry) { if (downloadRetry > 0) { downloadRetry = 0; startDownload() } }

    fun play(items: List<Song>, start: Int = 0, openPlayer: Boolean = true) {
        if (items.isEmpty()) return
        queue = items
        player.setMediaItems(items.map { MediaItem.fromUri(it.uri) }, start.coerceIn(items.indices), 0L)
        player.prepare(); player.play()
        if (openPlayer) page = "Player"
    }

    val filtered = remember(songs, filter, coversOnly) {
        songs.filter { it.title.contains(filter, true) || it.artist.contains(filter, true) || it.album.contains(filter, true) }
            .filter { !coversOnly || it.cover != null }
    }
    val folders = remember(songs) { songs.groupBy { it.folder.ifBlank { "Music" } }.toSortedMap() }
    val track = queue.getOrNull(player.currentMediaItemIndex)
    val togglePlay: () -> Unit = { if (player.isPlaying) player.pause() else if (player.mediaItemCount > 0) player.play() else play(filtered) }

    Box(Modifier.fillMaxSize().background(palette.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Melody", color = palette.ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(if (permission) "${songs.size} tracks · ${page.lowercase()}" else "Your music, your way", color = palette.muted, fontSize = 14.sp)
                }
                RoundIconButton(IcTheme, "Choose theme", palette) { themeOpen = !themeOpen }
                Spacer(Modifier.width(10.dp))
                RoundIconButton(IcRefresh, "Scan for music", palette) {
                    if (permission) scope.launch { songs = scanSongs(context) } else permissionRequest.launch(permissionName)
                }
            }
            if (themeOpen) Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                    .clip(RoundedCornerShape(24.dp)).background(palette.s1)
                    .border(1.dp, palette.border, RoundedCornerShape(24.dp)).padding(16.dp)
            ) {
                Text("Theme", color = palette.muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(palette.name, color = palette.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    palettes.forEachIndexed { index, item ->
                        val selected = index == themeIndex
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(item.bg)
                                .border(if (selected) 3.dp else 1.dp, if (selected) palette.ink else palette.border, CircleShape)
                                .clickable { themeIndex = index; prefs.edit().putInt("theme", index).apply(); themeOpen = false },
                            contentAlignment = Alignment.Center
                        ) { Box(Modifier.size(18.dp).clip(CircleShape).background(item.accent)) }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (!permission && page != "Download") {
                    PermissionPrompt(palette) { permissionRequest.launch(permissionName) }
                } else when (page) {
                    "Player" -> PlayerPage(track, playing, position, duration, shuffle, repeatMode, palette,
                        onPlay = togglePlay,
                        onPrevious = { if (player.mediaItemCount > 0) player.seekToPreviousMediaItem() },
                        onNext = { if (player.mediaItemCount > 0) player.seekToNextMediaItem() },
                        onSeek = { player.seekTo(it) },
                        onShuffle = { shuffle = !shuffle; prefs.edit().putBoolean("shuffle", shuffle).apply() },
                        onRepeat = { repeatMode = (repeatMode + 1) % 3; prefs.edit().putInt("repeat", repeatMode).apply() })
                    "Folders" -> FolderPage(folders, selectedFolders, palette,
                        onToggle = { name -> selectedFolders = if (name in selectedFolders) selectedFolders - name else selectedFolders + name },
                        onPlay = { names -> play(songs.filter { it.folder.ifBlank { "Music" } in names }) })
                    "Library" -> LibraryPage(filtered, track, filter, { filter = it }, coversOnly, { coversOnly = it }, libraryKind, { libraryKind = it }, palette,
                        onSong = { item -> play(filtered, filtered.indexOf(item).coerceAtLeast(0), openPlayer = false) },
                        onPlay = { items -> play(items) })
                    "Download" -> DownloadPage(downloadUrl, { downloadUrl = it }, downloading, downloadProgress, downloadStatus, savedFolderName, downloadedSongs,
                        onChooseFolder = { folderPicker.launch(null) }, onDownload = { startDownload() }, onPlay = { play(listOf(it)) }, palette = palette)
                    else -> PlaylistPage(playlists, songs, expandedPlaylist, palette,
                        onCreate = { playlistDialog = true },
                        onPlay = { list -> play(list.uris.mapNotNull { uri -> songs.find { it.uri.toString() == uri } }) },
                        onAdd = { list -> track?.let { song -> playlists = playlists.map { if (it.name == list.name && song.uri.toString() !in it.uris) it.copy(uris = it.uris + song.uri.toString()) else it }; savePlaylists(prefs, playlists) } },
                        onRemove = { list, uri -> playlists = playlists.map { if (it.name == list.name) it.copy(uris = it.uris - uri) else it }; savePlaylists(prefs, playlists) },
                        onExpand = { expandedPlaylist = if (expandedPlaylist == it) "" else it })
                }
            }
            if (page != "Player" && track != null) MiniPlayer(track, playing, palette, onOpen = { page = "Player" }, onToggle = togglePlay)
            NavBar(page, palette) { page = it }
        }
    }
    if (playlistDialog) AlertDialog(
        onDismissRequest = { playlistDialog = false; downloadedPlaylistUri = null },
        title = { Text("New playlist", fontWeight = FontWeight.Bold) },
        text = { OutlinedTextField(playlistName, { playlistName = it }, singleLine = true, label = { Text("Name") }, colors = fieldColors(palette)) },
        confirmButton = { TextButton(onClick = { val name = playlistName.trim(); if (name.isNotEmpty() && playlists.none { it.name == name }) { val uri = downloadedPlaylistUri; val uris = uri?.let(::listOf) ?: emptyList(); playlists = playlists + Playlist(name, uris); savePlaylists(prefs, playlists) }; downloadedPlaylistUri = null; playlistName = ""; playlistDialog = false }) { Text("Create", color = palette.accent, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = { playlistDialog = false; downloadedPlaylistUri = null }) { Text("Cancel", color = palette.muted) } },
        containerColor = palette.s2, titleContentColor = palette.ink, textContentColor = palette.muted
    )
    completedDownload?.let { song ->
        AlertDialog(
            onDismissRequest = { completedDownload = null },
            title = { Text("Add to a playlist?", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CoverTile(song, 44.dp, 12.dp, palette)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(song.title, color = palette.ink, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.artist.ifBlank { "Downloaded just now" }, color = palette.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (playlists.isEmpty()) Text("Create a playlist to add this song.", color = palette.muted)
                    playlists.forEach { list -> TextButton(onClick = { playlists = playlists.map { if (it.name == list.name && song.uri.toString() !in it.uris) it.copy(uris = it.uris + song.uri.toString()) else it }; savePlaylists(prefs, playlists); completedDownload = null }) { Text("＋  ${list.name}", color = palette.ink) } }
                    TextButton(onClick = { downloadedPlaylistUri = song.uri.toString(); playlistName = ""; playlistDialog = true; completedDownload = null }) { Text("Create playlist", color = palette.accent, fontWeight = FontWeight.Bold) }
                }
            },
            confirmButton = { TextButton(onClick = { completedDownload = null }) { Text("Not now", color = palette.muted) } },
            containerColor = palette.s2, titleContentColor = palette.ink, textContentColor = palette.muted
        )
    }
}

@Composable
private fun fieldColors(p: Palette) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = p.accent, unfocusedBorderColor = p.border,
    focusedTextColor = p.ink, unfocusedTextColor = p.ink,
    focusedContainerColor = p.s1, unfocusedContainerColor = p.s1,
    cursorColor = p.accent,
    focusedPlaceholderColor = p.muted, unfocusedPlaceholderColor = p.muted,
    focusedLabelColor = p.accent, unfocusedLabelColor = p.muted
)

@Composable
private fun RoundIconButton(icon: ImageVector, label: String, palette: Palette, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(palette.s1).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(22.dp), tint = palette.ink)
    }
}

@Composable
private fun IconBtn(icon: ImageVector, label: String, tint: Color, size: Dp = 24.dp, badge: String? = null, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(size), tint = tint)
        if (badge != null) Text(badge, color = tint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PillButton(label: String, icon: ImageVector, palette: Palette, onClick: () -> Unit) {
    Row(
        Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(palette.accent).clickable(onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = palette.onAccent)
        Text(label, color = palette.onAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CoverTile(song: Song?, size: Dp, radius: Dp, palette: Palette) {
    Box(Modifier.size(size).clip(RoundedCornerShape(radius)).background(palette.tint), contentAlignment = Alignment.Center) {
        if (song?.cover != null) Image(song.cover.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(IcNote, null, Modifier.size(size / 2), tint = palette.muted)
    }
}

@Composable
private fun PermissionPrompt(palette: Palette, onAllow: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(88.dp).clip(CircleShape).background(palette.tint), contentAlignment = Alignment.Center) {
            Icon(IcNote, null, Modifier.size(40.dp), tint = palette.ink)
        }
        Spacer(Modifier.height(20.dp))
        Text("Choose music access", color = palette.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Allow access to find songs on this device.", color = palette.muted, fontSize = 15.sp)
        Spacer(Modifier.height(20.dp))
        PillButton("Allow", IcCheck, palette, onAllow)
    }
}

@Composable
private fun NavBar(page: String, palette: Palette, onPage: (String) -> Unit) {
    val tabs = listOf("Player" to IcNote, "Folders" to IcFolder, "Library" to IcGrid, "Download" to IcDownload, "Playlists" to IcList)
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp).height(64.dp)
            .clip(RoundedCornerShape(32.dp)).background(palette.s1).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        tabs.forEach { (name, icon) ->
            val selected = page == name
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(26.dp))
                    .background(if (selected) palette.tint else Color.Transparent).clickable { onPage(name) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
            ) {
                Icon(icon, null, Modifier.size(22.dp), tint = if (selected) palette.ink else palette.muted)
                Text(name, color = if (selected) palette.ink else palette.muted, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

@Composable
private fun MiniPlayer(song: Song, playing: Boolean, palette: Palette, onOpen: () -> Unit, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp).height(64.dp)
            .clip(RoundedCornerShape(22.dp)).background(palette.s2).clickable(onClick = onOpen).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverTile(song, 44.dp, 12.dp, palette)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(song.title, color = palette.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist.ifBlank { song.album }, color = palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(42.dp).clip(CircleShape).background(palette.accent).clickable(onClick = onToggle), contentAlignment = Alignment.Center) {
            Icon(if (playing) IcPause else IcPlay, if (playing) "Pause" else "Play", Modifier.size(20.dp), tint = palette.onAccent)
        }
    }
}

@Composable
private fun PlayerPage(
    song: Song?, playing: Boolean, position: Long, duration: Long, shuffle: Boolean, repeatMode: Int, palette: Palette,
    onPlay: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onSeek: (Long) -> Unit, onShuffle: () -> Unit, onRepeat: () -> Unit
) {
    var seekDraft by remember { mutableFloatStateOf(0f) }
    var isSeeking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.widthIn(max = 340.dp).fillMaxWidth().weight(1f, fill = false).aspectRatio(1f)
                .clip(RoundedCornerShape(38.dp)).background(Brush.linearGradient(listOf(palette.accent, palette.tint))),
            contentAlignment = Alignment.Center
        ) {
            if (song?.cover != null) Image(song.cover.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(IcNote, null, Modifier.size(84.dp), tint = palette.onAccent.copy(alpha = .85f))
                Text("No artwork", color = palette.onAccent.copy(alpha = .85f), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(22.dp))
        Text(song?.title ?: "No track", color = palette.ink, fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(
            listOfNotNull(song?.artist?.takeIf { it.isNotBlank() }, song?.album?.takeIf { it.isNotBlank() }).joinToString(" · "),
            color = palette.muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Slider(
            value = if (isSeeking) seekDraft else if (duration > 0) position.toFloat().coerceIn(0f, duration.toFloat()) else 0f,
            onValueChange = { seekDraft = it; isSeeking = true },
            valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
            onValueChangeFinished = { onSeek(seekDraft.toLong()); isSeeking = false },
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            colors = SliderDefaults.colors(thumbColor = palette.ink, activeTrackColor = palette.accent, inactiveTrackColor = palette.track)
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(time(position), color = palette.muted, fontSize = 12.sp)
            Text(time(duration), color = palette.muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconBtn(IcShuffle, "Shuffle", if (shuffle) palette.accent else palette.muted, onClick = onShuffle)
            IconBtn(IcPrev, "Previous track", palette.ink, 28.dp, onClick = onPrevious)
            Box(Modifier.size(72.dp).clip(CircleShape).background(palette.accent).clickable(onClick = onPlay), contentAlignment = Alignment.Center) {
                Icon(if (playing) IcPause else IcPlay, if (playing) "Pause" else "Play", Modifier.size(30.dp), tint = palette.onAccent)
            }
            IconBtn(IcNext, "Next track", palette.ink, 28.dp, onClick = onNext)
            IconBtn(IcRepeat, "Repeat", if (repeatMode > 0) palette.accent else palette.muted, badge = if (repeatMode == 2) "1" else null, onClick = onRepeat)
        }
        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun FolderPage(folders: Map<String, List<Song>>, selected: Set<String>, palette: Palette, onToggle: (String) -> Unit, onPlay: (Set<String>) -> Unit) {
    val count = folders.filterKeys { it in selected }.values.sumOf { it.size }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 6.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${selected.size} selected · $count songs", Modifier.weight(1f), color = palette.muted, fontSize = 14.sp)
            PillButton("Play selected", IcPlay, palette) { onPlay(selected.ifEmpty { folders.keys }) }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(folders.toList(), key = { it.first }) { (name, tracks) ->
                val on = name in selected
                Row(
                    Modifier.fillMaxWidth().height(68.dp).clip(RoundedCornerShape(20.dp))
                        .background(if (on) palette.s2 else palette.s1)
                        .border(1.5.dp, if (on) palette.accent else Color.Transparent, RoundedCornerShape(20.dp))
                        .clickable { onToggle(name) }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(24.dp).clip(RoundedCornerShape(8.dp)).background(if (on) palette.accent else Color.Transparent)
                            .then(if (on) Modifier else Modifier.border(2.dp, palette.muted, RoundedCornerShape(8.dp))),
                        contentAlignment = Alignment.Center
                    ) { if (on) Icon(IcCheck, null, Modifier.size(16.dp), tint = palette.onAccent) }
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(palette.tint), contentAlignment = Alignment.Center) {
                        Icon(IcFolder, null, Modifier.size(20.dp), tint = palette.ink)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(name, color = palette.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${tracks.size} songs", color = palette.muted, fontSize = 13.sp)
                    }
                    Box(Modifier.size(40.dp).clip(CircleShape).background(palette.tint).clickable { onPlay(setOf(name)) }, contentAlignment = Alignment.Center) {
                        Icon(IcPlay, "Play $name", Modifier.size(18.dp), tint = palette.ink)
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryPage(
    songs: List<Song>, current: Song?, filter: String, onFilter: (String) -> Unit, coversOnly: Boolean, onCovers: (Boolean) -> Unit,
    kind: String, onKind: (String) -> Unit, palette: Palette, onSong: (Song) -> Unit, onPlay: (List<Song>) -> Unit
) {
    val groups: Map<String, List<Song>> = remember(songs, kind) {
        if (kind == "Songs") emptyMap()
        else songs.groupBy { if (kind == "Albums") it.album.ifBlank { "No album" } else it.artist.ifBlank { "No artist" } }.toSortedMap()
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            filter, onFilter, Modifier.fillMaxWidth().padding(horizontal = 24.dp), singleLine = true, shape = RoundedCornerShape(28.dp),
            placeholder = { Text("Filter songs", color = palette.muted) },
            leadingIcon = { Icon(IcSearch, null, Modifier.size(20.dp), tint = palette.muted) },
            colors = fieldColors(palette)
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.clip(RoundedCornerShape(20.dp)).background(palette.s1).padding(3.dp)) {
                listOf("Songs", "Albums", "Artists").forEach { name ->
                    val selected = name == kind
                    Box(
                        Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(if (selected) palette.accent else Color.Transparent)
                            .clickable { onKind(name) }.padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(name, color = if (selected) palette.onAccent else palette.muted, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.height(36.dp).clip(RoundedCornerShape(18.dp))
                    .background(if (coversOnly) palette.tint else Color.Transparent)
                    .border(1.dp, if (coversOnly) palette.accent else palette.border, RoundedCornerShape(18.dp))
                    .clickable { onCovers(!coversOnly) }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (coversOnly) Icon(IcCheck, null, Modifier.size(16.dp), tint = palette.ink)
                Text("Covers", color = if (coversOnly) palette.ink else palette.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        if (songs.isEmpty()) Text("No songs match.", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = palette.muted, fontSize = 14.sp)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
            if (kind == "Songs") {
                items(songs, key = { it.uri.toString() }) { item -> SongRow(item, item.uri == current?.uri, palette) { onSong(item) } }
            } else {
                items(groups.toList(), key = { it.first }) { (name, items) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable { onPlay(items) }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CoverTile(items.firstOrNull { it.cover != null } ?: items.firstOrNull(), 48.dp, 12.dp, palette)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(name, color = palette.ink, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${items.size} tracks", color = palette.muted, fontSize = 13.sp)
                        }
                        Icon(IcPlay, "Play $name", Modifier.size(18.dp), tint = palette.ink)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistPage(playlists: List<Playlist>, songs: List<Song>, expanded: String, palette: Palette, onCreate: () -> Unit, onPlay: (Playlist) -> Unit, onAdd: (Playlist) -> Unit, onRemove: (Playlist, String) -> Unit, onExpand: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 6.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${playlists.size} playlists", Modifier.weight(1f), color = palette.muted, fontSize = 14.sp)
            PillButton("Create", IcPlus, palette, onCreate)
        }
        if (playlists.isEmpty()) Text("Create your first playlist to collect songs.", color = palette.muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(playlists, key = { it.name }) { list ->
                val members = list.uris.mapNotNull { uri -> songs.find { it.uri.toString() == uri } }
                val open = expanded == list.name
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(if (open) palette.s2 else palette.s1).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Mosaic(members, palette)
                        Column(Modifier.weight(1f).clickable { onExpand(list.name) }.padding(horizontal = 12.dp, vertical = 4.dp)) {
                            Text(list.name, color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${list.uris.size} songs", color = palette.muted, fontSize = 13.sp)
                        }
                        Box(Modifier.size(40.dp).clip(CircleShape).background(palette.tint).clickable { onAdd(list) }, contentAlignment = Alignment.Center) {
                            Icon(IcPlus, "Add current song to ${list.name}", Modifier.size(20.dp), tint = palette.ink)
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(40.dp).clip(CircleShape).background(palette.accent).clickable { onPlay(list) }, contentAlignment = Alignment.Center) {
                            Icon(IcPlay, "Play ${list.name}", Modifier.size(18.dp), tint = palette.onAccent)
                        }
                    }
                    if (open) {
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.border))
                        if (members.isEmpty()) Text("No songs yet. Play a song, then tap + to add it.", Modifier.padding(top = 10.dp), color = palette.muted, fontSize = 13.sp)
                        members.forEach { song ->
                            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                                CoverTile(song, 32.dp, 9.dp, palette)
                                Text(song.title, Modifier.weight(1f).padding(horizontal = 12.dp), color = palette.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Box(Modifier.size(36.dp).clip(CircleShape).clickable { onRemove(list, song.uri.toString()) }, contentAlignment = Alignment.Center) {
                                    Icon(IcClose, "Remove ${song.title}", Modifier.size(18.dp), tint = palette.muted)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Mosaic(members: List<Song>, palette: Palette) {
    Column(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(palette.tint), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (row in 0..1) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (col in 0..1) {
                    val cover = members.getOrNull(row * 2 + col)?.cover
                    Box(Modifier.weight(1f).fillMaxHeight().background(palette.track)) {
                        if (cover != null) Image(cover.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadPage(
    link: String, onLink: (String) -> Unit, downloading: Boolean, progress: Int, status: String,
    folderName: String, downloads: List<Song>, onChooseFolder: () -> Unit, onDownload: () -> Unit, onPlay: (Song) -> Unit, palette: Palette
) {
    val errorColor = if (palette.dark) Color(0xFFFFB8C4) else Color(0xFFB3261E)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SourceChip("YouTube", IcPlay, true, palette)
            SourceChip("SoundCloud", IcCloud, false, palette)
        }
        Text("Music link", color = palette.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
        OutlinedTextField(
            value = link, onValueChange = onLink, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            placeholder = { Text("Paste a YouTube or SoundCloud link", color = palette.muted) },
            leadingIcon = { Icon(IcLink, null, Modifier.size(20.dp), tint = palette.muted) },
            singleLine = true, shape = RoundedCornerShape(16.dp), colors = fieldColors(palette)
        )
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.height(28.dp).clip(RoundedCornerShape(10.dp)).background(palette.tint).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Text("MP3", color = palette.ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Text("  Cover art embedded", color = palette.muted, fontSize = 13.sp)
        }
        Button(
            onClick = onDownload, enabled = !downloading,
            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.onAccent, disabledContainerColor = palette.accent.copy(alpha = .45f), disabledContentColor = palette.onAccent)
        ) {
            Icon(IcDownload, null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(if (downloading) "Downloading $progress%" else "Download track", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        Row(Modifier.fillMaxWidth().clickable(onClick = onChooseFolder).padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(IcFolder, null, Modifier.size(20.dp), tint = palette.accent)
            Text("  Save to  ", color = palette.muted, fontSize = 13.sp)
            Text(folderName, modifier = Modifier.weight(1f), color = palette.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(IcChevron, null, Modifier.size(18.dp), tint = palette.muted)
        }
        if (status.isNotBlank() || downloading) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(palette.s2).padding(horizontal = 16.dp, vertical = 14.dp)) {
            val isError = status.contains("failed", true) || status.contains("need", true) || status.contains("paste", true)
            Text(status, color = if (isError) errorColor else palette.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (downloading) Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(palette.track)) {
                Box(Modifier.fillMaxWidth(progress / 100f).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(palette.accent))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Recent downloads", color = palette.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("MP3", color = palette.muted, fontSize = 12.sp)
        }
        if (downloads.isEmpty()) Text("Your finished downloads will appear here and in Library.", Modifier.padding(top = 8.dp), color = palette.muted, fontSize = 13.sp)
        else downloads.take(6).forEach { SongRow(it, false, palette) { onPlay(it) } }
    }
}

@Composable
private fun SourceChip(label: String, icon: ImageVector, active: Boolean, palette: Palette) {
    Row(
        Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(if (active) palette.tint else palette.s1).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = if (active) palette.ink else palette.muted)
        Text(label, color = if (active) palette.ink else palette.muted, fontSize = 13.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun SongRow(song: Song, current: Boolean, palette: Palette, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (current) palette.s2 else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverTile(song, 44.dp, 12.dp, palette)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(song.title, color = if (current) palette.accent else palette.ink, fontSize = 15.sp, fontWeight = if (current) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist.ifBlank { song.album }, color = palette.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (current) Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom, modifier = Modifier.height(16.dp)) {
            Box(Modifier.width(3.dp).height(10.dp).clip(RoundedCornerShape(2.dp)).background(palette.accent))
            Box(Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(palette.accent))
            Box(Modifier.width(3.dp).height(7.dp).clip(RoundedCornerShape(2.dp)).background(palette.accent))
        } else Icon(IcPlay, null, Modifier.size(16.dp), tint = palette.muted)
    }
}

private fun audioPermission(context: Context): Boolean {
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

private fun audioWritePermission(context: Context): Boolean = Build.VERSION.SDK_INT >= 29 ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

private fun isSupportedMediaUrl(value: String): Boolean = try {
    val uri = Uri.parse(value)
    val allowed = setOf("youtube.com", "www.youtube.com", "music.youtube.com", "m.youtube.com", "youtu.be", "soundcloud.com", "www.soundcloud.com", "on.soundcloud.com")
    uri.scheme == "https" && uri.host?.lowercase(Locale.ROOT) in allowed
} catch (_: Exception) { false }

private fun saveDownloadedAudio(context: Context, file: File, treeUri: Uri?): Uri {
    if (treeUri != null) {
        val folder = DocumentFile.fromTreeUri(context, treeUri) ?: error("The selected folder is no longer available.")
        val target = folder.createFile("audio/mpeg", file.name) ?: error("Could not create an MP3 in the selected folder.")
        context.contentResolver.openOutputStream(target.uri, "w")?.use { output -> FileInputStream(file).use { it.copyTo(output) } }
            ?: error("Could not write to the selected folder.")
        return target.uri
    }
    if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Audio.Media.TITLE, file.nameWithoutExtension)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Audio Player")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: error("Could not save the MP3 to Music.")
        try {
            context.contentResolver.openOutputStream(uri, "w")?.use { output -> FileInputStream(file).use { it.copyTo(output) } }
                ?: error("Could not write the downloaded MP3.")
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (error: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
    }
    val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Audio Player")
    if (!folder.exists() && !folder.mkdirs()) error("Could not create the Music/Audio Player folder.")
    val target = File(folder, file.name)
    FileInputStream(file).use { input -> FileOutputStream(target).use { output -> input.copyTo(output) } }
    android.media.MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf("audio/mpeg"), null)
    return Uri.fromFile(target)
}

private data class AudioMetadata(val title: String, val artist: String, val album: String, val cover: Bitmap?)

private fun readAudioMetadata(file: File): AudioMetadata {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        val cover = retriever.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        AudioMetadata(
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty().takeUnless { it.equals("<unknown>", true) }.orEmpty(),
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty().takeUnless { it.equals("<unknown>", true) }.orEmpty(),
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty().takeUnless { it.equals("<unknown>", true) }.orEmpty(),
            cover
        )
    } finally { retriever.release() }
}

private suspend fun scanSongs(context: Context): List<Song> = withContext(Dispatchers.IO) {
    val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val folderColumn = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA
    val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, folderColumn)
    val result = mutableListOf<Song>()
    val covers = mutableMapOf<Long, Bitmap?>()
    context.contentResolver.query(collection, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        val folderCol = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val uri = ContentUris.withAppendedId(collection, id)
            val albumId = cursor.getLong(albumIdCol)
            val artUri = Uri.parse("content://media/external/audio/albumart/$albumId")
            val cover = (if (albumId > 0) covers.getOrPut(albumId) { decodeCover(context, artUri) } else null) ?: decodeEmbeddedCover(context, ContentUris.withAppendedId(collection, id))
            val rawFolder = if (folderCol >= 0) cursor.getString(folderCol).orEmpty() else "Music"
            val folder = if (Build.VERSION.SDK_INT >= 29) rawFolder.trim('/').substringAfterLast('/') else rawFolder.substringBeforeLast('/', "Music")
            result += Song(uri, cursor.getString(titleCol).orEmpty().ifBlank { "Untitled" }, cursor.getString(artistCol).orEmpty().takeUnless { it.equals("<unknown>", true) }.orEmpty(), cursor.getString(albumCol).orEmpty().takeUnless { it.equals("<unknown>", true) }.orEmpty(), folder.ifBlank { "Music" }, cover)
        }
    }
    result
}

private fun decodeCover(context: Context, uri: Uri): Bitmap? = try {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(input, null, bounds)
        val sample = BitmapFactory.Options().apply { inSampleSize = 4 }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, sample) }
    }
} catch (_: Exception) { null }

private fun decodeEmbeddedCover(context: Context, uri: Uri): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        retriever.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    } catch (_: Exception) { null } finally { retriever.release() }
}

private fun time(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) / 1000).toInt()
    return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}

private fun loadPlaylists(prefs: android.content.SharedPreferences): List<Playlist> = try {
    val json = JSONArray(prefs.getString("playlists", "[]") ?: "[]")
    (0 until json.length()).map { index ->
        val item = json.getJSONObject(index)
        val uris = item.getJSONArray("uris")
        Playlist(item.getString("name"), (0 until uris.length()).map { uris.getString(it) })
    }
} catch (_: Exception) { emptyList() }

private fun savePlaylists(prefs: android.content.SharedPreferences, playlists: List<Playlist>) {
    val json = JSONArray()
    playlists.forEach { list ->
        val uris = JSONArray(); list.uris.forEach(uris::put)
        json.put(org.json.JSONObject().put("name", list.name).put("uris", uris))
    }
    prefs.edit().putString("playlists", json.toString()).apply()
}

