import {
  View,
  Text,
  ScrollView,
  TouchableOpacity,
  StyleSheet,
  Modal,
  Alert,
  FlatList,
} from 'react-native';
import { useState } from 'react';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import Constants from 'expo-constants';
import { tokens } from '../../src/tokens';
import { useAuthStore } from '../../src/stores/authStore';
import { useProjectStore } from '../../src/stores/projectStore';
import type { ProjectResponse } from '../../src/api/projects';

/** 「我的」：账号、当前项目、二级入口（素材 / 分镜脚本）与退出登录。 */
export default function MineTab() {
  const insets = useSafeAreaInsets();
  const router = useRouter();
  const { user, logout } = useAuthStore();
  const { projects, currentProject, loadProjects, loadProject } = useProjectStore();
  const [projectOpen, setProjectOpen] = useState(false);

  const openProjectPicker = async () => {
    setProjectOpen(true);
    try {
      await loadProjects();
    } catch {
      // 项目列表加载失败时保留弹窗，用户可以重试
    }
  };

  const selectProject = async (project: ProjectResponse) => {
    try {
      await loadProject(project.id);
      setProjectOpen(false);
    } catch {
      Alert.alert('切换失败', '暂时无法打开这个项目，请稍后重试。');
    }
  };

  const confirmLogout = () => {
    Alert.alert('退出登录', '确定要退出当前账号吗？', [
      { text: '取消', style: 'cancel' },
      {
        text: '退出',
        style: 'destructive',
        onPress: () => {
          void logout();
        },
      },
    ]);
  };

  const about = () => {
    Alert.alert(
      '关于 Moon',
      `AI Storyboard ${Constants.expoConfig?.version ?? '1.0.0'}\n智能体对话、项目库一站式创作。`,
      [{ text: '好的' }],
    );
  };

  return (
    <View style={styles.root}>
      <View style={[styles.header, { paddingTop: insets.top + 12 }]}>
        <Text style={styles.headerTitle}>我的</Text>
      </View>

      <ScrollView contentContainerStyle={styles.content} showsVerticalScrollIndicator={false}>
        <View style={styles.profileCard}>
          <View style={styles.avatar}>
            <Text style={styles.avatarText}>☽</Text>
          </View>
          <View style={styles.profileText}>
            <Text style={styles.name} numberOfLines={1}>{user?.displayName || '未登录'}</Text>
            <Text style={styles.subtitle}>Moon 智能体账号</Text>
          </View>
        </View>

        <Text style={styles.sectionLabel}>当前项目</Text>
        <TouchableOpacity style={styles.row} onPress={() => void openProjectPicker()} activeOpacity={0.7}>
          <Ionicons name="folder-open-outline" size={18} color={tokens.colors.primary} />
          <Text style={styles.rowText} numberOfLines={1}>{currentProject?.name ?? '选择项目'}</Text>
          <Text style={styles.rowHint}>
            {currentProject ? (currentProject.status === 'active' ? '已保存' : '草稿') : ''}
          </Text>
          <Ionicons name="chevron-forward" size={16} color={tokens.colors.textCaption} />
        </TouchableOpacity>

        <Text style={styles.sectionLabel}>更多</Text>
        <TouchableOpacity style={styles.row} onPress={() => router.push('/materials')} activeOpacity={0.7}>
          <Ionicons name="images-outline" size={18} color={tokens.colors.textSecondary} />
          <Text style={styles.rowText}>素材</Text>
          <Text style={styles.rowHint}>对话生成</Text>
          <Ionicons name="chevron-forward" size={16} color={tokens.colors.textCaption} />
        </TouchableOpacity>
        <TouchableOpacity style={styles.row} onPress={() => router.push('/editor')} activeOpacity={0.7}>
          <Ionicons name="film-outline" size={18} color={tokens.colors.textSecondary} />
          <Text style={styles.rowText}>分镜脚本</Text>
          <Text style={styles.rowHint}>逐镜生成</Text>
          <Ionicons name="chevron-forward" size={16} color={tokens.colors.textCaption} />
        </TouchableOpacity>
        <TouchableOpacity style={styles.row} onPress={about} activeOpacity={0.7}>
          <Ionicons name="information-circle-outline" size={18} color={tokens.colors.textSecondary} />
          <Text style={styles.rowText}>关于</Text>
          <Ionicons name="chevron-forward" size={16} color={tokens.colors.textCaption} />
        </TouchableOpacity>

        <TouchableOpacity style={styles.logoutBtn} onPress={confirmLogout} activeOpacity={0.8}>
          <Ionicons name="log-out-outline" size={18} color={tokens.colors.error} />
          <Text style={styles.logoutText}>退出登录</Text>
        </TouchableOpacity>
      </ScrollView>

      <Modal
        visible={projectOpen}
        transparent
        animationType="fade"
        onRequestClose={() => setProjectOpen(false)}
      >
        <TouchableOpacity
          style={styles.backdrop}
          activeOpacity={1}
          onPress={() => setProjectOpen(false)}
        >
          <View style={styles.sheet} onStartShouldSetResponder={() => true}>
            <View style={styles.sheetHead}>
              <Text style={styles.sheetTitle}>切换项目</Text>
              <TouchableOpacity onPress={() => setProjectOpen(false)} hitSlop={8}>
                <Ionicons name="close" size={20} color={tokens.colors.textSecondary} />
              </TouchableOpacity>
            </View>
            <FlatList
              data={projects}
              keyExtractor={(item) => item.id}
              style={styles.sheetList}
              ListEmptyComponent={<Text style={styles.empty}>还没有项目，先在对话里创建一个。</Text>}
              renderItem={({ item }) => (
                <TouchableOpacity
                  style={[styles.projectItem, currentProject?.id === item.id && styles.projectItemActive]}
                  onPress={() => void selectProject(item)}
                >
                  <View style={styles.projectItemMain}>
                    <Text
                      style={[
                        styles.projectItemText,
                        currentProject?.id === item.id && styles.projectItemTextActive,
                      ]}
                      numberOfLines={1}
                    >
                      {item.name}
                    </Text>
                    <Text style={styles.projectItemMeta}>
                      {item.status === 'active' ? '已保存' : '草稿'} · {item.aspectRatio ?? '16:9'}
                    </Text>
                  </View>
                  {currentProject?.id === item.id && (
                    <Ionicons name="checkmark" size={18} color={tokens.colors.primary} />
                  )}
                </TouchableOpacity>
              )}
            />
          </View>
        </TouchableOpacity>
      </Modal>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: tokens.colors.canvas },
  header: {
    paddingHorizontal: tokens.spacing.lg,
    paddingBottom: tokens.spacing.md,
    backgroundColor: tokens.colors.canvas,
    borderBottomWidth: 1,
    borderBottomColor: tokens.colors.hairline,
  },
  headerTitle: {
    fontSize: tokens.fontSize.xl,
    fontWeight: tokens.fontWeight.bold,
    color: tokens.colors.ink,
  },
  content: {
    padding: tokens.spacing.lg,
    paddingBottom: tokens.spacing.xxl,
    gap: tokens.spacing.sm,
  },
  profileCard: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: tokens.spacing.md,
    padding: tokens.spacing.lg,
    borderRadius: tokens.radius.lg,
    backgroundColor: tokens.colors.surface,
    marginBottom: tokens.spacing.md,
  },
  avatar: {
    width: 52,
    height: 52,
    borderRadius: tokens.radius.full,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: tokens.colors.white,
    borderWidth: 1,
    borderColor: tokens.colors.hairline,
  },
  avatarText: { fontSize: 26, color: tokens.colors.primary },
  profileText: { flex: 1, gap: 2 },
  name: {
    fontSize: tokens.fontSize.lg,
    fontWeight: tokens.fontWeight.semibold,
    color: tokens.colors.ink,
  },
  subtitle: { fontSize: tokens.fontSize.xs, color: tokens.colors.textCaption },
  sectionLabel: {
    marginTop: tokens.spacing.md,
    marginBottom: tokens.spacing.xs,
    marginLeft: tokens.spacing.xs,
    fontSize: tokens.fontSize.xs,
    fontWeight: tokens.fontWeight.semibold,
    color: tokens.colors.textCaption,
    letterSpacing: 0.5,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: tokens.spacing.sm,
    minHeight: 52,
    paddingHorizontal: tokens.spacing.md,
    borderRadius: tokens.radius.md,
    backgroundColor: tokens.colors.white,
    borderWidth: 1,
    borderColor: tokens.colors.hairline,
  },
  rowText: { flex: 1, fontSize: tokens.fontSize.base, color: tokens.colors.ink },
  rowHint: { fontSize: tokens.fontSize.xs, color: tokens.colors.textCaption },
  logoutBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: tokens.spacing.sm,
    height: 50,
    marginTop: tokens.spacing.xl,
    borderRadius: tokens.radius.md,
    backgroundColor: tokens.colors.white,
    borderWidth: 1,
    borderColor: tokens.colors.hairline,
  },
  logoutText: {
    fontSize: tokens.fontSize.base,
    fontWeight: tokens.fontWeight.medium,
    color: tokens.colors.error,
  },
  backdrop: {
    flex: 1,
    justifyContent: 'flex-end',
    backgroundColor: 'rgba(0,0,0,0.35)',
  },
  sheet: {
    maxHeight: '70%',
    paddingTop: tokens.spacing.lg,
    paddingHorizontal: tokens.spacing.lg,
    paddingBottom: tokens.spacing.xxl,
    borderTopLeftRadius: tokens.radius.lg,
    borderTopRightRadius: tokens.radius.lg,
    backgroundColor: tokens.colors.canvas,
  },
  sheetHead: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: tokens.spacing.md,
  },
  sheetTitle: {
    fontSize: tokens.fontSize.md,
    fontWeight: tokens.fontWeight.semibold,
    color: tokens.colors.ink,
  },
  sheetList: { flexGrow: 0 },
  projectItem: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: tokens.spacing.sm,
    paddingVertical: tokens.spacing.md,
    paddingHorizontal: tokens.spacing.md,
    borderRadius: tokens.radius.md,
  },
  projectItemActive: { backgroundColor: tokens.colors.surface },
  projectItemMain: { flex: 1, gap: 2 },
  projectItemText: { fontSize: tokens.fontSize.base, color: tokens.colors.ink },
  projectItemTextActive: {
    fontWeight: tokens.fontWeight.semibold,
    color: tokens.colors.primary,
  },
  projectItemMeta: { fontSize: tokens.fontSize.xs, color: tokens.colors.textCaption },
  empty: {
    paddingVertical: tokens.spacing.xl,
    textAlign: 'center',
    fontSize: tokens.fontSize.sm,
    color: tokens.colors.textCaption,
  },
});
