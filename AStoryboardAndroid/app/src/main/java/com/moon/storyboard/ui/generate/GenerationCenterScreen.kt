package com.moon.storyboard.ui.generate

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moon.storyboard.ui.MainUiState
import com.moon.storyboard.ui.components.MoonPill
import com.moon.storyboard.ui.components.MoonTopBar
import com.moon.storyboard.ui.components.RemoteThumb
import com.moon.storyboard.ui.components.SectionLabel
import com.moon.storyboard.ui.theme.MoonPalette

@Composable
fun GenerationCenterScreen(
    state: MainUiState,
    onBack: () -> Unit,
    onOpenRecords: () -> Unit,
    onAddReference: (Uri) -> Unit,
    onRemoveReference: (String) -> Unit,
    onGenerate: (prompt: String, video: Boolean, ratio: String, count: Int) -> Unit,
) {
    var prompt by rememberSaveable { mutableStateOf("") }
    var video by rememberSaveable { mutableStateOf(false) }
    var ratio by rememberSaveable { mutableStateOf("16:9") }
    var count by rememberSaveable { mutableIntStateOf(1) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onAddReference(uri)
    }

    Column(Modifier.fillMaxSize().background(MoonPalette.Canvas)) {
        MoonTopBar(
            title = "生成中心",
            subtitle = "一句话，交给 Moon",
            onBack = onBack,
            actions = {
                IconButton(onClick = onOpenRecords) {
                    Icon(Icons.Outlined.History, contentDescription = "生成记录", tint = MoonPalette.Body)
                }
            },
        )

        Box(Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                Text(
                    "把一句想法变成图",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MoonPalette.Ink,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    "写清楚画面与风格，其余交给 Moon。",
                    fontSize = 13.sp,
                    color = MoonPalette.Muted,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )

                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth().height(88.dp),
                    placeholder = {
                        Text(
                            "例：雨夜的霓虹街道，一位穿风衣的侦探回头，电影感，冷色调",
                            fontSize = 13.sp,
                            color = MoonPalette.Muted,
                            lineHeight = 20.sp,
                        )
                    },
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MoonPalette.Surface,
                        unfocusedContainerColor = MoonPalette.Surface,
                        focusedBorderColor = MoonPalette.Primary,
                        unfocusedBorderColor = MoonPalette.Hairline,
                        focusedTextColor = MoonPalette.Ink,
                        unfocusedTextColor = MoonPalette.Ink,
                        cursorColor = MoonPalette.Primary,
                    ),
                )

                SectionLabel("参考素材")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.referenceUrls, key = { it }) { url ->
                        Box(Modifier.size(64.dp)) {
                            RemoteThumb(url = url, modifier = Modifier.fillMaxSize(), radius = 14.dp)
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(3.dp)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(MoonPalette.Canvas)
                                    .clickable { onRemoveReference(url) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = "移除参考图",
                                    tint = MoonPalette.Body,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                        }
                    }
                    if (state.referenceUrls.size < 5) {
                        item("add-reference") {
                            Surface(
                                onClick = {
                                    picker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                    )
                                },
                                color = MoonPalette.Surface,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, MoonPalette.Hairline),
                                modifier = Modifier.size(64.dp),
                                enabled = !state.uploadingReference,
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    if (state.uploadingReference) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 1.8.dp,
                                            color = MoonPalette.Primary,
                                        )
                                    } else {
                                        Icon(
                                            Icons.Outlined.AddPhotoAlternate,
                                            contentDescription = "添加参考图",
                                            tint = MoonPalette.Primary,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Text(
                                            "参考图",
                                            fontSize = 10.sp,
                                            color = MoonPalette.Muted,
                                            modifier = Modifier.padding(top = 3.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (state.referenceUrls.isNotEmpty()) {
                    Text(
                        "会作为本轮生成的参考素材，最多 5 张。",
                        fontSize = 11.sp,
                        color = MoonPalette.Muted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                SectionLabel("类型")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MoonPill("图片", !video, onClick = { video = false })
                    MoonPill("视频", video, onClick = { video = true })
                }

                SectionLabel("画幅")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("16:9", "1:1", "9:16", "4:3").forEach { option ->
                        MoonPill(option, ratio == option, onClick = { ratio = option })
                    }
                }

                if (!video) {
                    SectionLabel("数量")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StepButton(Icons.Outlined.Remove) { count = (count - 1).coerceAtLeast(1) }
                        Text(
                            "$count",
                            modifier = Modifier.width(44.dp),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = MoonPalette.Ink,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        StepButton(Icons.Outlined.Add) { count = (count + 1).coerceAtMost(4) }
                        Spacer(Modifier.width(10.dp))
                        Text("最多 4 张", fontSize = 12.sp, color = MoonPalette.Muted)
                    }
                }

                if (state.records.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "最近生成",
                            modifier = Modifier.weight(1f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MoonPalette.Ink,
                        )
                        Text("全部记录", fontSize = 12.sp, color = MoonPalette.Primary)
                    }
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
                    ) {
                        items(state.records.take(8), key = { it.id }) { record ->
                            RemoteThumb(url = record.url, modifier = Modifier.size(100.dp))
                        }
                    }
                }

                Spacer(Modifier.height(112.dp))
            }

            Surface(
                color = MoonPalette.Canvas,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            ) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 8.dp)) {
                    HorizontalDivider(color = MoonPalette.Hairline)
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = { onGenerate(prompt, video, ratio, count) },
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(16.dp),
                        enabled = prompt.isNotBlank() && !state.creatingConversation,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MoonPalette.Primary,
                            contentColor = Color.White,
                            disabledContainerColor = MoonPalette.Hairline,
                            disabledContentColor = MoonPalette.Muted,
                        ),
                    ) {
                        if (state.creatingConversation) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.White,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("正在准备", fontSize = 15.sp)
                        } else {
                            Text(
                                if (video) "生成视频" else "开始生成",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepButton(icon: ImageVector, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MoonPalette.Surface,
        border = BorderStroke(1.dp, MoonPalette.Hairline),
        modifier = Modifier.size(38.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MoonPalette.Body, modifier = Modifier.size(17.dp))
        }
    }
}
