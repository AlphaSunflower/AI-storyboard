package com.moon.storyboard.ui.chat

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moon.storyboard.data.ChatMessage
import com.moon.storyboard.ui.MainUiState
import com.moon.storyboard.ui.components.MoonMark
import com.moon.storyboard.ui.theme.MoonPalette

@Composable
fun ChatScreen(
    state: MainUiState,
    onSend: (String) -> Unit,
    onNewConversation: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onReload: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenGenerate: () -> Unit,
    onOpenRecords: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val active = state.conversations.firstOrNull { it.id == state.activeConversationId }
    val conversationTitle = when {
        active != null && active.title.isNotBlank() && active.title != "新对话" -> active.title
        state.messages.firstOrNull { it.role == "user" } != null ->
            state.messages.first { it.role == "user" }.content.take(16)
        else -> "新对话"
    }

    LaunchedEffect(state.messages.size, state.streamingText.length) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MoonPalette.Canvas)
            .imePadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f).clickable { pickerOpen = true },
            ) {
                Text(
                    state.project?.name ?: "未选择项目",
                    fontSize = 10.sp,
                    color = MoonPalette.Muted,
                    letterSpacing = 0.6.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        conversationTitle,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MoonPalette.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        Icons.Outlined.ArrowDropDown,
                        contentDescription = "切换对话",
                        tint = MoonPalette.Muted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            IconButton(onClick = onNewConversation, enabled = state.project != null && !state.sending) {
                Icon(Icons.Outlined.Add, contentDescription = "新建对话", tint = MoonPalette.Ink)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "更多", tint = MoonPalette.Ink)
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    modifier = Modifier.background(MoonPalette.Surface),
                ) {
                    MenuItem("项目库", Icons.Outlined.FolderOpen) {
                        menuOpen = false
                        onOpenLibrary()
                    }
                    MenuItem("生成中心", Icons.Outlined.AutoAwesome) {
                        menuOpen = false
                        onOpenGenerate()
                    }
                    MenuItem("生成记录", Icons.Outlined.History) {
                        menuOpen = false
                        onOpenRecords()
                    }
                    HorizontalDivider(color = MoonPalette.Hairline)
                    MenuItem("设置", Icons.Outlined.Settings) {
                        menuOpen = false
                        onOpenSettings()
                    }
                }
            }
        }

        Box(Modifier.weight(1f)) {
            when {
                state.project == null && !state.loading -> NoProject(onReload)
                state.loading -> LoadingChat()
                state.messages.isEmpty() && !state.sending && state.streamingText.isBlank() ->
                    EmptyChat(onSuggestion = onSend)
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, top = 10.dp, bottom = 20.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        MessageRow(message)
                    }
                    if (state.sending) {
                        item("streaming") { StreamingRow(state.streamingText) }
                    }
                }
            }
        }

        Composer(
            value = input,
            enabled = !state.sending && state.project != null,
            onValueChange = { input = it },
            onSend = {
                onSend(input)
                input = ""
            },
        )
    }

    if (pickerOpen) {
        AlertDialog(
            onDismissRequest = { pickerOpen = false },
            containerColor = MoonPalette.Surface,
            title = { Text("切换对话", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                pickerOpen = false
                                onNewConversation()
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, tint = MoonPalette.Primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("新建对话", fontSize = 14.sp, color = MoonPalette.Primary)
                    }
                    HorizontalDivider(color = MoonPalette.Hairline)
                    state.conversations.take(12).forEach { conversation ->
                        val selected = conversation.id == state.activeConversationId
                        Text(
                            conversation.title.ifBlank { "新对话" },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    pickerOpen = false
                                    onSelectConversation(conversation.id)
                                }
                                .padding(vertical = 12.dp),
                            fontSize = 14.sp,
                            color = if (selected) MoonPalette.Primary else MoonPalette.Ink,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickerOpen = false }) {
                    Text("关闭", color = MoonPalette.Primary)
                }
            },
        )
    }
}

