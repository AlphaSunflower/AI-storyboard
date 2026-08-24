import { useState } from 'react';
import { Modal, View, Text, TextInput, TouchableOpacity, StyleSheet, Image, ActivityIndicator, ScrollView } from 'react-native';
import * as ImagePicker from 'expo-image-picker';
import { Ionicons } from '@expo/vector-icons';
import { tokens } from '../../tokens';
import { useProjectStore } from '../../stores/projectStore';
import { useAgentStore } from '../../stores/agentStore';

/**
 * 右侧剧本输入抽屉：理解模型选择 + 参考图上传 + 剧本输入 + 生成分镜脚本。
 * 项目已有分镜时先弹 覆盖更新/追加/不生成 确认（mode 参数透传给 generateScript）。
 */
export function ScriptInputDrawer({ visible, onClose }: { visible: boolean; onClose: () => void }) {
  const {
    currentProject, scenes, generateScript, createProject,
    understandingModel, understandingModelOptions, setUnderstandingModel,
  } = useProjectStore();
  const agentGeneratedScenes = useAgentStore((s) => s.agentGeneratedScenes);

  const [scriptText, setScriptText] = useState('');
  const [generating, setGenerating] = useState(false);
  const [refImages, setRefImages] = useState<string[]>([]);
  const [confirmOverwrite, setConfirmOverwrite] = useState(false);
  const [pickOpen, setPickOpen] = useState(false);

  const handleRefImages = async () => {
    const result = await ImagePicker.launchImageLibraryAsync({ mediaTypes: ['images'], quality: 0.8, allowsEditing: false });
    if (!result.canceled && result.assets[0] && refImages.length < 3) {
      setRefImages((p) => [...p, result.assets![0].uri]);
    }
  };

  const handleGenerate = async () => {
    if (!scriptText.trim() || generating) return;
    if ((scenes?.length ?? 0) > 0) { setConfirmOverwrite(true); return; }
    await doGenerate();
  };

  const doGenerate = async (mode?: string) => {
    if (!scriptText.trim() || generating) return;
    setGenerating(true);
    try {
      let projectId = currentProject?.id;
      if (!projectId) {
        const p = await createProject('未命名项目', 'movie', '16:9');
        projectId = p.id;
      }
      await generateScript(
        projectId, scriptText, 'movie', '16:9', undefined,
        refImages.length ? understandingModel : undefined,
        refImages.length ? refImages : undefined,
        undefined, mode,
      );
      onClose();
    } catch { /* store 已处理 */ } finally {
      setGenerating(false);
    }
  };

  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={onClose}>
      <TouchableOpacity style={s.backdrop} activeOpacity={1} onPress={onClose}>
        <View style={s.drawer} onStartShouldSetResponder={() => true}>
          <View style={s.header}>
            <Text style={s.title}>剧本输入</Text>
            <TouchableOpacity onPress={onClose} style={s.closeBtn}>
              <Ionicons name="close" size={18} color={tokens.colors.textSecondary} />
            </TouchableOpacity>
          </View>

          <ScrollView style={s.body} contentContainerStyle={s.bodyContent}>
            {/* 理解模型 */}
            <View>
              <Text style={s.label}>理解模型</Text>
              <TouchableOpacity style={s.pickerBtn} onPress={() => setPickOpen(true)}>
                <Text style={s.pickerBtnText}>{understandingModelOptions.find((m) => m.value === understandingModel)?.label ?? understandingModel}</Text>
                <Text style={s.pickerArrow}>▾</Text>
              </TouchableOpacity>
            </View>

            {/* 参考图上传 */}
            <View>
              <Text style={s.label}>参考图（可选，最多 3 张）</Text>
              <TouchableOpacity style={s.uploadBtn} onPress={handleRefImages}>
                <Ionicons name="add-circle-outline" size={16} color={tokens.colors.textSecondary} />
              <Text style={s.uploadText}>上传参考图</Text>
              </TouchableOpacity>
              {refImages.length > 0 && (
                <View style={s.refRow}>
                  {refImages.map((uri, i) => (
                    <View key={i} style={s.refItem}>
                      <Image source={{ uri }} style={s.refImg} resizeMode="contain" />
                      <TouchableOpacity style={s.refRemove} onPress={() => setRefImages((p) => p.filter((_, j) => j !== i))}>
                        <Ionicons name="close" size={10} color={tokens.colors.white} />
                      </TouchableOpacity>
                    </View>
                  ))}
                </View>
              )}
            </View>

            {/* 剧本输入 */}
            <TextInput
              style={[s.scriptInput, agentGeneratedScenes && s.scriptInputDisabled]}
              value={scriptText}
              onChangeText={setScriptText}
              placeholder="输入剧本内容，AI 将自动生成分镜脚本..."
              placeholderTextColor={tokens.colors.textCaption}
              multiline
              editable={!agentGeneratedScenes}
            />
            {agentGeneratedScenes && (
              <Text style={s.hint}>智能体已生成分镜，手动输入暂时禁用</Text>
            )}
          </ScrollView>

          <View style={s.footer}>
            <TouchableOpacity
              style={[s.generateBtn, (!scriptText.trim() || generating || agentGeneratedScenes) && s.generateBtnDisabled]}
              onPress={handleGenerate}
              disabled={!scriptText.trim() || generating || agentGeneratedScenes}
            >
              {generating
                ? <ActivityIndicator size="small" color={tokens.colors.white} />
                : <Text style={s.generateText}>{generating ? '生成中...' : '生成分镜脚本'}</Text>}
            </TouchableOpacity>
          </View>

          {/* 覆盖/追加确认 */}
          {confirmOverwrite && (
            <View style={s.confirmWrap}>
              <View style={s.confirmCard}>
                <Text style={s.confirmTitle}>项目已有 {scenes.length} 个分镜</Text>
                <Text style={s.confirmBody}>选择如何处理本次生成：</Text>
                <TouchableOpacity style={s.confirmPrimary} onPress={() => { setConfirmOverwrite(false); void doGenerate('overwrite'); }}>
                  <Text style={s.confirmPrimaryText}>覆盖更新（清空现有分镜）</Text>
                </TouchableOpacity>
                <TouchableOpacity style={s.confirmSecondary} onPress={() => { setConfirmOverwrite(false); void doGenerate('append'); }}>
                  <Text style={s.confirmSecondaryText}>追加到现有分镜</Text>
                </TouchableOpacity>
                <TouchableOpacity style={s.confirmSecondary} onPress={() => setConfirmOverwrite(false)}>
                  <Text style={s.confirmSecondaryText}>不生成（取消）</Text>
                </TouchableOpacity>
              </View>
            </View>
          )}
        </View>
      </TouchableOpacity>

      {/* 模型选择弹窗 */}
      {pickOpen && (
        <Modal transparent animationType="fade" onRequestClose={() => setPickOpen(false)}>
          <TouchableOpacity style={s.pickBackdrop} activeOpacity={1} onPress={() => setPickOpen(false)}>
            <View style={s.pickCard} onStartShouldSetResponder={() => true}>
              <Text style={s.pickTitle}>选择理解模型</Text>
              {understandingModelOptions.map((m) => (
                <TouchableOpacity
                  key={m.value}
                  style={[s.pickItem, understandingModel === m.value && s.pickItemActive]}
                  onPress={() => { setUnderstandingModel(m.value); setPickOpen(false); }}
                >
                  <Text style={[s.pickItemText, understandingModel === m.value && s.pickItemTextActive]}>{m.label}</Text>
                </TouchableOpacity>
              ))}
              <TouchableOpacity style={s.pickCancel} onPress={() => setPickOpen(false)}>
                <Text style={s.pickCancelText}>取消</Text>
              </TouchableOpacity>
            </View>
          </TouchableOpacity>
        </Modal>
      )}
    </Modal>
  );
}

