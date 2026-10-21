import { View, Text, TouchableOpacity, FlatList, StyleSheet, Modal, TextInput, Alert, Linking, Image } from 'react-native';
import { useState, useEffect } from 'react';
import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { tokens } from '../../tokens';
import { useProjectStore } from '../../stores/projectStore';
import { useAgentStore } from '../../stores/agentStore';
import { useAuthStore } from '../../stores/authStore';
import type { ProjectResponse } from '../../api/projects';

interface SidebarDrawerProps {
  visible: boolean;
  onClose: () => void;
  onSelectConversation: (id: string) => void;
  conversations: { id: string; title: string; updatedAt: string; status?: string }[];
  activeConversationId: string | null;
  onNewConversation: () => void;
  /** 打开侧栏时是否自动展开项目选择列表（编辑器标题点击场景） */
  openProjectsOnOpen?: boolean;
}

export function SidebarDrawer({
  visible, onClose, onSelectConversation, conversations, activeConversationId, onNewConversation, openProjectsOnOpen = false,
}: SidebarDrawerProps) {
  const { projects, currentProject, loadProjects, loadProject, createProject } = useProjectStore();
  const { renameConversation, deleteConversation, archiveConversation } = useAgentStore();
  const { logout, user } = useAuthStore();
  const router = useRouter();
  const [projectOpen, setProjectOpen] = useState(false);
  const [newProjectName, setNewProjectName] = useState('');
  const [showNewProject, setShowNewProject] = useState(false);
  // 对话管理状态
  const [selectMode, setSelectMode] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [renameTarget, setRenameTarget] = useState<{ id: string; title: string } | null>(null);
  const [renameTitle, setRenameTitle] = useState('');

  useEffect(() => {
    if (visible) {
      loadProjects();
      setProjectOpen(openProjectsOnOpen);
    }
  }, [visible]);

  const handleSelectProject = async (p: ProjectResponse) => {
    await loadProject(p.id);
    setProjectOpen(false);
  };

  const handleCreateProject = async () => {
    const name = newProjectName.trim();
    if (!name) return;
    await createProject(name, 'custom', '16:9');
    setNewProjectName('');
    setShowNewProject(false);
  };

  const handleLogout = () => {
    Alert.alert('确认退出', '确定要退出登录吗？', [
      { text: '取消', style: 'cancel' },
      { text: '退出', style: 'destructive', onPress: () => { logout(); onClose(); } },
    ]);
  };

  // 长按对话 → 操作菜单
  const handleLongPress = (item: { id: string; title: string }) => {
    Alert.alert(item.title || '新对话', '选择操作', [
      { text: '重命名', onPress: () => { setRenameTarget({ id: item.id, title: item.title || '' }); setRenameTitle(item.title || ''); } },
      { text: '归档', onPress: () => archiveConversation(item.id) },
      { text: '删除', style: 'destructive', onPress: () => {
        Alert.alert('确认删除', '删除后不可恢复', [
          { text: '取消', style: 'cancel' },
          { text: '删除', style: 'destructive', onPress: () => deleteConversation(item.id) },
        ]);
      }},
      { text: '取消', style: 'cancel' },
    ]);
  };

  // 多选切换
  const toggleSelect = (id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  // 批量删除
  const handleBatchDelete = () => {
    if (selectedIds.size === 0) return;
    Alert.alert('确认删除', `删除 ${selectedIds.size} 个对话？`, [
      { text: '取消', style: 'cancel' },
      { text: '删除', style: 'destructive', onPress: () => {
        selectedIds.forEach((id) => deleteConversation(id));
        setSelectedIds(new Set());
        setSelectMode(false);
      }},
    ]);
  };

  // 重命名确认
  const handleRename = async () => {
    if (!renameTarget) return;
    const title = renameTitle.trim();
    if (!title) return;
    await renameConversation(renameTarget.id, title);
    setRenameTarget(null);
  };

  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose}>
      <TouchableOpacity style={s.backdrop} activeOpacity={1} onPress={onClose}>
        <View style={s.drawer} onStartShouldSetResponder={() => true}>
          <View style={s.drawerHeader}>
            <View style={s.drawerTitle}>
              <Image source={require('../../../assets/moon-mark.png')} style={s.drawerLogo} resizeMode="contain" />
              <Text style={s.drawerTitleText}>Moon 智能体</Text>
            </View>
            <TouchableOpacity onPress={onClose} style={s.closeBtn}>
              <Ionicons name="close" size={18} color={tokens.colors.textSecondary} />
            </TouchableOpacity>
          </View>

          <View style={s.section}>
            <TouchableOpacity style={s.projectBtn} onPress={() => setProjectOpen(!projectOpen)}>
              <Ionicons name="folder-outline" size={14} color={tokens.colors.textSecondary} />
              <Text style={s.projectBtnText} numberOfLines={1}>
                {currentProject?.name ?? '选择项目'}
              </Text>
              <Ionicons name={projectOpen ? 'chevron-up' : 'chevron-down'} size={14} color={tokens.colors.textCaption} />
            </TouchableOpacity>

            {projectOpen && (
              <View style={s.projectList}>
                {projects.map((p: ProjectResponse) => (
                  <TouchableOpacity
                    key={p.id}
                    style={[s.projectItem, currentProject?.id === p.id && s.projectItemActive]}
                    onPress={() => handleSelectProject(p)}
                  >
                    <Text style={[s.projectItemText, currentProject?.id === p.id && s.projectItemTextActive]} numberOfLines={1}>
                      {p.name}
                    </Text>
                    <Text style={s.projectItemStatus}>{p.status === 'active' ? '✓' : '草稿'}</Text>
                  </TouchableOpacity>
                ))}
                <TouchableOpacity style={s.newProjectBtn} onPress={() => setShowNewProject(true)}>
                  <Ionicons name="add-outline" size={16} color={tokens.colors.primary} />
                  <Text style={s.newProjectText}>新建项目</Text>
                </TouchableOpacity>
              </View>
            )}

            {showNewProject && (
              <View style={s.newProjectForm}>
                <TextInput
                  style={s.newProjectInput}
                  value={newProjectName}
                  onChangeText={setNewProjectName}
                  placeholder="项目名称"
                  placeholderTextColor={tokens.colors.textCaption}
                  autoFocus
                />
                <View style={s.newProjectActions}>
                  <TouchableOpacity style={s.cancelBtn} onPress={() => setShowNewProject(false)}>
                    <Text style={s.cancelText}>取消</Text>
                  </TouchableOpacity>
                  <TouchableOpacity style={s.confirmBtn} onPress={handleCreateProject}>
                    <Text style={s.confirmText}>创建</Text>
                  </TouchableOpacity>
                </View>
              </View>
            )}
          </View>

          <TouchableOpacity style={s.newChatBtn} onPress={() => { onNewConversation(); onClose(); }}>
            <Ionicons name="add-outline" size={18} color={tokens.colors.white} />
            <Text style={s.newChatText}>新建对话</Text>
          </TouchableOpacity>

          {/* 历史对话 */}
          <View style={s.convSection}>
            <View style={s.convHeader}>
              <Text style={s.convLabel}>历史对话</Text>
              <View style={s.convHeaderActions}>
                {selectMode && selectedIds.size > 0 && (
                  <TouchableOpacity style={s.batchDeleteBtn} onPress={handleBatchDelete}>
                    <Text style={s.batchDeleteText}>删除({selectedIds.size})</Text>
                  </TouchableOpacity>
                )}
                <TouchableOpacity onPress={() => { setSelectMode(!selectMode); setSelectedIds(new Set()); }}>
                  <Text style={s.selectModeText}>{selectMode ? '取消' : '多选'}</Text>
                </TouchableOpacity>
              </View>
            </View>
            <FlatList
              data={conversations}
              keyExtractor={(item) => item.id}
              style={s.convList}
              renderItem={({ item }) => {
                const isActive = activeConversationId === item.id;
                const isArchived = item.status === 'archived';
                const isSelected = selectedIds.has(item.id);
                return (
                  <TouchableOpacity
                    style={[s.convItem, isActive && s.convItemActive, isSelected && s.convItemSelected]}
                    onPress={() => selectMode ? toggleSelect(item.id) : (onSelectConversation(item.id), onClose())}
                    onLongPress={() => !selectMode && handleLongPress(item)}
                  >
                    {selectMode && (
                      <Ionicons
                        name={isSelected ? 'checkbox' : 'square-outline'}
                        size={16}
                        color={isSelected ? tokens.colors.primary : tokens.colors.textCaption}
                        style={{ marginRight: 8 }}
                      />
                    )}
                    <Text style={[s.convItemText, isActive && s.convItemTextActive, isArchived && s.convItemArchived]} numberOfLines={1}>
                      {item.title || '新对话'}
                    </Text>
                    {isArchived && <Text style={s.archivedBadge}>已归档</Text>}
                  </TouchableOpacity>
                );
              }}
              ListEmptyComponent={
                <Text style={s.convEmpty}>暂无对话</Text>
              }
            />
          </View>

          {/* 工具栏 */}
          <View style={s.utilityRow}>
            <TouchableOpacity style={s.utilityBtn} onPress={() => { onClose(); router.replace('/(tabs)/assets'); }}>
              <Ionicons name="folder-open-outline" size={16} color={tokens.colors.textSecondary} />
              <Text style={s.utilityText}>项目库</Text>
            </TouchableOpacity>
            <TouchableOpacity style={s.utilityBtn} onPress={() => Linking.openURL('http://192.168.100.61:8080/docs')}>
              <Ionicons name="document-text-outline" size={16} color={tokens.colors.textSecondary} />
              <Text style={s.utilityText}>文档</Text>
            </TouchableOpacity>
            <TouchableOpacity style={s.utilityBtn} onPress={() => Alert.alert('设置', `用户：${user?.displayName ?? '未登录'}\n邮箱：${user?.userId ?? '-'}`, [{ text: '确定' }])}>
              <Ionicons name="settings-outline" size={16} color={tokens.colors.textSecondary} />
              <Text style={s.utilityText}>设置</Text>
            </TouchableOpacity>
          </View>

          <View style={s.footer}>
            <View style={s.userInfo}>
              <Text style={s.userAvatar}>☽</Text>
              <Text style={s.userName} numberOfLines={1}>{user?.displayName ?? '用户'}</Text>
            </View>
            <TouchableOpacity style={s.logoutBtn} onPress={handleLogout}>
              <Text style={s.logoutText}>退出</Text>
            </TouchableOpacity>
          </View>
        </View>
      </TouchableOpacity>

      {/* 重命名弹窗 */}
      <Modal visible={!!renameTarget} transparent animationType="fade" onRequestClose={() => setRenameTarget(null)}>
        <TouchableOpacity style={s.renameBackdrop} activeOpacity={1} onPress={() => setRenameTarget(null)}>
          <View style={s.renameCard} onStartShouldSetResponder={() => true}>
            <Text style={s.renameTitle}>重命名对话</Text>
            <TextInput
              style={s.renameInput}
              value={renameTitle}
              onChangeText={setRenameTitle}
              placeholder="对话标题"
              placeholderTextColor={tokens.colors.textCaption}
              autoFocus
            />
            <View style={s.renameActions}>
              <TouchableOpacity style={s.cancelBtn} onPress={() => setRenameTarget(null)}>
                <Text style={s.cancelText}>取消</Text>
              </TouchableOpacity>
              <TouchableOpacity style={s.confirmBtn} onPress={handleRename}>
                <Text style={s.confirmText}>确定</Text>
              </TouchableOpacity>
            </View>
          </View>
        </TouchableOpacity>
      </Modal>
    </Modal>
  );
}

