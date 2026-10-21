package com.moon.storyboard.ui.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moon.storyboard.data.Project
import com.moon.storyboard.ui.MainUiState
import com.moon.storyboard.ui.components.MoonMark
import com.moon.storyboard.ui.components.MoonTopBar
import com.moon.storyboard.ui.components.SectionLabel
import com.moon.storyboard.ui.theme.MoonPalette

@Composable
fun SettingsScreen(
    state: MainUiState,
    onBack: () -> Unit,
    onSelectProject: (Project) -> Unit,
    onLogout: () -> Unit,
) {
    var projectPicker by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MoonPalette.Canvas)) {
        MoonTopBar(title = "设置", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Surface(
                color = MoonPalette.SurfaceWarm,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MoonMark(44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.displayName.ifBlank { "Moon 用户" },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MoonPalette.Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text("Moon 智能体账号", fontSize = 11.sp, color = MoonPalette.Muted)
                    }
                }
            }

            SectionLabel("项目")
            Surface(
                color = MoonPalette.Surface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MoonPalette.Hairline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SettingsRow(
                    icon = Icons.Outlined.FolderOpen,
                    label = state.project?.name ?: "未选择项目",
                    hint = state.project?.status?.let { if (it == "active") "已保存" else "草稿" } ?: "",
                    onClick = { projectPicker = true },
                )
            }

            SectionLabel("账号")
            Surface(
                color = MoonPalette.Surface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MoonPalette.Hairline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SettingsRow(
                    icon = Icons.Outlined.Logout,
                    label = "退出登录",
                    hint = "",
                    danger = true,
                    onClick = onLogout,
                )
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Moon for Android · 0.1.0",
                modifier = Modifier.fillMaxWidth(),
                fontSize = 11.sp,
                color = MoonPalette.Muted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (projectPicker) {
        AlertDialog(
            onDismissRequest = { projectPicker = false },
            containerColor = MoonPalette.Surface,
            title = { Text("切换项目", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (state.projects.isEmpty()) {
                        Text("还没有项目，请先在网页端创建。", fontSize = 13.sp, color = MoonPalette.Muted)
                    }
                    state.projects.forEach { project ->
                        val active = project.id == state.project?.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectProject(project)
                                    projectPicker = false
                                }
                                .padding(horizontal = 2.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    project.name,
                                    fontSize = 14.sp,
                                    color = if (active) MoonPalette.Primary else MoonPalette.Ink,
                                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    if (project.status == "active") "已保存" else "草稿",
                                    fontSize = 11.sp,
                                    color = MoonPalette.Muted,
                                )
                            }
                            if (active) Text("当前", fontSize = 11.sp, color = MoonPalette.Primary)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { projectPicker = false }) {
                    Text("完成", color = MoonPalette.Primary)
                }
            },
        )
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    label: String,
    hint: String,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(
                    if (danger) Color(0xFFFBEAEA) else MoonPalette.SurfaceWarm,
                    RoundedCornerShape(10.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (danger) MoonPalette.Danger else MoonPalette.Primary,
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            modifier = Modifier.weight(1f),
            fontSize = 14.sp,
            color = if (danger) MoonPalette.Danger else MoonPalette.Ink,
            fontWeight = FontWeight.Medium,
        )
        if (hint.isNotBlank()) {
            Text(hint, fontSize = 11.sp, color = MoonPalette.Muted)
            Spacer(Modifier.width(4.dp))
        }
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MoonPalette.Muted,
            modifier = Modifier.size(16.dp),
        )
    }
}
