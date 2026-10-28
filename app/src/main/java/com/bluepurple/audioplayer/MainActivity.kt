package com.bluepurple.audioplayer

import android.Manifest
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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

private data class Song(
    val uri: Uri, val title: String, val artist: String, val album: String,
    val folder: String, val cover: Bitmap?
)
private data class Playlist(val name: String, val uris: List<String>)
private data class Palette(val name: String, val colors: List<Color>, val ink: Color, val muted: Color, val accent: Color, val dark: Boolean)

private val palettes = listOf(
    Palette("Blue Purple", listOf(Color(0xFF5784C6), Color(0xFF717FC3), Color(0xFF9470B9)), Color.White, Color(0xFFE6E8FF), Color(0xFFB9A1FF), true),
    Palette("Aurora", listOf(Color(0xFF070B12), Color(0xFF0B2528), Color(0xFF12364A)), Color(0xFFE8FFF7), Color(0xFFABD3CC), Color(0xFF53E2B5), true),
    Palette("Violet Dusk", listOf(Color(0xFF100F19), Color(0xFF21182F), Color(0xFF302347)), Color(0xFFF7EFFF), Color(0xFFD2BFEC), Color(0xFFC994FF), true),
    Palette("Ice Blue", listOf(Color(0xFFDCEEF4), Color(0xFFAFCDE0), Color(0xFF9BA9D8)), Color(0xFF20364B), Color(0xFF4C647B), Color(0xFF477FA5), false),
    Palette("Sunset", listOf(Color(0xFFFFE1A4), Color(0xFFFFA980), Color(0xFFE7889D)), Color(0xFF542D22), Color(0xFF805044), Color(0xFFBD4D38), false),
    Palette("Rose", listOf(Color(0xFFFFD2DF), Color(0xFFF3A8C9), Color(0xFFB9A0F1)), Color(0xFF4D2740), Color(0xFF77445F), Color(0xFFB94878), false),
    Palette("Ocean", listOf(Color(0xFFC5F0EA), Color(0xFF90D1F2), Color(0xFFA8AEF3)), Color(0xFF18364E), Color(0xFF365E77), Color(0xFF247D9B), false)
)

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
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("audio-player", Context.MODE_PRIVATE) }
    val player = remember { ExoPlayer.Builder(context).build() }
    var permission by remember { mutableStateOf(audioPermission(context)) }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var queue by remember { mutableStateOf<List<Song>>(emptyList()) }
    var page by remember { mutableStateOf("Player") }
    var groupKind by remember { mutableStateOf("Album") }
    var selectedFolders by remember { mutableStateOf(setOf<String>()) }
    var filter by remember { mutableStateOf("") }
    var coversOnly by remember { mutableStateOf(false) }
    var themeIndex by remember { mutableIntStateOf(prefs.getInt("theme", 0).coerceIn(palettes.indices)) }
    var themeOpen by remember { mutableStateOf(false) }
    var mode by remember { mutableIntStateOf(1) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var volume by remember { mutableFloatStateOf(0.7f) }
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
    LaunchedEffect(volume) { player.volume = volume }
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

    fun play(items: List<Song>, start: Int = 0) {
        if (items.isEmpty()) return
        queue = items
        player.shuffleModeEnabled = mode == 1
        player.repeatMode = when (mode) { 2 -> Player.REPEAT_MODE_ALL; 3 -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
        player.setMediaItems(items.map { MediaItem.fromUri(it.uri) }, start.coerceIn(items.indices), 0L)
        player.prepare(); player.play()
        page = "Player"
    }

    val filtered = remember(songs, filter, coversOnly) {
        songs.filter { it.title.contains(filter, true) || it.artist.contains(filter, true) || it.album.contains(filter, true) }
            .filter { !coversOnly || it.cover != null }
    }
    val folders = remember(songs) { songs.groupBy { it.folder.ifBlank { "Music" } }.toSortedMap() }
    val track = queue.getOrNull(player.currentMediaItemIndex)

    Box(Modifier.fillMaxSize().background(Brush.linearGradient(palette.colors))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Melody", color = palette.ink, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
                    Text(if (permission) "${songs.size} tracks · ${page.lowercase()}" else "Your music, your way", color = palette.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = { themeOpen = !themeOpen }, colors = ButtonDefaults.textButtonColors(contentColor = palette.ink)) { Text("◉", fontSize = 25.sp) }
                TextButton(onClick = { if (permission) scope.launch { songs = scanSongs(context) } else permissionRequest.launch(permissionName) }, colors = ButtonDefaults.textButtonColors(contentColor = palette.ink)) { Text("Scan", fontWeight = FontWeight.Bold) }
            }
            if (themeOpen) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                palettes.forEachIndexed { index, item ->
                    Box(Modifier.size(34.dp).clip(CircleShape).background(Brush.linearGradient(item.colors))
                        .clickable { themeIndex = index; prefs.edit().putInt("theme", index).apply(); themeOpen = false }
                        .then(if (index == themeIndex) Modifier.background(Color.Transparent, CircleShape) else Modifier), contentAlignment = Alignment.Center) {
                        if (index == themeIndex) Text("✓", color = item.ink, fontSize = 17.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
            if (!permission && page != "Download") {
                Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("♫", color = palette.ink, fontSize = 54.sp)
                    Text("Choose music access", color = palette.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Allow access to find songs on this device.", color = palette.muted, fontSize = 15.sp)
                    Button(onClick = { permissionRequest.launch(permissionName) }, colors = ButtonDefaults.buttonColors(containerColor = palette.accent)) { Text("Allow", color = palette.ink, fontWeight = FontWeight.Bold) }
                }
            } else when (page) {
                "Player" -> PlayerPage(track, queue, playing, position, duration, volume, mode, palette,
                    onPlay = { if (player.isPlaying) player.pause() else if (player.mediaItemCount > 0) player.play() else play(filtered) },
                    onPrevious = { if (player.mediaItemCount > 0) player.seekToPreviousMediaItem() },
                    onNext = { if (player.mediaItemCount > 0) player.seekToNextMediaItem() },
                    onSeek = { player.seekTo(it) }, onVolume = { volume = it; player.volume = it },
                    onMode = { mode = (mode + 1) % 4; player.shuffleModeEnabled = mode == 1; player.repeatMode = when (mode) { 2 -> Player.REPEAT_MODE_ALL; 3 -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } },
                    onFilter = { filter = it }, filter = filter, coversOnly = coversOnly, onCovers = { coversOnly = it }, songs = filtered,
                    onSelectSong = { item -> play(filtered, filtered.indexOf(item).coerceAtLeast(0)) }, ink = palette.ink, muted = palette.muted, accent = palette.accent)
                "Folders" -> FolderPage(folders, selectedFolders, palette,
                    onToggle = { name -> selectedFolders = if (name in selectedFolders) selectedFolders - name else selectedFolders + name },
                    onPlay = { names -> val items = filtered.filter { it.folder.ifBlank { "Music" } in names }; play(items) })
                "Library" -> LibraryPage(filtered, groupKind, palette,
                    onKind = { groupKind = it }, onPlay = { items -> play(items) })
                "Download" -> DownloadPage(downloadUrl, { downloadUrl = it }, downloading, downloadProgress, downloadStatus, savedFolderName, downloadedSongs,
                    onChooseFolder = { folderPicker.launch(null) }, onDownload = { startDownload() }, onPlay = { play(listOf(it)) }, palette = palette)
                else -> PlaylistPage(playlists, filtered, expandedPlaylist, palette,
                    onCreate = { playlistDialog = true },
                    onPlay = { list -> play(list.uris.mapNotNull { uri -> songs.find { it.uri.toString() == uri } }) },
                    onAdd = { list -> track?.let { song -> playlists = playlists.map { if (it.name == list.name && song.uri.toString() !in it.uris) it.copy(uris = it.uris + song.uri.toString()) else it }; savePlaylists(prefs, playlists) } },
                    onRemove = { list, uri -> playlists = playlists.map { if (it.name == list.name) it.copy(uris = it.uris - uri) else it }; savePlaylists(prefs, playlists) },
                    onExpand = { expandedPlaylist = if (expandedPlaylist == it) "" else it })
            }
            Row(Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 7.dp).clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = if (palette.dark) .28f else .13f)).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf("Player" to "♫", "Folders" to "▤", "Library" to "◫", "Download" to "↓", "Playlists" to "☷").forEach { (name, icon) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(15.dp)).background(if (page == name) palette.accent.copy(alpha = if (palette.dark) .35f else .25f) else Color.Transparent).clickable { page = name }.padding(vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(icon, color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(name, color = if (page == name) palette.ink else palette.muted, fontSize = 10.sp, fontWeight = if (page == name) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                    }
                }
            }
        }
    }
    if (playlistDialog) AlertDialog(
        onDismissRequest = { playlistDialog = false; downloadedPlaylistUri = null },
        title = { Text("New playlist", fontWeight = FontWeight.Bold) },
        text = { OutlinedTextField(playlistName, { playlistName = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { val name = playlistName.trim(); if (name.isNotEmpty() && playlists.none { it.name == name }) { val uri = downloadedPlaylistUri; val uris = uri?.let(::listOf) ?: emptyList(); playlists = playlists + Playlist(name, uris); savePlaylists(prefs, playlists) }; downloadedPlaylistUri = null; playlistName = ""; playlistDialog = false }) { Text("Create") } },
        dismissButton = { TextButton(onClick = { playlistDialog = false; downloadedPlaylistUri = null }) { Text("Cancel") } }
    )
    completedDownload?.let { song ->
        AlertDialog(
            onDismissRequest = { completedDownload = null },
            title = { Text("Add to a playlist?", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(song.title, color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    if (playlists.isEmpty()) Text("Create a playlist to add this song.", color = palette.muted)
                    playlists.forEach { list -> TextButton(onClick = { playlists = playlists.map { if (it.name == list.name && song.uri.toString() !in it.uris) it.copy(uris = it.uris + song.uri.toString()) else it }; savePlaylists(prefs, playlists); completedDownload = null }) { Text("＋  ${list.name}", color = palette.ink) } }
                    TextButton(onClick = { downloadedPlaylistUri = song.uri.toString(); playlistName = ""; playlistDialog = true; completedDownload = null }) { Text("Create playlist", color = palette.accent, fontWeight = FontWeight.Bold) }
                }
            },
            confirmButton = { TextButton(onClick = { completedDownload = null }) { Text("Not now") } },
            containerColor = Color(0xFF242541), titleContentColor = palette.ink, textContentColor = palette.muted
        )
    }
}

@Composable
private fun ColumnScope.PlayerPage(
    song: Song?, queue: List<Song>, playing: Boolean, position: Long, duration: Long, volume: Float, mode: Int, palette: Palette,
    onPlay: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onSeek: (Long) -> Unit, onVolume: (Float) -> Unit,
    onMode: () -> Unit, onFilter: (String) -> Unit, filter: String, coversOnly: Boolean, onCovers: (Boolean) -> Unit,
    songs: List<Song>, onSelectSong: (Song) -> Unit, ink: Color, muted: Color, accent: Color
) {
    var seekDraft by remember { mutableFloatStateOf(0f) }
    var isSeeking by remember { mutableStateOf(false) }
    Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(5.dp))
        Box(Modifier.widthIn(max = 290.dp).fillMaxWidth().weight(1f, fill = false).aspectRatio(1f).clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(palette.colors)), contentAlignment = Alignment.Center) {
            if (song?.cover != null) Image(song.cover.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            else Text("♫\nNo artwork", color = ink, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(18.dp))
        Text(song?.title ?: "No track", color = ink, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(song?.artist?.takeIf { it.isNotBlank() } ?: "", color = muted, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(song?.album?.takeIf { it.isNotBlank() } ?: "", color = muted, fontSize = 14.sp, maxLines = 1)
        Slider(value = if (isSeeking) seekDraft else if (duration > 0) position.toFloat().coerceIn(0f, duration.toFloat()) else 0f,
            onValueChange = { seekDraft = it; isSeeking = true }, valueRange = 0f..duration.coerceAtLeast(1L).toFloat(), onValueChangeFinished = { onSeek(seekDraft.toLong()); isSeeking = false },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp), colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = ink.copy(alpha = .25f)))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(time(position), color = muted, fontSize = 12.sp); Text(time(duration), color = muted, fontSize = 12.sp) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconText("|◀", ink, onPrevious)
            Surface(Modifier.size(62.dp).clickable(onClick = onPlay), shape = CircleShape, color = accent) { Box(contentAlignment = Alignment.Center) { Text(if (playing) "Ⅱ" else "▶", color = palette.ink, fontSize = 26.sp, fontWeight = FontWeight.Black) } }
            IconText("▶|", ink, onNext)
            IconText(when (mode) { 2 -> "⟳"; 3 -> "↻¹"; else -> "↻" }, ink, onMode)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("◖", color = muted, fontSize = 20.sp)
            Slider(volume, onVolume, Modifier.weight(1f), colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(filter, onFilter, Modifier.weight(1f), singleLine = true, placeholder = { Text("Filter songs") })
            Checkbox(coversOnly, onCovers)
            Text("Cover", color = muted, fontSize = 13.sp)
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 145.dp)) {
            items(songs.take(100), key = { it.uri.toString() }) { item -> SongRow(item, ink, muted, onClick = { onSelectSong(item) }) }
        }
    }
}