const s = StyleSheet.create({
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.35)' },
  drawer: {
    position: 'absolute', top: 0, left: 0, bottom: 0,
    width: '80%', maxWidth: 320,
    backgroundColor: tokens.colors.canvas,
    flexDirection: 'column',
    shadowColor: '#000', shadowOffset: { width: 4, height: 0 },
    shadowOpacity: 0.12, shadowRadius: 24, elevation: 16,
  },
  drawerHeader: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    paddingHorizontal: 16, paddingVertical: 14,
    borderBottomWidth: 1, borderBottomColor: tokens.colors.hairline,
  },
  drawerTitle: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  drawerLogo: { width: 20, height: 20 },
  drawerTitleText: { fontSize: tokens.fontSize.md, fontWeight: tokens.fontWeight.bold, color: tokens.colors.ink },
  closeBtn: { width: 32, height: 32, alignItems: 'center', justifyContent: 'center', borderRadius: 8 },
  section: { paddingHorizontal: 12, paddingTop: 10 },
  projectBtn: {
    flexDirection: 'row', alignItems: 'center', height: 40,
    paddingHorizontal: 12, borderRadius: 10, backgroundColor: tokens.colors.surface, gap: 8,
  },
  projectBtnText: { flex: 1, fontSize: tokens.fontSize.base, color: tokens.colors.ink },
  projectList: {
    marginTop: 6, backgroundColor: tokens.colors.white,
    borderRadius: 10, borderWidth: 1, borderColor: tokens.colors.hairline, overflow: 'hidden',
  },
  projectItem: { flexDirection: 'row', alignItems: 'center', height: 40, paddingHorizontal: 12, gap: 8 },
  projectItemActive: { backgroundColor: tokens.colors.surface },
  projectItemText: { flex: 1, fontSize: tokens.fontSize.sm, color: tokens.colors.ink },
  projectItemTextActive: { fontWeight: tokens.fontWeight.semibold, color: tokens.colors.primary },
  projectItemStatus: { fontSize: tokens.fontSize.xs, color: tokens.colors.textCaption },
  newProjectBtn: { height: 36, alignItems: 'center', justifyContent: 'center', borderTopWidth: 1, borderTopColor: tokens.colors.hairline },
  newProjectText: { fontSize: tokens.fontSize.sm, color: tokens.colors.primary, fontWeight: tokens.fontWeight.medium },
  newProjectForm: {
    marginTop: 8, padding: 12, backgroundColor: tokens.colors.white,
    borderRadius: 10, borderWidth: 1, borderColor: tokens.colors.hairline,
  },
  newProjectInput: {
    height: 40, borderWidth: 1, borderColor: tokens.colors.hairline,
    borderRadius: tokens.radius.sm, paddingHorizontal: 10,
    fontSize: tokens.fontSize.sm, color: tokens.colors.ink, marginBottom: 8,
  },
  newProjectActions: { flexDirection: 'row', justifyContent: 'flex-end', gap: 8 },
  cancelBtn: { paddingHorizontal: 14, paddingVertical: 6, borderRadius: tokens.radius.sm, backgroundColor: tokens.colors.surface },
  cancelText: { fontSize: tokens.fontSize.sm, color: tokens.colors.textSecondary },
  confirmBtn: { paddingHorizontal: 14, paddingVertical: 6, borderRadius: tokens.radius.sm, backgroundColor: tokens.colors.primary },
  confirmText: { fontSize: tokens.fontSize.sm, color: tokens.colors.white, fontWeight: tokens.fontWeight.medium },
  newChatBtn: {
    flexDirection: 'row', alignItems: 'center', height: 44,
    marginHorizontal: 12, marginTop: 10, paddingHorizontal: 14,
    borderRadius: 10, backgroundColor: tokens.colors.primary, gap: 8,
  },
  newChatText: { fontSize: tokens.fontSize.base, color: tokens.colors.white, fontWeight: tokens.fontWeight.semibold },
  convSection: { flex: 1, marginTop: 14, paddingHorizontal: 12 },
  convHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 6, paddingHorizontal: 4 },
  convLabel: {
    fontSize: tokens.fontSize.xs, fontWeight: tokens.fontWeight.semibold,
    color: tokens.colors.textCaption, textTransform: 'uppercase', letterSpacing: 0.5,
  },
  convHeaderActions: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  selectModeText: { fontSize: tokens.fontSize.xs, color: tokens.colors.primary, fontWeight: tokens.fontWeight.medium },
  batchDeleteBtn: { paddingHorizontal: 8, paddingVertical: 3, borderRadius: 4, backgroundColor: tokens.colors.error },
  batchDeleteText: { fontSize: tokens.fontSize.xs, color: tokens.colors.white, fontWeight: tokens.fontWeight.medium },
  convList: { flex: 1 },
  convItem: { flexDirection: 'row', alignItems: 'center', height: 40, paddingHorizontal: 12, borderRadius: 8, marginBottom: 2 },
  convItemActive: { backgroundColor: tokens.colors.surface },
  convItemSelected: { backgroundColor: 'rgba(204, 120, 92, 0.1)' },
  convItemText: { flex: 1, fontSize: tokens.fontSize.sm, color: tokens.colors.textSecondary },
  convItemTextActive: { color: tokens.colors.ink, fontWeight: tokens.fontWeight.medium },
  convItemArchived: { opacity: 0.5 },
  archivedBadge: { fontSize: 10, color: tokens.colors.textCaption, marginLeft: 4 },
  convEmpty: { fontSize: tokens.fontSize.sm, color: tokens.colors.textCaption, textAlign: 'center', paddingVertical: 20 },
  utilityRow: {
    flexDirection: 'row', gap: 6, paddingHorizontal: 12, paddingVertical: 8,
    borderTopWidth: 1, borderTopColor: tokens.colors.hairline,
  },
  utilityBtn: { flex: 1, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 4, height: 36, borderRadius: 8, backgroundColor: tokens.colors.surface },
  utilityText: { fontSize: tokens.fontSize.xs, color: tokens.colors.textSecondary },
  footer: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    paddingHorizontal: 16, paddingVertical: 12,
    borderTopWidth: 1, borderTopColor: tokens.colors.hairline,
  },
  userInfo: { flexDirection: 'row', alignItems: 'center', gap: 8, flex: 1 },
  userAvatar: { fontSize: 20, color: tokens.colors.primary },
  userName: { fontSize: tokens.fontSize.sm, color: tokens.colors.ink, fontWeight: tokens.fontWeight.medium, flex: 1 },
  logoutBtn: { paddingHorizontal: 12, paddingVertical: 6, borderRadius: tokens.radius.sm, backgroundColor: tokens.colors.surface },
  logoutText: { fontSize: tokens.fontSize.sm, color: tokens.colors.error, fontWeight: tokens.fontWeight.medium },
  renameBackdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.35)', justifyContent: 'center', padding: 32 },
  renameCard: { backgroundColor: tokens.colors.white, borderRadius: 12, padding: 20 },
  renameTitle: { fontSize: tokens.fontSize.base, fontWeight: tokens.fontWeight.semibold, color: tokens.colors.ink, marginBottom: 12 },
  renameInput: {
    height: 44, borderWidth: 1, borderColor: tokens.colors.hairline,
    borderRadius: tokens.radius.sm, paddingHorizontal: 12,
    fontSize: tokens.fontSize.base, color: tokens.colors.ink, marginBottom: 16,
  },
  renameActions: { flexDirection: 'row', justifyContent: 'flex-end', gap: 8 },
});
