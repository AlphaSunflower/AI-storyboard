import { View, Text, TextInput, TouchableOpacity, FlatList, StyleSheet, KeyboardAvoidingView, Platform, ActivityIndicator, Alert, Image } from 'react-native';
import { useState, useRef, useEffect } from 'react';
import * as ImagePicker from 'expo-image-picker';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Ionicons } from '@expo/vector-icons';
import { tokens } from '../../src/tokens';
import { useAgentStore, type HumanInputInfo, type ConfirmResultInfo, type VideoPlanInfo } from '../../src/stores/agentStore';
import { ParamSelector } from '../../src/components/agent/ParamSelector';
import { useProjectStore } from '../../src/stores/projectStore';
import { SidebarDrawer } from '../../src/components/agent/SidebarDrawer';
import { assetUrl } from '../../src/config';
import type { AgentMessage } from '../../src/api/agent';

export default function ChatTab() {
  const {
    messages, streaming, waitingHumanInput, waitingVideoPlan, confirmResult,
    streamError, workflowHint, sendMessage, submitHumanInput, submitVideoPlan,
    skipCurrentHITL, dismissConfirm, activeConversationId, conversations,
    newConversation, selectConversation, loadConversations, refImageUrl, setRefImageUrl,
  } = useAgentStore();
  const currentProject = useProjectStore((s) => s.currentProject);
  const [input, setInput] = useState('');
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const flatListRef = useRef<FlatList>(null);

  useEffect(() => {
    if (currentProject) loadConversations();
  }, [currentProject?.id]);

  useEffect(() => {
    if (messages.length > 0) {
      setTimeout(() => flatListRef.current?.scrollToEnd({ animated: true }), 100);
    }
  }, [messages.length]);

  const handleSend = async () => {
    const text = input.trim();
    if (!text || streaming) return;
    setInput('');
    await sendMessage(text);
  };

  const handlePickImage = async () => {
    const result = await ImagePicker.launchImageLibraryAsync({
      mediaTypes: ['images'],
      quality: 0.8,
      allowsEditing: false,
    });
    if (!result.canceled && result.assets[0]) {
      setRefImageUrl(result.assets[0].uri);
    }
  };

  const insets = useSafeAreaInsets();
  const currentTitle = conversations.find((c) => c.id === activeConversationId)?.title ?? '新对话';

  const renderMessage = ({ item }: { item: AgentMessage }) => {
    const isUser = item.role === 'user';
    const isImage = item.content && (
      item.content.includes('/api/files/images/') ||
      item.content.startsWith('data:image') ||
      /\.(png|jpg|jpeg|webp)(\?|$)/i.test(item.content)
    );
    return (
      <View style={[s.msgBubble, isUser ? s.userBubble : s.assistantBubble]}>
        {isImage ? (
          <Image source={{ uri: assetUrl(item.content) }} style={s.msgImage} resizeMode="cover" />
        ) : (
          <Text style={[s.msgText, isUser && s.userText]}>{item.content}</Text>
        )}
      </View>
    );
  };

  return (
    <View style={s.container}>
      {/* Header */}
      <View style={[s.header, { height: 56 + insets.top, paddingTop: insets.top }]}>
        <TouchableOpacity style={s.menuBtn} onPress={() => setSidebarOpen(true)}>
          <View style={s.menuIcon}>
            <View style={s.menuLine} />
            <View style={s.menuLine} />
            <View style={[s.menuLine, { width: 12 }]} />
          </View>
        </TouchableOpacity>
        <TouchableOpacity style={s.headerTitleWrap} activeOpacity={0.7} onPress={() => setSidebarOpen(true)}>
          <Text style={s.headerTitle} numberOfLines={1}>{currentTitle}</Text>
        </TouchableOpacity>
        <TouchableOpacity style={s.newBtn} onPress={newConversation}>
          <Text style={s.newBtnText}>＋</Text>
        </TouchableOpacity>
      </View>

      {/* Sidebar Drawer */}
      <SidebarDrawer
        visible={sidebarOpen}
        onClose={() => setSidebarOpen(false)}
        onSelectConversation={(id) => selectConversation(id)}
        conversations={conversations}
        activeConversationId={activeConversationId}
        onNewConversation={newConversation}
      />

      {/* Messages */}
      <KeyboardAvoidingView
        style={s.body}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
        keyboardVerticalOffset={56}
      >
        <FlatList
          ref={flatListRef}
          data={messages}
          renderItem={renderMessage}
          keyExtractor={(item) => item.id}
          contentContainerStyle={s.msgList}
          ListEmptyComponent={
            !currentProject ? (
              <View style={s.empty}>
                <Text style={s.emptyIcon}>☾</Text>
                <Text style={s.emptyText}>请先选择项目</Text>
                <Text style={s.emptyHint}>点击左上角菜单选择或创建项目</Text>
              </View>
            ) : (
              <View style={s.empty}>
                <Text style={s.emptyIcon}>☾</Text>
                <Text style={s.emptyText}>开始和 Moon 智能体对话</Text>
                <Text style={s.emptyHint}>输入你的创作需求，Moon 会帮你生成分镜、图片或视频</Text>
              </View>
            )
          }
          ListFooterComponent={
            <>
              {workflowHint ? (
                <View style={s.hintRow}>
                  <ActivityIndicator size="small" color={tokens.colors.primary} />
                  <Text style={s.hintText}>{workflowHint}</Text>
                </View>
              ) : null}
              {streamError ? (
                <View style={s.errorBubble}>
                  <Text style={s.errorText}>{streamError}</Text>
                </View>
              ) : null}
              {waitingHumanInput && (
                <HITLCard info={waitingHumanInput} onSubmit={submitHumanInput} onSkip={skipCurrentHITL} />
              )}
              {waitingVideoPlan && (
                <VideoPlanCard info={waitingVideoPlan} onSubmit={submitVideoPlan} onSkip={skipCurrentHITL} />
              )}
              {confirmResult && (
                <ConfirmCard info={confirmResult} onDismiss={dismissConfirm} />
              )}
            </>
          }
        />

        {/* Composer */}
        <View style={s.composer}>
          {/* 参考图预览 */}
          {refImageUrl && (
            <View style={s.refPreview}>
              <Image source={{ uri: refImageUrl }} style={s.refThumb} resizeMode="cover" />
              <TouchableOpacity style={s.refRemove} onPress={() => setRefImageUrl(null)}>
                <Text style={s.refRemoveText}><Ionicons name="close" size={12} color={tokens.colors.textSecondary} /></Text>
              </TouchableOpacity>
              <Text style={s.refHint}>已选参考图</Text>
            </View>
          )}

          <View style={s.composerRow}>
            <TouchableOpacity style={s.attachBtn} onPress={handlePickImage}>
              <Text style={s.attachIcon}>⊕</Text>
            </TouchableOpacity>
            <TextInput
              style={s.input}
              value={input}
              onChangeText={setInput}
              placeholder={currentProject ? '输入消息...' : '请先在左上角选择项目'}
              placeholderTextColor={tokens.colors.textCaption}
              multiline
              maxLength={2000}
              editable={!streaming && !!currentProject}
            />
            <TouchableOpacity
              style={[s.sendBtn, (!input.trim() || streaming) && s.sendBtnDisabled]}
              onPress={handleSend}
              disabled={!input.trim() || streaming}
            >
              {streaming ? (
                <ActivityIndicator size="small" color={tokens.colors.white} />
              ) : (
                <Text style={s.sendBtnText}>发送</Text>
              )}
            </TouchableOpacity>
          </View>
        </View>
      </KeyboardAvoidingView>
    </View>
  );
}