@Composable
private fun ColumnScope.FolderPage(folders: Map<String, List<Song>>, selected: Set<String>, palette: Palette, onToggle: (String) -> Unit, onPlay: (Set<String>) -> Unit) {
    Column(Modifier.weight(1f).fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Folders", color = palette.ink, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Button(onClick = { onPlay(selected.ifEmpty { folders.keys }) }, colors = ButtonDefaults.buttonColors(containerColor = palette.accent)) { Text("▶ Play selected", color = palette.ink, fontWeight = FontWeight.Bold) }
        }
        LazyColumn { items(folders.toList(), key = { it.first }) { (name, tracks) ->
            Row(Modifier.fillMaxWidth().clickable { onToggle(name) }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(name in selected, { onToggle(name) }); Text(name, Modifier.weight(1f), color = palette.ink, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${tracks.size}", color = palette.muted)
                TextButton(onClick = { onPlay(setOf(name)) }) { Text("▶", color = palette.ink) }
            }
        } }
    }
}

@Composable
private fun ColumnScope.LibraryPage(songs: List<Song>, kind: String, palette: Palette, onKind: (String) -> Unit, onPlay: (List<Song>) -> Unit) {
    val groups = remember(songs, kind) { songs.groupBy { if (kind == "Album") it.album.ifBlank { "No album" } else it.artist.ifBlank { "No artist" } }.toSortedMap() }
    Column(Modifier.weight(1f).fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Library", color = palette.ink, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            TextButton(onClick = { onKind(if (kind == "Album") "Artist" else "Album") }) { Text(kind, color = palette.ink, fontWeight = FontWeight.Bold) }
        }
        LazyColumn { items(groups.toList(), key = { it.first }) { (name, items) ->
            Row(Modifier.fillMaxWidth().clickable { onPlay(items) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(name, color = palette.ink, fontWeight = FontWeight.Bold, fontSize = 17.sp); Text("${items.size} tracks", color = palette.muted, fontSize = 13.sp) }
                TextButton(onClick = { onPlay(items) }) { Text("▶", color = palette.ink) }
            }
        } }
    }
}

