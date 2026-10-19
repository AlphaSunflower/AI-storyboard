import { View, Text, TextInput, TouchableOpacity, StyleSheet, KeyboardAvoidingView, Platform, Image } from 'react-native';
import { Link, router } from 'expo-router';
import { useState } from 'react';
import { SafeAreaView } from 'react-native-safe-area-context';
import { tokens } from '../../src/tokens';
import { useAuthStore } from '../../src/stores/authStore';

export default function LoginScreen() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const { login, isLoading, error } = useAuthStore();

  const handleLogin = async () => {
    await login({ email, password });
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
        <Image source={require('../../assets/moon-mark.png')} style={s.logo} resizeMode="contain" />
        <Text style={s.title}>AI Storyboard</Text>
        <Text style={s.subtitle}>登录你的账户</Text>

        <View style={s.form}>
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
            onPress={handleLogin}
            disabled={isLoading}
          >
            <Text style={s.buttonText}>{isLoading ? '登录中...' : '登录'}</Text>
          </TouchableOpacity>

          <Link href="/register" asChild>
            <TouchableOpacity style={s.linkButton}>
              <Text style={s.linkText}>没有账户？注册</Text>
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
  logo: {
    width: 64,
    height: 64,
    alignSelf: 'center',
    marginBottom: 10,
  },
  title: {
    fontSize: tokens.fontSize.hero,
    fontWeight: tokens.fontWeight.bold,
    color: tokens.colors.ink,
    textAlign: 'center',
    marginBottom: 8,
  },
  subtitle: {
    fontSize: tokens.fontSize.base,
    color: tokens.colors.textSecondary,
    textAlign: 'center',
    marginBottom: 40,
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
  error: {
    fontSize: tokens.fontSize.sm,
    color: tokens.colors.error,
    textAlign: 'center',
  },
  button: {
    height: 48,
    backgroundColor: tokens.colors.primary,
    borderRadius: tokens.radius.sm,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: 8,
  },
  buttonDisabled: { opacity: 0.6 },
  buttonText: {
    fontSize: tokens.fontSize.base,
    fontWeight: tokens.fontWeight.semibold,
    color: tokens.colors.white,
  },
  linkButton: { alignItems: 'center', marginTop: 8 },
  linkText: {
    fontSize: tokens.fontSize.sm,
    color: tokens.colors.primary,
  },
});