@Composable
private fun MenuItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, fontSize = 14.sp, color = MoonPalette.Ink) },
        onClick = onClick,
        leadingIcon = { Icon(icon, contentDescription = null, tint = MoonPalette.Muted, modifier = Modifier.size(18.dp)) },
    )
}

@Composable
private fun LoadingChat() {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        repeat(3) { index ->
            Surface(
                color = MoonPalette.Surface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(if (index == 1) 0.72f else 0.9f).height(64.dp),
            ) {}
        }
    }
}

@Composable
private fun NoProject(onReload: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 36.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MoonMark(48.dp)
        Spacer(Modifier.height(16.dp))
        Text("还没有项目", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MoonPalette.Ink)
        Spacer(Modifier.height(6.dp))
        Text(
            "手机端不创建项目。请先在网页端新建项目，再回来重新检查。",
            fontSize = 13.sp,
            color = MoonPalette.Muted,
            lineHeight = 20.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        TextButton(onClick = onReload) {
            Text("重新检查", color = MoonPalette.Primary)
        }
    }
}

@Composable
private fun EmptyChat(onSuggestion: (String) -> Unit) {
    val suggestions = listOf("做一组科幻分镜", "生成一张概念海报", "整理本周的素材")
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MoonMark(52.dp)
        Spacer(Modifier.height(16.dp))
        Text("从一句话开始", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MoonPalette.Ink)
        Spacer(Modifier.height(6.dp))
        Text(
            "描述画面或分镜，Moon 会落成具体产物。",
            fontSize = 13.sp,
            color = MoonPalette.Muted,
            lineHeight = 20.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        suggestions.forEach { text ->
            Surface(
                onClick = { onSuggestion(text) },
                color = MoonPalette.Surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MoonPalette.Hairline),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Text(
                    text,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    fontSize = 13.sp,
                    color = MoonPalette.Body,
                )
            }
        }
    }
}

@Composable
private fun MessageRow(message: ChatMessage) {
    val isUser = message.role == "user"
    if (isUser) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MoonPalette.PrimarySoft,
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                Text(
                    text = message.content,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    color = MoonPalette.Ink,
                )
            }
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            MoonMark(26.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = message.content,
                modifier = Modifier.weight(1f).padding(top = 2.dp),
                fontSize = 14.sp,
                lineHeight = 23.sp,
                color = MoonPalette.Ink,
            )
        }
    }
}

@Composable
private fun StreamingRow(partial: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        MoonMark(26.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(top = 2.dp)) {
            if (partial.isNotBlank()) {
                Text(partial, fontSize = 14.sp, lineHeight = 23.sp, color = MoonPalette.Ink)
            }
            Row(
                modifier = Modifier.padding(top = if (partial.isBlank()) 4.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = MoonPalette.Primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (partial.isBlank()) "正在思考" else "正在生成",
                    fontSize = 12.sp,
                    color = MoonPalette.Muted,
                )
            }
        }
    }
}

@Composable
private fun Composer(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Surface(
        color = MoonPalette.Surface,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MoonPalette.Hairline),
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 15.dp, end = 5.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).heightIn(min = 22.dp, max = 110.dp),
                textStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = MoonPalette.Ink),
                cursorBrush = SolidColor(MoonPalette.Primary),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text("描述你的想法…", fontSize = 14.sp, color = MoonPalette.Muted)
                        }
                        inner()
                    }
                },
            )
            Spacer(Modifier.width(6.dp))
            FilledIconButton(
                onClick = onSend,
                enabled = enabled && value.isNotBlank(),
                modifier = Modifier.size(36.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MoonPalette.Primary,
                    contentColor = androidx.compose.ui.graphics.Color.White,
                    disabledContainerColor = MoonPalette.Hairline,
                    disabledContentColor = MoonPalette.Muted,
                ),
            ) {
                Icon(Icons.Outlined.Send, contentDescription = "发送", modifier = Modifier.size(19.dp))
            }
        }
    }
}