@Composable
private fun ColumnScope.PlaylistPage(playlists: List<Playlist>, songs: List<Song>, expanded: String, palette: Palette, onCreate: () -> Unit, onPlay: (Playlist) -> Unit, onAdd: (Playlist) -> Unit, onRemove: (Playlist, String) -> Unit, onExpand: (String) -> Unit) {
    Column(Modifier.weight(1f).fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Playlists", color = palette.ink, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Button(onClick = onCreate, colors = ButtonDefaults.buttonColors(containerColor = palette.accent)) { Text("Create", color = palette.ink, fontWeight = FontWeight.Bold) }
        }
        LazyColumn { items(playlists, key = { it.name }) { list ->
            Column {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${if (expanded == list.name) "⌄" else "›"}  ${list.name}", Modifier.weight(1f).clickable { onExpand(list.name) }, color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("${list.uris.size}", color = palette.muted)
                    TextButton(onClick = { onAdd(list) }) { Text("+", color = palette.ink) }
                    TextButton(onClick = { onPlay(list) }) { Text("▶", color = palette.ink) }
                }
                if (expanded == list.name) list.uris.forEach { uri ->
                    val song = songs.find { it.uri.toString() == uri }
                    if (song != null) Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(song.title, Modifier.weight(1f), color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { onRemove(list, uri) }) { Text("×", color = palette.ink) }
                    }
                }
            }
        } }
    }
}