function HITLCard({ info, onSubmit, onSkip }: { info: HumanInputInfo; onSubmit: (actionId: string, customText?: string, params?: Record<string, string>, assetIds?: string[]) => void; onSkip: () => void }) {
  const [customOpen, setCustomOpen] = useState(false);
  const [customText, setCustomText] = useState('');
  const [selectedParams, setSelectedParams] = useState<Record<string, string>>({});
  const [selectedAssets, setSelectedAssets] = useState<Set<string>>(new Set());
  const expired = info.expirationTime > 0 && Date.now() / 1000 > info.expirationTime;
  const hasSplitSelectors = !!((info.imageModels && info.imageModels.length > 0) || (info.videoModels && info.videoModels.length > 0));
  const hasAssets = !!(info.assets && info.assets.length > 0);
  const mergeParams = (p: Record<string, string>) => setSelectedParams((prev) => ({ ...prev, ...p }));
  const toggleAsset = (id: string) => setSelectedAssets((prev) => { const n = new Set(prev); if (n.has(id)) n.delete(id); else n.add(id); return n; });

  const handleAction = (a: { id: string; title: string }) => {
    if (a.id === 'custom') { setCustomOpen(true); return; }
    if (hasAssets) onSubmit(a.id, undefined, selectedParams, a.id === 'asset-skip' ? [] : Array.from(selectedAssets));
    else onSubmit(a.id, undefined, selectedParams);
  };

  return (
    <View style={s.card}>
      <Text style={s.cardTitle}>需要确认</Text>
      <Text style={s.cardBody}>{info.formContent || '请确认是否继续？'}</Text>

      {hasAssets && (
        <View style={s.assetBox}>
          <TouchableOpacity
            style={s.assetAllRow}
            onPress={() => setSelectedAssets(selectedAssets.size === info.assets!.length ? new Set() : new Set(info.assets!.map((a) => a.id)))}
          >
            <Text style={s.assetAllText}>{selectedAssets.size === info.assets!.length ? <Ionicons name="checkbox" size={16} color={tokens.colors.primary} /> : <Ionicons name="square-outline" size={16} color={tokens.colors.textSecondary} />} 全选</Text>
          </TouchableOpacity>
          {info.assets!.map((a) => (
            <TouchableOpacity key={a.id} style={s.assetRow} onPress={() => toggleAsset(a.id)}>
              <Text style={s.assetCheck}>{selectedAssets.has(a.id) ? <Ionicons name="checkbox" size={16} color={tokens.colors.primary} /> : <Ionicons name="square-outline" size={16} color={tokens.colors.textSecondary} />}</Text>
              {a.image && <Image source={{ uri: assetUrl(a.image) }} style={s.assetThumb} resizeMode="cover" />}
              <Text style={s.assetName} numberOfLines={1}>{a.name}</Text>
              <Text style={s.assetType}>{a.type}</Text>
            </TouchableOpacity>
          ))}
        </View>
      )}

      {expired ? (
        <Text style={s.cardMeta}>确认已过期，请重新发起对话</Text>
      ) : customOpen ? (
        <View>
          <TextInput
            style={s.customInput}
            value={customText}
            onChangeText={setCustomText}
            placeholder="输入你的想法…"
            placeholderTextColor={tokens.colors.textCaption}
            autoFocus
            multiline
          />
          <View style={s.cardActions}>
            <TouchableOpacity style={[s.cardBtn, (!customText.trim()) && s.cardBtnDisabled]} onPress={() => onSubmit('custom', customText.trim(), selectedParams, Array.from(selectedAssets))}>
              <Text style={s.cardBtnText}>确认输入</Text>
            </TouchableOpacity>
            <TouchableOpacity style={s.cardSkipBtn} onPress={() => { setCustomOpen(false); setCustomText(''); }}>
              <Text style={s.cardSkipText}>取消</Text>
            </TouchableOpacity>
          </View>
        </View>
      ) : (
        <>
          {hasSplitSelectors ? (
            <View>
              {info.imageModels && info.imageModels.length > 0 && (
                <ParamSelector keyPrefix="image" models={info.imageModels} recommended={info.recommended} reasons={info.reasons} onParamsChange={mergeParams} label="图片生成参数" />
              )}
              {info.videoModels && info.videoModels.length > 0 && (
                <ParamSelector keyPrefix="video" models={info.videoModels} recommended={info.recommended} reasons={info.reasons} onParamsChange={mergeParams} label="视频生成参数" />
              )}
            </View>
          ) : info.models && info.models.length > 0 ? (
            <ParamSelector models={info.models} recommended={info.recommended} reasons={info.reasons} onParamsChange={setSelectedParams} />
          ) : null}

          <View style={s.cardActions}>
            {info.actions.map((a) => (
              <TouchableOpacity key={a.id} style={s.cardBtn} onPress={() => handleAction(a)}>
                <Text style={s.cardBtnText}>{a.title}</Text>
              </TouchableOpacity>
            ))}
          </View>
          <TouchableOpacity style={s.cardSkipBtn} onPress={onSkip}>
            <Text style={s.cardSkipText}>跳过，换个话题</Text>
          </TouchableOpacity>
        </>
      )}
    </View>
  );
}

