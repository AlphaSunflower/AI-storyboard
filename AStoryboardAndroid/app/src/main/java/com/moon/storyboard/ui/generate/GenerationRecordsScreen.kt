package com.moon.storyboard.ui.generate

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.moon.storyboard.data.GenerationRecord
import com.moon.storyboard.ui.MainUiState
import com.moon.storyboard.ui.components.MoonEmptyState
import com.moon.storyboard.ui.components.MoonPill
import com.moon.storyboard.ui.components.MoonTopBar
import com.moon.storyboard.ui.components.RemoteThumb
import com.moon.storyboard.ui.components.formatShortTime
import com.moon.storyboard.ui.theme.MoonPalette

@Composable
fun GenerationRecordsScreen(
    state: MainUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    var filter by remember { mutableStateOf("all") }
    var selected by remember { mutableStateOf<GenerationRecord?>(null) }

    val filtered = state.records.filter {
        filter == "all" || it.type == filter
    }

    Column(Modifier.fillMaxSize().background(MoonPalette.Canvas)) {
        MoonTopBar(
            title = "生成记录",
            subtitle = if (state.records.isEmpty()) "还没有生成记录" else "共 ${state.records.size} 条",
            onBack = onBack,
            actions = {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新", tint = MoonPalette.Body)
                }
            },
        )

        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val options = listOf("all" to "全部", "image" to "图片", "video" to "视频")
            items(options) { (value, label) ->
                MoonPill(label, filter == value, onClick = { filter = value })
            }
        }

        when {
            state.recordsLoading && state.records.isEmpty() -> {
                Column(
                    Modifier.fillMaxSize().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    repeat(2) {
                        Surface(
                            color = MoonPalette.Surface,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth().height(180.dp),
                        ) {}
                    }
                }
            }

            filtered.isEmpty() -> MoonEmptyState(
                icon = Icons.Outlined.History,
                title = "还没有生成记录",
                body = "去生成中心写一句描述，生成的图片和视频会按时间沉淀在这里。",
                modifier = Modifier.padding(top = 40.dp),
            )

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp, end = 20.dp, top = 14.dp, bottom = 24.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(filtered, key = { it.id }) { record ->
                    RecordCard(record) { selected = record }
                }
            }
        }
    }

    selected?.let { record ->
        RecordDialog(record) { selected = null }
    }
}

@Composable
private fun RecordCard(record: GenerationRecord, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MoonPalette.Surface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MoonPalette.Hairline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(6.dp)) {
            RemoteThumb(
                url = record.url,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                radius = 14.dp,
            )
            Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                Text(
                    record.prompt.ifBlank { "未填写提示词" },
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MoonPalette.Ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(record.status)
                    Text(
                        statusLabel(record.status),
                        fontSize = 10.sp,
                        color = statusColor(record.status),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        formatShortTime(record.createdAt),
                        fontSize = 10.sp,
                        color = MoonPalette.Muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordDialog(record: GenerationRecord, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = MoonPalette.Surface,
            shape = RoundedCornerShape(22.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                RemoteThumb(
                    url = record.url,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    radius = 16.dp,
                )
                Column(Modifier.padding(horizontal = 6.dp, vertical = 10.dp)) {
                    Text(
                        record.prompt.ifBlank { "未填写提示词" },
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MoonPalette.Ink,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        listOfNotNull(
                            record.model.takeIf { it.isNotBlank() },
                            record.genType.takeIf { it.isNotBlank() },
                            formatShortTime(record.createdAt).takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                        fontSize = 11.sp,
                        color = MoonPalette.Muted,
                    )
                    if (record.error.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(record.error, fontSize = 11.sp, color = MoonPalette.Danger)
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("关闭", color = MoonPalette.Primary)
                }
            }
        }
    }
}

@Composable
private fun StatusDot(status: String) {
    Surface(
        color = statusColor(status),
        shape = CircleShape,
        modifier = Modifier.size(6.dp),
    ) {}
}

private fun statusLabel(status: String): String = when (status) {
    "completed" -> "已完成"
    "failed" -> "失败"
    "generating" -> "生成中"
    "queued" -> "排队中"
    "deleted" -> "已删除"
    else -> status.ifBlank { "未知" }
}

private fun statusColor(status: String): Color = when (status) {
    "completed" -> MoonPalette.Success
    "failed" -> MoonPalette.Danger
    "deleted" -> MoonPalette.Muted
    else -> MoonPalette.Primary
}
