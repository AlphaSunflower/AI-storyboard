import { View, Text, FlatList, TouchableOpacity, StyleSheet, Image, ActivityIndicator, Modal, Share, Alert } from 'react-native';
import { useState, useEffect } from 'react';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Ionicons } from '@expo/vector-icons';
import { tokens } from '../src/tokens';
import { useAgentStore } from '../src/stores/agentStore';
import { useProjectStore } from '../src/stores/projectStore';
import { assetUrl } from '../src/config';
import type { AgentAsset } from '../src/api/agent';

export default function MaterialsTab() {
  const { assets, loadAssets, deleteAsset, conversations, activeConversationId, selectConversation, loadConversations } = useAgentStore();
  const currentProject = useProjectStore((s) => s.currentProject);
  const [filter, setFilter] = useState<'all' | 'image' | 'video' | 'reference'>('all');
  const [preview, setPreview] = useState<AgentAsset | null>(null);
  const insets = useSafeAreaInsets();

  useEffect(() => {
    if (currentProject) loadConversations();
  }, [currentProject?.id]);

  useEffect(() => { loadAssets(1); }, [activeConversationId]);

  const filtered = assets?.records.filter((a) => filter === 'all' || a.type === filter) ?? [];

  const handleShare = async (url: string) => {
    try { await Share.share({ url: assetUrl(url), message: 'AI Storyboard 素材' }); } catch {}
  };

  const currentConvTitle = conversations.find((c) => c.id === activeConversationId)?.title ?? '未选择会话';

  const renderItem = ({ item }: { item: AgentAsset }) => {
    const url = assetUrl(item.url);
    const isImage = item.type === 'image';
    return (
      <TouchableOpacity style={s.card} onPress={() => setPreview(item)} activeOpacity={0.8}>
        <View style={s.thumb}>
          {isImage ? (
            <Image source={{ uri: url }} style={s.thumbImg} resizeMode="cover" />
          ) : (
            <View style={s.videoThumb}>
              <Ionicons name="play-outline" size={18} color={tokens.colors.textCaption} />
            </View>
          )}
          {item.status === 'generating' && (
            <View style={[StyleSheet.absoluteFill, s.thumbOverlay]}>
              <ActivityIndicator size="small" color={tokens.colors.white} />
            </View>
          )}
        </View>
        <View style={s.cardInfo}>
          <Text style={s.cardTitle}>{item.type === 'image' ? '图片' : item.type === 'video' ? '视频' : '参考图'}</Text>
          <Text style={s.cardDate}>{new Date(item.createdAt).toLocaleDateString('zh-CN')}</Text>
          {item.status === 'generating' && <Text style={s.statusGen}>生成中</Text>}
          {item.error && <Text style={s.statusErr} numberOfLines={1}>{item.error}</Text>}
        </View>
        <TouchableOpacity style={s.shareBtn} onPress={() => handleShare(item.url)}>
          <Ionicons name="share-outline" size={16} color={tokens.colors.textSecondary} />
        </TouchableOpacity>
      </TouchableOpacity>
    );
  };

  return (
    <View style={s.container}>
      <View style={[s.header, { height: 56 + insets.top, paddingTop: insets.top }]}>
        <Text style={s.headerTitle}>产出素材</Text>
        <Text style={s.headerCount}>{assets?.total ?? 0} 项</Text>
      </View>

      {/* 会话选择器 */}
      {conversations.length > 0 && (
        <View style={s.convRow}>
          <FlatList
            horizontal
            showsHorizontalScrollIndicator={false}
            data={conversations}
            keyExtractor={(c) => c.id}
            contentContainerStyle={{ paddingHorizontal: 12, gap: 6 }}
            renderItem={({ item: c }) => (
              <TouchableOpacity
                style={[s.convChip, activeConversationId === c.id && s.convChipActive]}
                onPress={() => selectConversation(c.id)}
              >
                <Text style={[s.convChipText, activeConversationId === c.id && s.convChipTextActive]} numberOfLines={1}>
                  {c.title || '新对话'}
                </Text>
              </TouchableOpacity>
            )}
          />
        </View>
      )}

      <View style={s.filterRow}>
        {(['all', 'image', 'video', 'reference'] as const).map((f) => (
          <TouchableOpacity key={f} style={[s.filterBtn, filter === f && s.filterBtnActive]} onPress={() => setFilter(f)}>
            <Text style={[s.filterText, filter === f && s.filterTextActive]}>
              {f === 'all' ? '全部' : f === 'image' ? '图片' : f === 'video' ? '视频' : '参考图'}
            </Text>
          </TouchableOpacity>
        ))}
      </View>

      <FlatList
        data={filtered}
        keyExtractor={(item) => item.id}
        renderItem={renderItem}
        contentContainerStyle={s.list}
        ListEmptyComponent={
          <View style={s.empty}>
            <Ionicons name="images-outline" size={48} color={tokens.colors.textCaption} />
            <Text style={s.emptyText}>暂无素材</Text>
            <Text style={s.emptyHint}>
              {activeConversationId ? '在对话中生成图片或视频后会出现在这里' : '请先选择一个会话'}
            </Text>
          </View>
        }
      />

      {/* 预览 */}
      <Modal visible={!!preview} transparent animationType="fade" onRequestClose={() => setPreview(null)}>
        {preview && (
          <View style={s.previewBg}>
            <View style={[s.previewHeader, { paddingTop: insets.top + 8 }]}>
              <Text style={s.previewTitle}>{preview.type === 'image' ? '图片' : '视频'}</Text>
              <TouchableOpacity onPress={() => setPreview(null)}>
                <Ionicons name="close" size={22} color={tokens.colors.white} />
              </TouchableOpacity>
            </View>
            <View style={s.previewBody}>
              <Image source={{ uri: assetUrl(preview.url) }} style={s.previewImg} resizeMode="contain" />
            </View>
            <View style={[s.previewFooter, { paddingBottom: insets.bottom + 12 }]}>
              <TouchableOpacity style={s.previewBtn} onPress={() => handleShare(preview.url)}>
                <Text style={s.previewBtnText}>分享/保存</Text>
              </TouchableOpacity>
              <TouchableOpacity style={s.previewBtnDanger} onPress={() => { setPreview(null); deleteAsset(preview.id); }}>
                <Text style={s.previewBtnDangerText}>删除</Text>
              </TouchableOpacity>
            </View>
          </View>
        )}
      </Modal>
    </View>
  );
}