function VideoPlanCard({ info, onSubmit, onSkip }: { info: VideoPlanInfo; onSubmit: (actionId: string, params?: Record<string, string>, customText?: string) => void; onSkip: () => void }) {
  const [selectedParams, setSelectedParams] = useState<Record<string, string>>({});
  const [customOpen, setCustomOpen] = useState(false);
  const [customText, setCustomText] = useState('');

  const handleAction = (a: { id: string; title: string }) => {
    if (a.id === 'custom') { setCustomOpen(true); return; }
    onSubmit(a.id, selectedParams);
  };

  return (
    <View style={s.card}>
      <Text style={s.cardTitle}>视频方案</Text>
      <Text style={s.cardBody}>{info.message}</Text>
      <Text style={s.cardMeta}>时长: {info.duration}秒</Text>

      {info.models && info.models.length > 0 && !customOpen && (
        <ParamSelector models={info.models} recommended={info.recommended} reasons={info.reasons} onParamsChange={setSelectedParams} />
      )}

      {customOpen ? (
        <View>
          <TextInput
            style={s.customInput}
            value={customText}
            onChangeText={setCustomText}
            placeholder="输入你的想法…"
            placeholderTextColor={tokens.colors.textCaption}
            autoFocus
            multiline
          />
          <View style={s.cardActions}>
            <TouchableOpacity style={[s.cardBtn, (!customText.trim()) && s.cardBtnDisabled]} onPress={() => onSubmit('custom', selectedParams, customText.trim())}>
              <Text style={s.cardBtnText}>确认输入</Text>
            </TouchableOpacity>
            <TouchableOpacity style={s.cardSkipBtn} onPress={() => { setCustomOpen(false); setCustomText(''); }}>
              <Text style={s.cardSkipText}>取消</Text>
            </TouchableOpacity>
          </View>
        </View>
      ) : (
        <>
          <View style={s.cardActions}>
            {info.actions.map((a) => (
              <TouchableOpacity key={a.id} style={s.cardBtn} onPress={() => handleAction(a)}>
                <Text style={s.cardBtnText}>{a.title}</Text>
              </TouchableOpacity>
            ))}
          </View>
          <TouchableOpacity style={s.cardSkipBtn} onPress={onSkip}>
            <Text style={s.cardSkipText}>跳过</Text>
          </TouchableOpacity>
        </>
      )}
    </View>
  );
}

