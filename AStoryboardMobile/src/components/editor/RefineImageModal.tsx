import { useState } from 'react';
import { Modal, View, Text, TextInput, TouchableOpacity, StyleSheet, Image, ActivityIndicator, KeyboardAvoidingView, Platform } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { tokens } from '../../tokens';
import { assetUrl } from '../../config';
import { useProjectStore } from '../../stores/projectStore';

/**
 * 完善图片弹窗（图改图）：显示当前生成图大图 + 完善诉求输入，
 * 提交后以当前图为源图走 mode=edit，generatedImageUrl=当前图。
 */
export function RefineImageModal({
  sceneId,
  imageUrl,
  onClose,
}: {
  sceneId: string;
  imageUrl: string;
  onClose: () => void;
}) {
  const generateImage = useProjectStore((s) => s.generateImage);
  const generatingImage = useProjectStore((s) => !!s.generatingImage[sceneId]);
  const [refinePrompt, setRefinePrompt] = useState('');
  const [error, setError] = useState('');

  const handleRefine = async () => {
    if (!refinePrompt.trim()) {
      setError('请填写完善诉求，例如「让光线更柔和，人物更清晰」');
      return;
    }
    setError('');
    try {
      // 图改图：当前图为源图，诉求文本作为 prompt（model 传空 → 走默认编辑模型）
      await generateImage(sceneId, refinePrompt.trim(), undefined, undefined, 'edit', imageUrl);
      onClose(); // 成功后关闭，预览刷新展示新图
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
        || (err instanceof Error ? err.message : '完善失败，请重试');
      setError(msg);
    }
  };

  return (
    <Modal visible transparent animationType="fade" onRequestClose={onClose}>
      <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={s.backdrop}>
        <TouchableOpacity style={s.backdrop} activeOpacity={1} onPress={onClose}>
          <View style={s.card} onStartShouldSetResponder={() => true}>
            <View style={s.header}>
              <Text style={s.title}>完善图片</Text>
              <TouchableOpacity onPress={onClose} style={s.closeBtn}>
                <Ionicons name="close" size={15} color={tokens.colors.textSecondary} />
              </TouchableOpacity>
            </View>

            <Image source={{ uri: assetUrl(imageUrl) }} style={s.image} resizeMode="contain" />

            <TextInput
              style={s.input}
              value={refinePrompt}
              onChangeText={setRefinePrompt}
              placeholder="描述你想如何完善这张图，例如：让光线更柔和，人物表情更自然，背景虚化…"
              placeholderTextColor={tokens.colors.textCaption}
              multiline
            />

            {error ? <Text style={s.error}>{error}</Text> : null}

            <View style={s.actions}>
              <TouchableOpacity style={s.cancelBtn} onPress={onClose}>
                <Text style={s.cancelText}>取消</Text>
              </TouchableOpacity>
              <TouchableOpacity style={s.confirmBtn} onPress={handleRefine} disabled={generatingImage}>
                {generatingImage
                  ? <ActivityIndicator size="small" color={tokens.colors.white} />
                  : <Text style={s.confirmText}>生成完善图</Text>}
              </TouchableOpacity>
            </View>
          </View>
        </TouchableOpacity>
      </KeyboardAvoidingView>
    </Modal>
  );
}

const s = StyleSheet.create({
  backdrop: { flex: 1, backgroundColor: 'rgba(20,20,19,0.78)', justifyContent: 'center', padding: 24 },
  card: {
    backgroundColor: tokens.colors.canvas,
    borderRadius: 16,
    padding: 20,
    width: '100%',
    maxHeight: '92%',
  },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 },
  title: { fontSize: tokens.fontSize.base, fontWeight: tokens.fontWeight.semibold, color: tokens.colors.ink },
  closeBtn: { width: 32, height: 32, borderRadius: 16, backgroundColor: tokens.colors.surface, alignItems: 'center', justifyContent: 'center' },
  closeText: { fontSize: 15, color: tokens.colors.textSecondary },
  image: {
    width: '100%',
    height: 260,
    borderRadius: 12,
    backgroundColor: tokens.colors.surface,
    marginBottom: 12,
  },
  input: {
    minHeight: 88,
    borderWidth: 1,
    borderColor: tokens.colors.hairline,
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: tokens.fontSize.base,
    color: tokens.colors.ink,
    backgroundColor: tokens.colors.white,
    textAlignVertical: 'top',
    marginBottom: 10,
  },
  error: { fontSize: tokens.fontSize.sm, color: tokens.colors.error, marginBottom: 10 },
  actions: { flexDirection: 'row', justifyContent: 'flex-end', gap: 8 },
  cancelBtn: {
    paddingHorizontal: 18, paddingVertical: 10, borderRadius: 8,
    borderWidth: 1, borderColor: tokens.colors.hairline, backgroundColor: tokens.colors.surface,
  },
  cancelText: { fontSize: tokens.fontSize.sm, color: tokens.colors.textSecondary },
  confirmBtn: {
    paddingHorizontal: 20, paddingVertical: 10, borderRadius: 8,
    backgroundColor: tokens.colors.primary, alignItems: 'center', justifyContent: 'center', minWidth: 96,
  },
  confirmText: { fontSize: tokens.fontSize.sm, color: tokens.colors.white, fontWeight: tokens.fontWeight.semibold },
});
