import { Tabs } from 'expo-router';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Ionicons } from '@expo/vector-icons';
import { tokens } from '../../src/tokens';

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
          title: '对话',
          tabBarIcon: ({ color }) => <Ionicons name="chatbubble-outline" size={22} color={color as string} />,
        }}
      />
      <Tabs.Screen
        name="editor"
        options={{
          title: '编辑器',
          tabBarIcon: ({ color }) => <Ionicons name="film-outline" size={22} color={color as string} />,
        }}
      />
      <Tabs.Screen
        name="materials"
        options={{
          title: '素材',
          tabBarIcon: ({ color }) => <Ionicons name="images-outline" size={22} color={color as string} />,
        }}
      />
      <Tabs.Screen
        name="assets"
        options={{
          title: '资产库',
          tabBarIcon: ({ color }) => <Ionicons name="cube-outline" size={22} color={color as string} />,
        }}
      />
    </Tabs>
  );
}