function ConfirmCard({ info, onDismiss }: { info: ConfirmResultInfo; onDismiss: () => void }) {
  return (
    <View style={s.card}>
      <Text style={s.cardTitle}>
        {info.kind === 'image' ? '图片生成完成' : info.kind === 'video' ? '视频生成完成' : '分镜生成完成'}
      </Text>
      {info.sceneCount !== undefined && (
        <Text style={s.cardMeta}>共 {info.sceneCount} 个分镜</Text>
      )}
      <View style={s.cardActions}>
        {info.actions.map((a) => (
          <TouchableOpacity key={a.id} style={s.cardBtn} onPress={onDismiss}>
            <Text style={s.cardBtnText}>{a.title}</Text>
          </TouchableOpacity>
        ))}
      </View>
    </View>
  );
}

const s = StyleSheet.create({
  container: { flex: 1, backgroundColor: tokens.colors.canvas },
  header: {
    height: 56,
    flexDirection: 'row',
    alignItems: 'center',
    borderBottomWidth: 1,
    borderBottomColor: tokens.colors.hairline,
    paddingHorizontal: 12,
  },
  menuBtn: {
    width: 40,
    height: 40,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 10,
  },
  menuIcon: {
    width: 20,
    height: 16,
    justifyContent: 'space-between',
  },
  menuLine: {
    height: 2,
    backgroundColor: tokens.colors.ink,
    borderRadius: 1,
    width: 20,
  },
  headerTitleWrap: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    flexDirection: 'row',
    marginHorizontal: 8,
  },
  headerTitle: {
    fontSize: tokens.fontSize.base,
    fontWeight: tokens.fontWeight.semibold,
    color: tokens.colors.ink,
    textAlign: 'center',
    flexShrink: 1,
  },
  newBtn: {
    width: 40,
    height: 40,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 10,
  },
  newBtnText: {
    fontSize: 22,
    color: tokens.colors.primary,
    fontWeight: tokens.fontWeight.bold,
  },
  body: {
    flex: 1,
  },
  msgList: {
    padding: 16,
    paddingBottom: 8,
  },
  msgBubble: {
    maxWidth: '80%',
    paddingHorizontal: 14,
    paddingVertical: 10,
    borderRadius: tokens.radius.md,
    marginBottom: 10,
  },
  userBubble: {
    alignSelf: 'flex-end',
    backgroundColor: tokens.colors.primary,
  },
  assistantBubble: {
    alignSelf: 'flex-start',
    backgroundColor: tokens.colors.surface,
  },
  msgText: {
    fontSize: tokens.fontSize.base,
    color: tokens.colors.ink,
    lineHeight: 22,
  },
  userText: {
    color: tokens.colors.white,
  },
  msgImage: {
    width: 200,
    height: 150,
    borderRadius: tokens.radius.sm,
  },
  empty: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingTop: 120,
  },
  emptyIcon: {
    fontSize: 48,
    color: tokens.colors.primary,
    marginBottom: 16,
  },
  emptyText: {
    fontSize: tokens.fontSize.lg,
    fontWeight: tokens.fontWeight.semibold,
    color: tokens.colors.ink,
    marginBottom: 8,
  },
  emptyHint: {
    fontSize: tokens.fontSize.sm,
    color: tokens.colors.textCaption,
    textAlign: 'center',
    paddingHorizontal: 40,
  },
  hintRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    paddingHorizontal: 16,
    paddingVertical: 8,
  },
  hintText: {
    fontSize: tokens.fontSize.sm,
    color: tokens.colors.textSecondary,
  },
  errorBubble: {
    backgroundColor: '#fef2f2',
    borderRadius: tokens.radius.sm,
    padding: 12,
    marginBottom: 10,
  },
  errorText: {
    fontSize: tokens.fontSize.sm,
    color: tokens.colors.error,
  },
  card: {
    backgroundColor: tokens.colors.white,
    borderRadius: tokens.radius.md,
    borderWidth: 1,
    borderColor: tokens.colors.hairline,
    padding: 16,
    marginBottom: 12,
  },
  cardTitle: {
    fontSize: tokens.fontSize.base,
    fontWeight: tokens.fontWeight.semibold,
    color: tokens.colors.ink,
    marginBottom: 8,
  },
  cardBody: {
    fontSize: tokens.fontSize.sm,
    color: tokens.colors.textSecondary,
    lineHeight: 20,
    marginBottom: 8,
  },
  cardMeta: {
    fontSize: tokens.fontSize.xs,
    color: tokens.colors.textCaption,
    marginBottom: 12,
  },
  cardActions: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  cardBtn: {
    paddingHorizontal: 16,
    paddingVertical: 8,
    backgroundColor: tokens.colors.primary,
    borderRadius: tokens.radius.sm,
  },
  cardBtnText: {
    fontSize: tokens.fontSize.sm,
    fontWeight: tokens.fontWeight.medium,
    color: tokens.colors.white,
  },
  cardSkipBtn: {
    paddingHorizontal: 16,
    paddingVertical: 8,
    backgroundColor: tokens.colors.surface,
    borderRadius: tokens.radius.sm,
  },
  cardSkipText: {
    fontSize: tokens.fontSize.sm,
    color: tokens.colors.textSecondary,
  },
  cardBtnDisabled: { opacity: 0.5 },
  customInput: {
    minHeight: 72,
    borderWidth: 1,
    borderColor: tokens.colors.hairline,
    borderRadius: tokens.radius.sm,
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: tokens.fontSize.base,
    color: tokens.colors.ink,
    backgroundColor: tokens.colors.white,
    textAlignVertical: 'top',
    marginBottom: 10,
  },
  assetBox: {
    borderWidth: 1,
    borderColor: tokens.colors.hairline,
    borderRadius: tokens.radius.md,
    padding: 8,
    marginBottom: 10,
    maxHeight: 240,
  },
  assetAllRow: { paddingVertical: 6, borderBottomWidth: 1, borderBottomColor: tokens.colors.hairline, marginBottom: 4 },
  assetAllText: { fontSize: tokens.fontSize.sm, fontWeight: tokens.fontWeight.medium, color: tokens.colors.ink },
  assetRow: { flexDirection: 'row', alignItems: 'center', gap: 8, paddingVertical: 6 },
  assetCheck: { fontSize: tokens.fontSize.base, color: tokens.colors.ink },
  assetThumb: { width: 28, height: 28, borderRadius: 6, backgroundColor: tokens.colors.surface },
  assetName: { flex: 1, fontSize: tokens.fontSize.sm, color: tokens.colors.ink },
  assetType: { fontSize: 11, color: tokens.colors.textSecondary },
  composer: {
    borderTopWidth: 1,
    borderTopColor: tokens.colors.hairline,
    backgroundColor: tokens.colors.canvas,
    paddingHorizontal: 12,
    paddingVertical: 8,
  },
  refPreview: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    marginBottom: 8,
    paddingHorizontal: 4,
  },
  refThumb: {
    width: 40,
    height: 40,
    borderRadius: tokens.radius.sm,
  },
  refRemove: {
    width: 20,
    height: 20,
    borderRadius: 10,
    backgroundColor: tokens.colors.surface,
    alignItems: 'center',
    justifyContent: 'center',
  },
  refRemoveText: {
    fontSize: 10,
    color: tokens.colors.textSecondary,
  },
  refHint: {
    fontSize: tokens.fontSize.xs,
    color: tokens.colors.textCaption,
  },
  composerRow: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    gap: 8,
  },
  attachBtn: {
    width: 40,
    height: 40,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: tokens.radius.sm,
    backgroundColor: tokens.colors.surface,
  },
  attachIcon: {
    fontSize: 20,
    color: tokens.colors.textSecondary,
  },
  input: {
    flex: 1,
    minHeight: 40,
    maxHeight: 120,
    borderWidth: 1,
    borderColor: tokens.colors.hairline,
    borderRadius: tokens.radius.sm,
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: tokens.fontSize.base,
    color: tokens.colors.ink,
    backgroundColor: tokens.colors.white,
  },
  sendBtn: {
    height: 40,
    paddingHorizontal: 16,
    backgroundColor: tokens.colors.primary,
    borderRadius: tokens.radius.sm,
    alignItems: 'center',
    justifyContent: 'center',
  },
  sendBtnDisabled: {
    opacity: 0.5,
  },
  sendBtnText: {
    fontSize: tokens.fontSize.sm,
    fontWeight: tokens.fontWeight.semibold,
    color: tokens.colors.white,
  },
});
