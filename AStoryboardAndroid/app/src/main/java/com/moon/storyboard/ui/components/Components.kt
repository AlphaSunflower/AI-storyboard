package com.moon.storyboard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.moon.storyboard.ui.theme.MoonPalette
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@Composable
fun MoonTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MoonPalette.Canvas)
            .padding(start = if (onBack != null) 4.dp else 18.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "返回", tint = MoonPalette.Ink)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MoonPalette.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = MoonPalette.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        actions()
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MoonPalette.Muted,
        letterSpacing = 0.8.sp,
    )
}

@Composable
fun MoonPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) MoonPalette.Primary else MoonPalette.Surface)
            .border(1.dp, if (selected) MoonPalette.Primary else MoonPalette.Hairline, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) Color.White else MoonPalette.Body,
        )
    }
}

@Composable
fun MoonEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MoonPalette.SurfaceWarm),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MoonPalette.Primary, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MoonPalette.Ink)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            fontSize = 13.sp,
            color = MoonPalette.Muted,
            lineHeight = 20.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
fun MoonMark(size: Dp = 34.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MoonPalette.PrimarySoft)
            .border(1.dp, MoonPalette.Hairline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("☽", fontSize = (size.value * 0.5f).sp, color = MoonPalette.Primary)
    }
}

@Composable
fun RemoteThumb(url: String, modifier: Modifier = Modifier, radius: Dp = 14.dp) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(radius))
            .background(MoonPalette.SurfaceWarm),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isBlank()) {
            Icon(Icons.Outlined.Image, contentDescription = null, tint = MoonPalette.Muted)
            return@Box
        }
        AsyncImage(
            model = mediaUrl(url),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

fun formatShortTime(iso: String): String {
    if (iso.isBlank()) return ""
    return try {
        val parsed = OffsetDateTime.parse(iso)
            .atZoneSameInstant(java.time.ZoneId.systemDefault())
        parsed.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    } catch (_: Exception) {
        iso.take(16).replace('T', ' ')
    }
}

/** 后端返回的是相对 `/api/...` 地址；Coil 需要绝对 URL。 */
fun mediaUrl(path: String?): String {
    if (path.isNullOrBlank()) return ""
    if (path.startsWith("http")) return path
    return com.moon.storyboard.BuildConfig.API_BASE_URL.trimEnd('/') + path
}

@Composable
fun KeyValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 12.sp, color = MoonPalette.Muted, modifier = Modifier.width(72.dp))
        Text(value, fontSize = 13.sp, color = MoonPalette.Body, modifier = Modifier.weight(1f))
    }
}
