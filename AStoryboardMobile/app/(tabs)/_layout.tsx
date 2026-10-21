import { Tabs } from 'expo-router';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Ionicons } from '@expo/vector-icons';
import { tokens } from '../../src/tokens';

/**
 * 手机端只有三个 Tab：智能体对话 / 项目库 / 我的。
 *
 * 分镜编辑器（/editor）和素材（/materials）是二级页，从「我的」进入，
 * 不再占用 Tab；新增 Tab 之前先确认这三个是不是仍然只够用。
 */
export default function TabsLayout() {
  const insets = useSafeAreaInsets();
  return (
    <Tabs
      screenOptions={{
        headerShown: false,
        tabBarStyle: {
          backgroundColor: tokens.colors.canvas,
          borderTopColor: tokens.colors.hairline,
          borderTopWidth: 1,
          height: 56 + insets.bottom,
          paddingBottom: insets.bottom,
          paddingTop: 4,
        },
        tabBarActiveTintColor: tokens.colors.primary,
        tabBarInactiveTintColor: tokens.colors.textCaption,
        tabBarLabelStyle: {
          fontSize: 11,
          fontWeight: tokens.fontWeight.medium,
        },
      }}
    >
      <Tabs.Screen
        name="chat"
        options={{
          title: '智能体对话',
          tabBarIcon: ({ color }) => (
            <Ionicons name="chatbubble-ellipses-outline" size={22} color={color as string} />
          ),
        }}
      />
      <Tabs.Screen
        name="assets"
        options={{
          title: '项目库',
          tabBarIcon: ({ color }) => (
            <Ionicons name="folder-open-outline" size={22} color={color as string} />
          ),
        }}
      />
      <Tabs.Screen
        name="mine"
        options={{
          title: '我的',
          tabBarIcon: ({ color }) => (
            <Ionicons name="person-outline" size={22} color={color as string} />
          ),
        }}
      />
    </Tabs>
  );
}
