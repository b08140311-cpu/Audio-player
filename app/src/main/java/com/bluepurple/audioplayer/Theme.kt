package com.bluepurple.audioplayer

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.Color

internal data class Song(
    val uri: Uri, val title: String, val artist: String, val album: String,
    val folder: String, val cover: Bitmap?
)

internal data class Playlist(val name: String, val uris: List<String>)

internal data class Palette(
    val name: String,
    val bg: Color,
    val ink: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val s1: Color,
    val s2: Color,
    val tint: Color,
    val track: Color,
    val border: Color,
    val dark: Boolean
)

private fun c(hex: Long) = Color(0xFF000000 or hex)

internal val palettes = listOf(
    Palette("Blue Purple", c(0x14111F), c(0xF4F0FF), c(0xA79FC4), c(0xB9A1FF), c(0x1A1030), c(0x1E1A2E), c(0x241E38), c(0x332A52), c(0x2E2845), c(0x3A3254), true),
    Palette("Aurora", c(0x0A1517), c(0xE8FFF7), c(0x9FC7BF), c(0x53E2B5), c(0x062A20), c(0x11262A), c(0x163239), c(0x1B4A4A), c(0x1F3A3D), c(0x2B4A4E), true),
    Palette("Violet Dusk", c(0x100F19), c(0xF7EFFF), c(0xC4B3E0), c(0xC994FF), c(0x2A0F45), c(0x1B1829), c(0x21182F), c(0x3A2A5C), c(0x2D2442), c(0x3E3158), true),
    Palette("Ice Blue", c(0xEAF4F8), c(0x20364B), c(0x4C647B), c(0x3A6F95), c(0xFFFFFF), c(0xDCEBF2), c(0xCFE2EC), c(0xBCD6E6), c(0xBFD3E0), c(0xAFC6D6), false),
    Palette("Sunset", c(0xFFF1D6), c(0x542D22), c(0x805044), c(0xBD4D38), c(0xFFFFFF), c(0xFFE4B8), c(0xFFD9A6), c(0xFFC896), c(0xF0CFA0), c(0xE9BE8A), false),
    Palette("Rose", c(0xFFE7EF), c(0x4D2740), c(0x77445F), c(0xB94878), c(0xFFFFFF), c(0xFFD6E5), c(0xFBC8DB), c(0xF5B5D0), c(0xEBC0D2), c(0xE4A9C2), false),
    Palette("Ocean", c(0xDDF4F1), c(0x18364E), c(0x365E77), c(0x247D9B), c(0xFFFFFF), c(0xC9EBE6), c(0xB9E2EE), c(0xA9D6E4), c(0xB4DAD6), c(0x9CCBD0), false)
)
