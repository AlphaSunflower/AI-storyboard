import { View, Text, FlatList, TouchableOpacity, StyleSheet, Image, ActivityIndicator, Modal, TextInput, Alert, ScrollView, KeyboardAvoidingView, Platform } from 'react-native';
import { useState, useEffect } from 'react';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Ionicons } from '@expo/vector-icons';
import * as ImagePicker from 'expo-image-picker';
import { tokens } from '../../src/tokens';
import { useAssetStore } from '../../src/stores/assetStore';
import { useProjectStore } from '../../src/stores/projectStore';
import { assetUrl } from '../../src/config';
import type { Asset, AssetType } from '../../src/api/assets';
import { TYPE_LABEL } from '../../src/api/assets';

const TYPES: { value: AssetType; label: string }[] = [
  { value: 'character', label: '人物' },
  { value: 'prop', label: '道具' },
  { value: 'scene', label: '场景' },
];

export default function AssetsTab() {
  const { assets, loading, loadAssets, createAsset, updateAsset, deleteAsset, uploadImage, deleteImage } = useAssetStore();
  const currentProject = useProjectStore((s) => s.currentProject);
  const insets = useSafeAreaInsets();

  const [typeFilter, setTypeFilter] = useState<'all' | AssetType>('all');
  const [createOpen, setCreateOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<Asset | null>(null);
  const [detailAsset, setDetailAsset] = useState<Asset | null>(null);

  // 新建表单
  const [createType, setCreateType] = useState<AssetType>('character');
  const [createName, setCreateName] = useState('');
  const [createDesc, setCreateDesc] = useState('');
  const [createImages, setCreateImages] = useState<{ uri: string; name: string }[]>([]);

  // 编辑表单
  const [editName, setEditName] = useState('');
  const [editDesc, setEditDesc] = useState('');

  useEffect(() => { loadAssets(); }, [currentProject?.id]);

  const filtered = typeFilter === 'all' ? assets : assets.filter((a) => a.type === typeFilter);

  const handleCreate = async () => {
    if (!createName.trim()) { Alert.alert('提示', '请输入资产名称'); return; }
    const id = await createAsset({ type: createType, name: createName.trim(), description: createDesc.trim() || undefined });
    if (id) {
      // 上传选中的图片
      for (const img of createImages) {
        await uploadImage(id, { uri: img.uri, name: img.name, type: 'image/jpeg' });
      }
      setCreateOpen(false);
      setCreateName('');
      setCreateDesc('');
      setCreateImages([]);
    }
  };

  const handlePickCreateImages = async () => {
    const result = await ImagePicker.launchImageLibraryAsync({ mediaTypes: ['images'], quality: 0.8, allowsMultipleSelection: true });
    if (result.canceled || !result.assets.length) return;
    setCreateImages((prev) => [
      ...prev,
      ...result.assets.map((a) => ({ uri: a.uri, name: a.fileName || `image_${Date.now()}.jpg` })),
    ]);
  };

  const handleEdit = async () => {
    if (!editTarget || !editName.trim()) return;
    await updateAsset(editTarget.id, { name: editName.trim(), description: editDesc.trim() || undefined });
    setEditTarget(null);
  };

  const handleDelete = (a: Asset) => {
    Alert.alert('删除资产', `确定删除「${a.name}」？图片与分镜关联将一并删除。`, [
      { text: '取消', style: 'cancel' },
      { text: '删除', style: 'destructive', onPress: () => deleteAsset(a.id) },
    ]);
  };

  const handleUploadImage = async (assetId: string) => {
    const result = await ImagePicker.launchImageLibraryAsync({ mediaTypes: ['images'], quality: 0.8 });
    if (result.canceled || !result.assets[0]) return;
    const asset = result.assets[0];
    await uploadImage(assetId, { uri: asset.uri, name: asset.fileName || 'image.jpg', type: 'image/jpeg' });
    // 刷新详情
    const updated = useAssetStore.getState().assets.find((a) => a.id === assetId);
    if (updated) setDetailAsset(updated);
  };

  const handleDeleteImage = async (assetId: string, imageId: string) => {
    await deleteImage(assetId, imageId);
    const updated = useAssetStore.getState().assets.find((a) => a.id === assetId);
    if (updated) setDetailAsset(updated);
  };

  const renderCard = ({ item }: { item: Asset }) => {
    const cover = item.images[0];
    return (
      <TouchableOpacity style={s.card} onPress={() => setDetailAsset(item)} activeOpacity={0.8}>
        <View style={s.cardThumb}>
          {cover ? (
            <Image source={{ uri: assetUrl(cover.url) }} style={s.cardThumbImg} resizeMode="cover" />
          ) : (
            <Ionicons name="image-outline" size={24} color={tokens.colors.textCaption} />
          )}
        </View>
        <View style={s.cardInfo}>
          <View style={s.cardTitleRow}>
            <Text style={s.cardTitle} numberOfLines={1}>{item.name}</Text>
            <View style={s.typeBadge}>
              <Text style={s.typeBadgeText}>{TYPE_LABEL[item.type]}</Text>
            </View>
          </View>
          <Text style={s.cardDesc} numberOfLines={1}>{item.description || '（无文字约束）'}</Text>
          {item.images.length > 1 && <Text style={s.imgCount}>{item.images.length} 图</Text>}
        </View>
        <View style={s.cardActions}>
          <TouchableOpacity style={s.iconBtn} onPress={() => { setEditTarget(item); setEditName(item.name); setEditDesc(item.description); }}>
            <Ionicons name="create-outline" size={16} color={tokens.colors.textSecondary} />
          </TouchableOpacity>
          <TouchableOpacity style={s.iconBtn} onPress={() => handleDelete(item)}>
            <Ionicons name="trash-outline" size={16} color={tokens.colors.error} />
          </TouchableOpacity>
        </View>
      </TouchableOpacity>
    );
  };

  return (
    <View style={s.container}>
      {/* Header */}
      <View style={[s.header, { height: 56 + insets.top, paddingTop: insets.top }]}>
        <Text style={s.headerTitle}>项目库</Text>
        <TouchableOpacity style={s.addBtn} onPress={() => setCreateOpen(true)}>
          <Ionicons name="add" size={20} color={tokens.colors.white} />
          <Text style={s.addBtnText}>新建</Text>
        </TouchableOpacity>
      </View>

      {/* Type filter */}
      <View style={s.filterRow}>
        {(['all', 'character', 'prop', 'scene'] as const).map((t) => (
          <TouchableOpacity key={t} style={[s.filterBtn, typeFilter === t && s.filterBtnActive]} onPress={() => setTypeFilter(t)}>
            <Text style={[s.filterText, typeFilter === t && s.filterTextActive]}>
              {t === 'all' ? '全部' : TYPE_LABEL[t]}
            </Text>
          </TouchableOpacity>
        ))}
      </View>

      {/* List */}
      {loading && assets.length === 0 ? (
        <View style={s.empty}><ActivityIndicator color={tokens.colors.primary} /></View>
      ) : (
        <FlatList
          data={filtered}
          keyExtractor={(item) => item.id}
          renderItem={renderCard}
          contentContainerStyle={s.list}
          ListEmptyComponent={
            <View style={s.empty}>
              <Ionicons name="cube-outline" size={48} color={tokens.colors.textCaption} />
              <Text style={s.emptyText}>暂无资产</Text>
              <Text style={s.emptyHint}>点右上角「新建」创建人物/道具/场景卡，约束图片和视频生成</Text>
            </View>
          }
        />
      )}

      {/* 新建弹窗 */}
      <Modal visible={createOpen} transparent animationType="fade" onRequestClose={() => { setCreateOpen(false); setCreateImages([]); }}>
        <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={s.modalOverlay}>
          <View style={s.modalPanel}>
            <Text style={s.modalTitle}>新建资产</Text>

            <Text style={s.label}>类型</Text>
            <View style={s.typeRow}>
              {TYPES.map((t) => (
                <TouchableOpacity key={t.value} style={[s.typeBtn, createType === t.value && s.typeBtnActive]} onPress={() => setCreateType(t.value)}>
                  <Text style={[s.typeBtnText, createType === t.value && s.typeBtnTextActive]}>{t.label}</Text>
                </TouchableOpacity>
              ))}
            </View>

            <Text style={s.label}>名称</Text>
            <TextInput style={s.input} value={createName} onChangeText={setCreateName} placeholder="如：主角小明" placeholderTextColor={tokens.colors.textCaption} />

            <Text style={s.label}>文字约束（可选）</Text>
            <TextInput style={[s.input, s.textArea]} value={createDesc} onChangeText={setCreateDesc} placeholder="描述外观、风格等，约束生成结果" placeholderTextColor={tokens.colors.textCaption} multiline numberOfLines={3} />

            <Text style={s.label}>参考图片（可选）</Text>
            <View style={s.createImageGrid}>
              {createImages.map((img, i) => (
                <View key={i} style={s.createImageItem}>
                  <Image source={{ uri: img.uri }} style={s.createImageImg} resizeMode="cover" />
                  <TouchableOpacity style={s.createImageRemove} onPress={() => setCreateImages((prev) => prev.filter((_, idx) => idx !== i))}>
                    <Ionicons name="close-circle" size={18} color={tokens.colors.error} />
                  </TouchableOpacity>
                </View>
              ))}
              <TouchableOpacity style={s.createImageAdd} onPress={handlePickCreateImages}>
                <Ionicons name="add" size={24} color={tokens.colors.textCaption} />
              </TouchableOpacity>
            </View>

            <View style={s.modalActions}>
              <TouchableOpacity style={s.cancelBtn} onPress={() => { setCreateOpen(false); setCreateImages([]); }}>
                <Text style={s.cancelBtnText}>取消</Text>
              </TouchableOpacity>
              <TouchableOpacity style={[s.confirmBtn, !createName.trim() && s.confirmBtnDisabled]} onPress={handleCreate} disabled={!createName.trim()}>
                <Text style={s.confirmBtnText}>创建</Text>
              </TouchableOpacity>
            </View>
          </View>
        </KeyboardAvoidingView>
      </Modal>

      {/* 编辑弹窗 */}
      <Modal visible={!!editTarget} transparent animationType="fade" onRequestClose={() => setEditTarget(null)}>
        <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={s.modalOverlay}>
          <View style={s.modalPanel}>
            <Text style={s.modalTitle}>编辑资产</Text>

            <Text style={s.label}>名称</Text>
            <TextInput style={s.input} value={editName} onChangeText={setEditName} />

            <Text style={s.label}>文字约束</Text>
            <TextInput style={[s.input, s.textArea]} value={editDesc} onChangeText={setEditDesc} multiline numberOfLines={3} />

            <View style={s.modalActions}>
              <TouchableOpacity style={s.cancelBtn} onPress={() => setEditTarget(null)}>
                <Text style={s.cancelBtnText}>取消</Text>
              </TouchableOpacity>
              <TouchableOpacity style={[s.confirmBtn, !editName.trim() && s.confirmBtnDisabled]} onPress={handleEdit} disabled={!editName.trim()}>
                <Text style={s.confirmBtnText}>保存</Text>
              </TouchableOpacity>
            </View>
          </View>
        </KeyboardAvoidingView>
      </Modal>

      {/* 详情/图片管理弹窗 */}
      <Modal visible={!!detailAsset} transparent animationType="slide" onRequestClose={() => setDetailAsset(null)}>
        {detailAsset && (
          <View style={s.detailOverlay}>
            <View style={[s.detailHeader, { paddingTop: insets.top + 8 }]}>
              <TouchableOpacity onPress={() => setDetailAsset(null)}>
                <Ionicons name="arrow-back" size={22} color={tokens.colors.ink} />
              </TouchableOpacity>
              <Text style={s.detailTitle} numberOfLines={1}>{detailAsset.name}</Text>
              <View style={s.typeBadge}><Text style={s.typeBadgeText}>{TYPE_LABEL[detailAsset.type]}</Text></View>
            </View>
            <ScrollView style={s.detailBody} contentContainerStyle={{ padding: 16 }}>
              {detailAsset.description ? (
                <Text style={s.detailDesc}>{detailAsset.description}</Text>
              ) : (
                <Text style={s.detailDescEmpty}>无文字约束</Text>
              )}
              <Text style={s.sectionTitle}>参考图片（{detailAsset.images.length}）</Text>
              <View style={s.imageGrid}>
                {detailAsset.images.map((img) => (
                  <View key={img.id} style={s.imageItem}>
                    <Image source={{ uri: assetUrl(img.url) }} style={s.imageItemImg} resizeMode="cover" />
                    <TouchableOpacity style={s.imageDeleteBtn} onPress={() => handleDeleteImage(detailAsset.id, img.id)}>
                      <Ionicons name="close-circle" size={20} color={tokens.colors.error} />
                    </TouchableOpacity>
                  </View>
                ))}
                <TouchableOpacity style={s.imageAddBtn} onPress={() => handleUploadImage(detailAsset.id)}>
                  <Ionicons name="add" size={28} color={tokens.colors.textCaption} />
                  <Text style={s.imageAddText}>添加图片</Text>
                </TouchableOpacity>
              </View>
            </ScrollView>
          </View>
        )}
      </Modal>
    </View>
  );
}

const s = StyleSheet.create({
  container: { flex: 1, backgroundColor: tokens.colors.canvas },
  header: { flexDirection: 'row', alignItems: 'center', borderBottomWidth: 1, borderBottomColor: tokens.colors.hairline, paddingHorizontal: 16, gap: 12 },
  headerTitle: { flex: 1, fontSize: tokens.fontSize.md, fontWeight: tokens.fontWeight.bold, color: tokens.colors.ink },
  addBtn: { flexDirection: 'row', alignItems: 'center', gap: 4, backgroundColor: tokens.colors.primary, paddingHorizontal: 14, paddingVertical: 8, borderRadius: tokens.radius.sm },
  addBtnText: { fontSize: tokens.fontSize.sm, color: tokens.colors.white, fontWeight: tokens.fontWeight.medium },
  filterRow: { flexDirection: 'row', paddingHorizontal: 16, paddingVertical: 10, gap: 8 },
  filterBtn: { paddingHorizontal: 14, paddingVertical: 6, borderRadius: tokens.radius.full, backgroundColor: tokens.colors.surface },
  filterBtnActive: { backgroundColor: tokens.colors.primary },
  filterText: { fontSize: tokens.fontSize.sm, color: tokens.colors.textSecondary, fontWeight: tokens.fontWeight.medium },
  filterTextActive: { color: tokens.colors.white },
  list: { padding: 12, gap: 10 },
  card: { flexDirection: 'row', backgroundColor: tokens.colors.white, borderRadius: tokens.radius.md, borderWidth: 1, borderColor: tokens.colors.hairline, padding: 10, gap: 12, alignItems: 'center' },
  cardThumb: { width: 56, height: 56, borderRadius: tokens.radius.sm, overflow: 'hidden', backgroundColor: tokens.colors.surface, alignItems: 'center', justifyContent: 'center' },
  cardThumbImg: { width: '100%', height: '100%' },
  cardInfo: { flex: 1, gap: 2 },
  cardTitleRow: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  cardTitle: { fontSize: tokens.fontSize.base, fontWeight: tokens.fontWeight.semibold, color: tokens.colors.ink, flex: 1 },
  typeBadge: { backgroundColor: tokens.colors.surface, paddingHorizontal: 6, paddingVertical: 2, borderRadius: tokens.radius.full },
  typeBadgeText: { fontSize: 10, color: tokens.colors.primary, fontWeight: tokens.fontWeight.medium },
  cardDesc: { fontSize: tokens.fontSize.sm, color: tokens.colors.textSecondary },
  imgCount: { fontSize: tokens.fontSize.xs, color: tokens.colors.textCaption },
  cardActions: { flexDirection: 'row', gap: 4 },
  iconBtn: { width: 32, height: 32, alignItems: 'center', justifyContent: 'center' },
  empty: { alignItems: 'center', paddingTop: 80, gap: 8 },
  emptyText: { fontSize: tokens.fontSize.base, color: tokens.colors.textSecondary },
  emptyHint: { fontSize: tokens.fontSize.sm, color: tokens.colors.textCaption, textAlign: 'center', paddingHorizontal: 40 },
  // Modal shared
  modalOverlay: { flex: 1, backgroundColor: 'rgba(0,0,0,0.4)', justifyContent: 'center', padding: 24 },
  modalPanel: { backgroundColor: tokens.colors.white, borderRadius: tokens.radius.lg, padding: 20 },
  modalTitle: { fontSize: tokens.fontSize.md, fontWeight: tokens.fontWeight.bold, color: tokens.colors.ink, marginBottom: 16 },
  label: { fontSize: tokens.fontSize.sm, color: tokens.colors.textSecondary, marginBottom: 6, marginTop: 12 },
  input: { borderWidth: 1, borderColor: tokens.colors.hairline, borderRadius: tokens.radius.sm, paddingHorizontal: 12, paddingVertical: 10, fontSize: tokens.fontSize.base, color: tokens.colors.ink, backgroundColor: tokens.colors.white },
  textArea: { minHeight: 72, textAlignVertical: 'top' },
  typeRow: { flexDirection: 'row', gap: 8 },
  typeBtn: { paddingHorizontal: 16, paddingVertical: 8, borderRadius: tokens.radius.sm, borderWidth: 1, borderColor: tokens.colors.hairline },
  typeBtnActive: { backgroundColor: tokens.colors.primary, borderColor: tokens.colors.primary },
  typeBtnText: { fontSize: tokens.fontSize.sm, color: tokens.colors.textSecondary },
  typeBtnTextActive: { color: tokens.colors.white },
  modalActions: { flexDirection: 'row', justifyContent: 'flex-end', gap: 10, marginTop: 20 },
  cancelBtn: { paddingHorizontal: 18, paddingVertical: 10, borderRadius: tokens.radius.sm, borderWidth: 1, borderColor: tokens.colors.hairline },
  cancelBtnText: { fontSize: tokens.fontSize.sm, color: tokens.colors.textSecondary },
  confirmBtn: { paddingHorizontal: 18, paddingVertical: 10, borderRadius: tokens.radius.sm, backgroundColor: tokens.colors.primary },
  confirmBtnDisabled: { opacity: 0.5 },
  confirmBtnText: { fontSize: tokens.fontSize.sm, color: tokens.colors.white, fontWeight: tokens.fontWeight.medium },
  // Detail modal
  detailOverlay: { flex: 1, backgroundColor: tokens.colors.canvas },
  detailHeader: { flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, paddingBottom: 12, gap: 10, borderBottomWidth: 1, borderBottomColor: tokens.colors.hairline },
  detailTitle: { flex: 1, fontSize: tokens.fontSize.md, fontWeight: tokens.fontWeight.bold, color: tokens.colors.ink },
  detailBody: { flex: 1 },
  detailDesc: { fontSize: tokens.fontSize.base, color: tokens.colors.ink, lineHeight: 22, marginBottom: 16 },
  detailDescEmpty: { fontSize: tokens.fontSize.base, color: tokens.colors.textCaption, fontStyle: 'italic', marginBottom: 16 },
  sectionTitle: { fontSize: tokens.fontSize.sm, fontWeight: tokens.fontWeight.semibold, color: tokens.colors.textSecondary, marginBottom: 12 },
  imageGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 10 },
  imageItem: { width: 100, height: 100, borderRadius: tokens.radius.sm, overflow: 'hidden', position: 'relative' },
  imageItemImg: { width: '100%', height: '100%' },
  imageDeleteBtn: { position: 'absolute', top: 4, right: 4 },
  imageAddBtn: { width: 100, height: 100, borderRadius: tokens.radius.sm, borderWidth: 1, borderColor: tokens.colors.hairline, borderStyle: 'dashed', alignItems: 'center', justifyContent: 'center', gap: 4 },
  imageAddText: { fontSize: tokens.fontSize.xs, color: tokens.colors.textCaption },
  // Create image grid
  createImageGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  createImageItem: { width: 64, height: 64, borderRadius: tokens.radius.sm, overflow: 'hidden', position: 'relative' },
  createImageImg: { width: '100%', height: '100%' },
  createImageRemove: { position: 'absolute', top: 2, right: 2 },
  createImageAdd: { width: 64, height: 64, borderRadius: tokens.radius.sm, borderWidth: 1, borderColor: tokens.colors.hairline, borderStyle: 'dashed', alignItems: 'center', justifyContent: 'center' },
});
