package com.moon.storyboard.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moon.storyboard.data.LibraryAsset
import com.moon.storyboard.ui.MainUiState
import com.moon.storyboard.ui.components.MoonEmptyState
import com.moon.storyboard.ui.components.MoonPill
import com.moon.storyboard.ui.components.MoonTopBar
import com.moon.storyboard.ui.theme.MoonPalette

@Composable
fun LibraryScreen(state: MainUiState, onBack: () -> Unit, onRefresh: () -> Unit) {
    var filter by remember { mutableStateOf("all") }
    val filtered = state.library.filter { filter == "all" || it.type == filter }

    Column(Modifier.fillMaxSize().background(MoonPalette.Canvas)) {
        MoonTopBar(
            title = "项目库",
            subtitle = state.project?.name ?: "当前项目的资产",
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
            val options = listOf(
                "all" to "全部",
                "character" to "人物",
                "prop" to "道具",
                "scene" to "场景",
            )
            items(options) { (value, label) ->
                MoonPill(text = label, selected = filter == value, onClick = { filter = value })
            }
        }

        when {
            state.libraryLoading && state.library.isEmpty() -> {
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(3) {
                        Surface(
                            color = MoonPalette.Surface,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth().height(92.dp),
                        ) {}
                    }
                }
            }

            filtered.isEmpty() -> MoonEmptyState(
                icon = Icons.Outlined.FolderOpen,
                title = if (filter == "all") "项目库还是空的" else "这个分类还没有资产",
                body = "在对话里让 Moon 创建人物、道具或场景，它们会沉淀到这里。",
                modifier = Modifier.padding(top = 40.dp),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp, end = 20.dp, top = 14.dp, bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(filtered, key = { it.id }) { asset ->
                    AssetRow(asset)
                }
            }
        }
    }
}

@Composable
private fun AssetRow(asset: LibraryAsset) {
    val (icon, label) = when (asset.type) {
        "character" -> Icons.Outlined.PersonOutline to "人物"
        "prop" -> Icons.Outlined.Style to "道具"
        "scene" -> Icons.Outlined.Place to "场景"
        else -> Icons.Outlined.FolderOpen to "资产"
    }

    Surface(
        color = MoonPalette.Surface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MoonPalette.Hairline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(MoonPalette.SurfaceWarm, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MoonPalette.Primary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    asset.name.ifBlank { "未命名" },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MoonPalette.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (asset.description.isNotBlank()) {
                    Text(
                        asset.description,
                        fontSize = 12.sp,
                        color = MoonPalette.Muted,
                        lineHeight = 18.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(label, fontSize = 11.sp, color = MoonPalette.Primary)
        }
    }
}