const s = StyleSheet.create({
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.35)' },
  drawer: {
    position: 'absolute', top: 0, right: 0, bottom: 0, width: '86%', maxWidth: 400,
    backgroundColor: tokens.colors.canvas, flexDirection: 'column',
    shadowColor: '#000', shadowOffset: { width: -4, height: 0 }, shadowOpacity: 0.12, shadowRadius: 24, elevation: 16,
  },
  header: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    paddingHorizontal: 16, paddingVertical: 14, borderBottomWidth: 1, borderBottomColor: tokens.colors.hairline,
  },
  title: { fontSize: tokens.fontSize.md, fontWeight: tokens.fontWeight.bold, color: tokens.colors.ink },
  closeBtn: { width: 32, height: 32, alignItems: 'center', justifyContent: 'center', borderRadius: 8 },
  closeText: { fontSize: 18, color: tokens.colors.textSecondary },
  body: { flex: 1 },
  bodyContent: { padding: 16, gap: 14 },
  label: { fontSize: tokens.fontSize.sm, color: tokens.colors.textSecondary, marginBottom: 6 },
  pickerBtn: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    height: 44, borderWidth: 1, borderColor: tokens.colors.hairline,
    borderRadius: 8, paddingHorizontal: 12, backgroundColor: tokens.colors.white,
  },
  pickerBtnText: { fontSize: tokens.fontSize.base, color: tokens.colors.ink, flex: 1 },
  pickerArrow: { fontSize: 12, color: tokens.colors.textCaption },
  uploadBtn: {
    height: 44, borderWidth: 1, borderColor: tokens.colors.hairline, borderStyle: 'dashed',
    borderRadius: 10, backgroundColor: tokens.colors.canvas, alignItems: 'center', justifyContent: 'center',
  },
  uploadText: { fontSize: tokens.fontSize.sm, color: tokens.colors.textSecondary },
  refRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 8 },
  refItem: { position: 'relative' },
  refImg: { width: 56, height: 56, borderRadius: 6, backgroundColor: tokens.colors.surface },
  refRemove: {
    position: 'absolute', top: -6, right: -6, width: 18, height: 18, borderRadius: 9,
    backgroundColor: tokens.colors.error, alignItems: 'center', justifyContent: 'center',
  },
  refRemoveText: { fontSize: 10, color: tokens.colors.white },
  scriptInput: {
    minHeight: 140, borderWidth: 1, borderColor: tokens.colors.hairline,
    borderRadius: 12, padding: 12, fontSize: tokens.fontSize.base, lineHeight: 22,
    color: tokens.colors.ink, backgroundColor: tokens.colors.white, textAlignVertical: 'top',
  },
  scriptInputDisabled: { backgroundColor: '#f5f5f5' },
  hint: { fontSize: tokens.fontSize.sm, color: tokens.colors.textCaption },
  footer: { padding: 12, borderTopWidth: 1, borderTopColor: tokens.colors.hairline },
  generateBtn: {
    height: 46, borderRadius: 12, backgroundColor: tokens.colors.primary,
    alignItems: 'center', justifyContent: 'center',
  },
  generateBtnDisabled: { opacity: 0.45 },
  generateText: { fontSize: tokens.fontSize.base, color: tokens.colors.white, fontWeight: tokens.fontWeight.semibold },
  confirmWrap: { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: 'rgba(20,20,19,0.35)', alignItems: 'center', justifyContent: 'center', padding: 24 },
  confirmCard: { width: '100%', maxWidth: 380, backgroundColor: tokens.colors.white, borderRadius: 10, padding: 24 },
  confirmTitle: { fontSize: tokens.fontSize.base, fontWeight: tokens.fontWeight.semibold, color: tokens.colors.ink, marginBottom: 8 },
  confirmBody: { fontSize: tokens.fontSize.sm, color: tokens.colors.textSecondary, marginBottom: 16 },
  confirmPrimary: { height: 44, borderRadius: 10, backgroundColor: tokens.colors.primary, alignItems: 'center', justifyContent: 'center', marginBottom: 8 },
  confirmPrimaryText: { fontSize: tokens.fontSize.base, color: tokens.colors.white, fontWeight: tokens.fontWeight.medium },
  confirmSecondary: { height: 44, borderRadius: 10, borderWidth: 1, borderColor: tokens.colors.hairline, alignItems: 'center', justifyContent: 'center', marginBottom: 8 },
  confirmSecondaryText: { fontSize: tokens.fontSize.base, color: tokens.colors.ink },
  pickBackdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.35)', justifyContent: 'center', padding: 32 },
  pickCard: { backgroundColor: tokens.colors.white, borderRadius: 12, padding: 16 },
  pickTitle: { fontSize: tokens.fontSize.base, fontWeight: tokens.fontWeight.semibold, color: tokens.colors.ink, marginBottom: 12 },
  pickItem: { height: 44, justifyContent: 'center', paddingHorizontal: 12, borderRadius: 8, marginBottom: 4 },
  pickItemActive: { backgroundColor: tokens.colors.surface },
  pickItemText: { fontSize: tokens.fontSize.base, color: tokens.colors.textSecondary },
  pickItemTextActive: { color: tokens.colors.primary, fontWeight: tokens.fontWeight.semibold },
  pickCancel: { height: 44, alignItems: 'center', justifyContent: 'center', marginTop: 8 },
  pickCancelText: { fontSize: tokens.fontSize.base, color: tokens.colors.textSecondary },
});
