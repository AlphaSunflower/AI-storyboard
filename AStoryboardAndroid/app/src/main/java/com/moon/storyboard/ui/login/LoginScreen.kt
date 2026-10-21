package com.moon.storyboard.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moon.storyboard.data.ApiClient
import com.moon.storyboard.data.ApiException
import com.moon.storyboard.data.LoginData
import com.moon.storyboard.data.LoginRequest
import com.moon.storyboard.data.SessionStore
import com.moon.storyboard.ui.components.MoonMark
import com.moon.storyboard.ui.theme.MoonPalette
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

@Composable
fun LoginScreen(api: ApiClient, session: SessionStore) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (loading) return
        if (email.isBlank() || password.isBlank()) return
        loading = true
        error = null
        scope.launch {
            try {
                val data = api.post<LoginData>(
                    "/auth/login",
                    api.json.encodeToString(LoginRequest(email.trim(), password)),
                )
                session.save(data)
            } catch (e: Exception) {
                error = (e as? ApiException)?.message ?: "登录失败，请检查网络"
            } finally {
                loading = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MoonPalette.Canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
    ) {
        Spacer(Modifier.height(56.dp))
        MoonMark(size = 52.dp)
        Spacer(Modifier.height(22.dp))
        Text(
            "Moon",
            fontSize = 34.sp,
            fontWeight = FontWeight.SemiBold,
            color = MoonPalette.Ink,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "把想法说给智能体，剩下的交给它。",
            fontSize = 14.sp,
            color = MoonPalette.Muted,
            lineHeight = 22.sp,
        )

        Spacer(Modifier.height(44.dp))
        FieldLabel("邮箱")
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("you@example.com", color = MoonPalette.Muted) },
            shape = RoundedCornerShape(14.dp),
            colors = fieldColors(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
        )

        Spacer(Modifier.height(16.dp))
        FieldLabel("密码")
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("输入密码", color = MoonPalette.Muted) },
            visualTransformation = PasswordVisualTransformation(),
            shape = RoundedCornerShape(14.dp),
            colors = fieldColors(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
        )

        if (error != null) {
            Spacer(Modifier.height(14.dp))
            Text(error!!, fontSize = 12.sp, color = MoonPalette.Danger)
        }

        Spacer(Modifier.height(28.dp))
        Button(
            onClick = { submit() },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MoonPalette.Primary,
                contentColor = androidx.compose.ui.graphics.Color.White,
            ),
            enabled = !loading && email.isNotBlank() && password.isNotBlank(),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp).padding(end = 10.dp),
                    strokeWidth = 2.dp,
                    color = androidx.compose.ui.graphics.Color.White,
                )
                Text("正在登录", fontSize = 15.sp)
            } else {
                Text("进入 Moon", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }

        Spacer(Modifier.height(18.dp))
        TextButton(
            onClick = { /* 注册走网页端，先保持单一入口 */ },
            modifier = Modifier.align(Alignment.CenterHorizontally),
            enabled = false,
        ) {
            Text("账号由管理员开通", fontSize = 12.sp, color = MoonPalette.Muted)
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = MoonPalette.Body,
        modifier = Modifier.padding(start = 2.dp, bottom = 8.dp),
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MoonPalette.Surface,
    unfocusedContainerColor = MoonPalette.Surface,
    focusedBorderColor = MoonPalette.Primary,
    unfocusedBorderColor = MoonPalette.Hairline,
    focusedTextColor = MoonPalette.Ink,
    unfocusedTextColor = MoonPalette.Ink,
    cursorColor = MoonPalette.Primary,
)