@Composable
private fun ColumnScope.DownloadPage(
    link: String, onLink: (String) -> Unit, downloading: Boolean, progress: Int, status: String,
    folderName: String, downloads: List<Song>, onChooseFolder: () -> Unit, onDownload: () -> Unit, onPlay: (Song) -> Unit, palette: Palette
) {
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
        Text("YOUR MUSIC", color = palette.muted, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.8.sp)
        Text("Download", color = palette.ink, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(17.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            listOf("▶  YouTube", "☁  SoundCloud").forEach { source ->
                Surface(shape = RoundedCornerShape(14.dp), color = palette.accent.copy(alpha = if (source.startsWith("▶")) .30f else .14f)) {
                    Text(source, Modifier.padding(horizontal = 13.dp, vertical = 9.dp), color = palette.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("Music link", color = palette.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = link, onValueChange = onLink, modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
            placeholder = { Text("Paste a YouTube or SoundCloud link", color = palette.muted) },
            singleLine = true, shape = RoundedCornerShape(15.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = palette.accent, unfocusedBorderColor = palette.ink.copy(alpha = .22f),
                focusedTextColor = palette.ink, unfocusedTextColor = palette.ink,
                focusedContainerColor = Color.Black.copy(alpha = .12f), unfocusedContainerColor = Color.Black.copy(alpha = .12f)
            )
        )
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(11.dp), color = palette.accent.copy(alpha = .28f)) {
                Text("♫  MP3", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = palette.ink, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
            }
            Text("  ·  Cover art embedded", color = palette.muted, fontSize = 12.sp)
        }
        Button(
            onClick = onDownload, enabled = !downloading,
            modifier = Modifier.fillMaxWidth().height(51.dp), shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.ink, disabledContainerColor = palette.accent.copy(alpha = .45f))
        ) { Text(if (downloading) "Downloading $progress%" else "↓   Download track", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp) }
        Row(Modifier.fillMaxWidth().clickable(onClick = onChooseFolder).padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("▱", color = palette.accent, fontSize = 19.sp)
            Text("  Save to  ", color = palette.muted, fontSize = 12.sp)
            Text(folderName, modifier = Modifier.weight(1f), color = palette.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("›", color = palette.muted, fontSize = 21.sp)
        }
        if (status.isNotBlank()) {
            Text(status, color = if (status.contains("failed", true) || status.contains("need", true) || status.contains("paste", true)) Color(0xFFFFB8C4) else palette.muted, fontSize = 12.sp)
            if (downloading) Slider(progress / 100f, {}, modifier = Modifier.fillMaxWidth().height(24.dp), colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = palette.accent, activeTrackColor = palette.accent, inactiveTrackColor = palette.ink.copy(alpha = .18f)))
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Recent downloads", color = palette.ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            Text("MP3", color = palette.muted, fontSize = 11.sp)
        }
        if (downloads.isEmpty()) Text("Your finished downloads will appear here and in Library.", Modifier.padding(top = 7.dp), color = palette.muted, fontSize = 12.sp)
        else downloads.take(6).forEach { SongRow(it, palette.ink, palette.muted) { onPlay(it) } }
    }
}

@Composable
private fun SongRow(song: Song, ink: Color, muted: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(9.dp)).background(ink.copy(alpha = .13f)), contentAlignment = Alignment.Center) {
            if (song.cover != null) Image(song.cover.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) else Text("♫", color = ink)
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(song.title, color = ink, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist.ifBlank { song.album }, color = muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("▶", color = ink, modifier = Modifier.padding(horizontal = 10.dp))
    }
}

@Composable
private fun IconText(text: String, color: Color, action: () -> Unit) {
    Text(text, Modifier.clip(CircleShape).clickable(onClick = action).padding(12.dp), color = color, fontSize = 21.sp, fontWeight = FontWeight.Bold)
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