const s = StyleSheet.create({
  container: { flex: 1, backgroundColor: tokens.colors.canvas },
  header: { flexDirection: 'row', alignItems: 'center', borderBottomWidth: 1, borderBottomColor: tokens.colors.hairline, paddingHorizontal: 16, gap: 8 },
  headerTitle: { flex: 1, fontSize: tokens.fontSize.md, fontWeight: tokens.fontWeight.bold, color: tokens.colors.ink },
  headerCount: { fontSize: tokens.fontSize.sm, color: tokens.colors.textCaption },
  convRow: { paddingVertical: 8, borderBottomWidth: 1, borderBottomColor: tokens.colors.hairline },
  convChip: { paddingHorizontal: 12, paddingVertical: 6, borderRadius: tokens.radius.full, backgroundColor: tokens.colors.surface, maxWidth: 140 },
  convChipActive: { backgroundColor: tokens.colors.primary },
  convChipText: { fontSize: tokens.fontSize.xs, color: tokens.colors.textSecondary, fontWeight: tokens.fontWeight.medium },
  convChipTextActive: { color: tokens.colors.white },
  filterRow: { flexDirection: 'row', paddingHorizontal: 16, paddingVertical: 10, gap: 8 },
  filterBtn: { paddingHorizontal: 14, paddingVertical: 6, borderRadius: tokens.radius.full, backgroundColor: tokens.colors.surface },
  filterBtnActive: { backgroundColor: tokens.colors.primary },
  filterText: { fontSize: tokens.fontSize.sm, color: tokens.colors.textSecondary, fontWeight: tokens.fontWeight.medium },
  filterTextActive: { color: tokens.colors.white },
  list: { padding: 12, gap: 10 },
  card: { flexDirection: 'row', backgroundColor: tokens.colors.white, borderRadius: tokens.radius.md, borderWidth: 1, borderColor: tokens.colors.hairline, padding: 10, gap: 12, alignItems: 'center' },
  thumb: { width: 64, height: 48, borderRadius: tokens.radius.sm, overflow: 'hidden', backgroundColor: tokens.colors.surface },
  thumbImg: { width: '100%', height: '100%' },
  videoThumb: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  thumbOverlay: { backgroundColor: 'rgba(0,0,0,0.4)', alignItems: 'center', justifyContent: 'center' },
  cardInfo: { flex: 1, gap: 2 },
  cardTitle: { fontSize: tokens.fontSize.sm, fontWeight: tokens.fontWeight.medium, color: tokens.colors.ink },
  cardDate: { fontSize: tokens.fontSize.xs, color: tokens.colors.textCaption },
  statusGen: { fontSize: tokens.fontSize.xs, color: tokens.colors.primary },
  statusErr: { fontSize: tokens.fontSize.xs, color: tokens.colors.error },
  shareBtn: { padding: 8 },
  empty: { alignItems: 'center', paddingTop: 80, gap: 8 },
  emptyText: { fontSize: tokens.fontSize.base, color: tokens.colors.textSecondary },
  emptyHint: { fontSize: tokens.fontSize.sm, color: tokens.colors.textCaption, textAlign: 'center', paddingHorizontal: 40 },
  previewBg: { flex: 1, backgroundColor: 'rgba(0,0,0,0.92)' },
  previewHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 16, paddingBottom: 8 },
  previewTitle: { fontSize: tokens.fontSize.base, color: tokens.colors.white, fontWeight: tokens.fontWeight.semibold },
  previewBody: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 12 },
  previewImg: { width: '100%', height: '100%' },
  previewFooter: { flexDirection: 'row', gap: 8, paddingHorizontal: 16, paddingTop: 12 },
  previewBtn: { flex: 1, height: 44, borderRadius: 10, borderWidth: 1, borderColor: 'rgba(255,255,255,0.3)', alignItems: 'center', justifyContent: 'center' },
  previewBtnText: { fontSize: tokens.fontSize.base, color: tokens.colors.white, fontWeight: tokens.fontWeight.medium },
  previewBtnDanger: { height: 44, paddingHorizontal: 18, borderRadius: 10, borderWidth: 1, borderColor: tokens.colors.error, alignItems: 'center', justifyContent: 'center' },
  previewBtnDangerText: { fontSize: tokens.fontSize.base, color: tokens.colors.error, fontWeight: tokens.fontWeight.medium },
});

