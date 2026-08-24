import { View, Text, TextInput, TouchableOpacity, StyleSheet, KeyboardAvoidingView, Platform } from 'react-native';
import { Link, router } from 'expo-router';
import { useState } from 'react';
import { SafeAreaView } from 'react-native-safe-area-context';
import { tokens } from '../../src/tokens';
import { useAuthStore } from '../../src/stores/authStore';

export default function RegisterScreen() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const { register, isLoading, error } = useAuthStore();

  const handleRegister = async () => {
    await register({ email, password, displayName });
    if (!useAuthStore.getState().error && useAuthStore.getState().isAuthenticated) {
      router.replace('/(tabs)/chat');
    }
  };

  return (
    <SafeAreaView style={s.container} edges={['top', 'bottom']}>
    <KeyboardAvoidingView
      style={s.inner}
      behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
    >
      <View style={s.innerContent}>
        <Text style={s.logo}>☾</Text>
        <Text style={s.title}>创建账户</Text>

        <View style={s.form}>
          <Text style={s.label}>昵称</Text>
          <TextInput
            style={s.input}
            value={displayName}
            onChangeText={setDisplayName}
            placeholder="输入昵称"
            placeholderTextColor={tokens.colors.textCaption}
          />

          <Text style={s.label}>邮箱</Text>
          <TextInput
            style={s.input}
            value={email}
            onChangeText={setEmail}
            placeholder="输入邮箱"
            placeholderTextColor={tokens.colors.textCaption}
            autoCapitalize="none"
            keyboardType="email-address"
          />

          <Text style={s.label}>密码</Text>
          <TextInput
            style={s.input}
            value={password}
            onChangeText={setPassword}
            placeholder="输入密码"
            placeholderTextColor={tokens.colors.textCaption}
            secureTextEntry
          />

          {error ? <Text style={s.error}>{error}</Text> : null}

          <TouchableOpacity
            style={[s.button, isLoading && s.buttonDisabled]}
            onPress={handleRegister}
            disabled={isLoading}
          >
            <Text style={s.buttonText}>{isLoading ? '注册中...' : '注册'}</Text>
          </TouchableOpacity>

          <Link href="/login" asChild>
            <TouchableOpacity style={s.linkButton}>
              <Text style={s.linkText}>已有账户？登录</Text>
            </TouchableOpacity>
          </Link>
        </View>
      </View>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

const s = StyleSheet.create({
  container: { flex: 1, backgroundColor: tokens.colors.canvas },
  inner: { flex: 1, justifyContent: 'center', paddingHorizontal: 32 },
  innerContent: { width: '100%' },
  logo: { fontSize: 48, color: tokens.colors.primary, textAlign: 'center', marginBottom: 8 },
  title: {
    fontSize: tokens.fontSize.xxl,
    fontWeight: tokens.fontWeight.bold,
    color: tokens.colors.ink,
    textAlign: 'center',
    marginBottom: 32,
  },
  form: { gap: 12 },
  label: {
    fontSize: tokens.fontSize.sm,
    fontWeight: tokens.fontWeight.medium,
    color: tokens.colors.ink,
    marginBottom: -4,
  },
  input: {
    height: 48,
    borderWidth: 1,
    borderColor: tokens.colors.hairline,
    borderRadius: tokens.radius.sm,
    paddingHorizontal: 14,
    fontSize: tokens.fontSize.base,
    color: tokens.colors.ink,
    backgroundColor: tokens.colors.white,
  },
  error: { fontSize: tokens.fontSize.sm, color: tokens.colors.error, textAlign: 'center' },
  button: {
    height: 48,
    backgroundColor: tokens.colors.primary,
    borderRadius: tokens.radius.sm,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: 8,
  },
  buttonDisabled: { opacity: 0.6 },
  buttonText: { fontSize: tokens.fontSize.base, fontWeight: tokens.fontWeight.semibold, color: tokens.colors.white },
  linkButton: { alignItems: 'center', marginTop: 8 },
  linkText: { fontSize: tokens.fontSize.sm, color: tokens.colors.primary },
});
