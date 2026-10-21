import { View, Text, TextInput, FlatList, TouchableOpacity, StyleSheet, Image, ActivityIndicator, Modal, Alert } from 'react-native';
import { useState, useEffect } from 'react';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Ionicons } from '@expo/vector-icons';
import { VideoView, useVideoPlayer, type VideoPlayer } from 'expo-video';
import { File, Paths } from 'expo-file-system';
import * as Sharing from 'expo-sharing';
import { tokens } from '../src/tokens';
import { useProjectStore } from '../src/stores/projectStore';
import { sceneApi } from '../src/api/scenes';
import { assetUrl } from '../src/config';
import type { SceneResponse } from '../src/api/projects';
import { RefineImageModal } from '../src/components/editor/RefineImageModal';
import { ScriptInputDrawer } from '../src/components/editor/ScriptInputDrawer';
import { SidebarDrawer } from '../src/components/agent/SidebarDrawer';
import { useAgentStore } from '../src/stores/agentStore';

const IMAGE_SIZES = ['1024x1024', '1536x1024', '1024x1536'];
const IMAGE_QUALITIES = ['low', 'medium', 'high'];
const VIDEO_DURATIONS = [4, 6, 8];
const VIDEO_RESOLUTIONS = ['768P', '2K'];
const VIDEO_ASPECTS = ['16:9', '9:16', '4:3', '1:1'];

// 下载图片/视频到缓存目录，返回本地 file URI
async function downloadToCache(url: string): Promise<string> {
  const file = await File.downloadFileAsync(url, Paths.cache);
  return file.uri;
}
// 保存到手机相册（图片/视频），写权限，失败提示
async function saveToPhone(rawUrl: string): Promise<void> {
  try {
    const MediaLibrary = await import('expo-media-library');
    const perm = await MediaLibrary.requestPermissionsAsync(true);
    if (!perm.granted) { Alert.alert('提示', '未获得相册权限，无法保存'); return; }
    const uri = await downloadToCache(assetUrl(rawUrl));
    await MediaLibrary.Asset.create(uri);
    Alert.alert('已保存', '已保存到手机相册');
  } catch {
    Alert.alert('保存失败', '保存失败，请重试');
  }
}
// 分享真实文件给好友
async function shareToFriends(rawUrl: string): Promise<void> {
  try {
    const uri = await downloadToCache(assetUrl(rawUrl));
    if (await Sharing.isAvailableAsync()) {
      await Sharing.shareAsync(uri);
    } else {
      Alert.alert('提示', '当前设备不支持分享');
    }
  } catch {
    Alert.alert('分享失败', '分享失败，请重试');
  }
}
// 长按图片/视频 → 动作面板
function showAssetActions(rawUrl: string): void {
  Alert.alert('媒体操作', '选择操作', [
    { text: '保存到手机', onPress: () => saveToPhone(rawUrl) },
    { text: '分享给好友', onPress: () => shareToFriends(rawUrl) },
    { text: '取消', style: 'cancel' },
  ]);
}

export default function EditorTab() {
  const {
    scenes, currentProject, selectedSceneId, selectScene,
    generatingImage, generatingVideo, videoProgress,
    generateImage, generateVideo, updateProject, updateSceneInStore,
    imageModelOptions, videoModelOptions,
    imageModel, imageSize, imageQuality, setImageModel, setImageSize, setImageQuality,
    videoModel, setVideoModel, refreshScenes,
  } = useProjectStore();
  const [viewMode, setViewMode] = useState<'list' | 'preview'>('list');
  const [scriptOpen, setScriptOpen] = useState(false);
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [openProjectOnOpen, setOpenProjectOnOpen] = useState(false);
  const { conversations, activeConversationId, selectConversation, newConversation, loadConversations } = useAgentStore();
  const insets = useSafeAreaInsets();

  // 选择模式
  const [selectMode, setSelectMode] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [renameTarget, setRenameTarget] = useState<SceneResponse | null>(null);
  const [renameText, setRenameText] = useState('');

  useEffect(() => {
    if (currentProject) loadConversations();
  }, [currentProject?.id]);

  const handleSelectScene = (id: string) => {
    selectScene(id);
    setViewMode('preview');
  };

  const selectedScene = scenes.find((s) => s.id === selectedSceneId);

  // 长按处理
  const handleLongPress = (scene: SceneResponse) => {
    if (selectMode) return;
    setSelectMode(true);
    setSelectedIds(new Set([scene.id]));
  };

  const toggleSelect = (id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  const exitSelectMode = () => {
    setSelectMode(false);
    setSelectedIds(new Set());
  };

  const handleBatchDelete = () => {
    if (selectedIds.size === 0) return;
    Alert.alert('确认删除', `删除 ${selectedIds.size} 个分镜？`, [
      { text: '取消', style: 'cancel' },
      {
        text: '删除', style: 'destructive', onPress: async () => {
          for (const id of selectedIds) {
            try { await sceneApi.delete(id); } catch {}
          }
          exitSelectMode();
          await refreshScenes();
        },
      },
    ]);
  };

  const handleRename = async () => {
    if (!renameTarget || !renameText.trim()) return;
    try {
      await sceneApi.update(renameTarget.id, { sceneNumber: Number(renameText.trim()) });
      updateSceneInStore(renameTarget.id, { sceneNumber: Number(renameText.trim()) });
    } catch {}
    setRenameTarget(null);
  };

  const handleSingleDelete = (scene: SceneResponse) => {
    Alert.alert('删除分镜', `确定删除分镜 ${scene.sceneNumber}？`, [
      { text: '取消', style: 'cancel' },
      {
        text: '删除', style: 'destructive', onPress: async () => {
          try { await sceneApi.delete(scene.id); } catch {}
          await refreshScenes();
        },
      },
    ]);
  };

  return (
    <View style={s.container}>
      {/* List header */}
      {viewMode === 'list' && (
        <View style={[s.header, { height: 56 + insets.top, paddingTop: insets.top }]}>
          {selectMode ? (
            <>
              <TouchableOpacity onPress={exitSelectMode} style={s.headerBtn}>
                <Ionicons name="close" size={20} color={tokens.colors.ink} />
              </TouchableOpacity>
              <Text style={s.headerTitle}>已选 {selectedIds.size} 项</Text>
              <View style={s.headerActions}>
                <TouchableOpacity onPress={() => {
                  if (selectedIds.size === scenes.length) setSelectedIds(new Set());
                  else setSelectedIds(new Set(scenes.map(s => s.id)));
                }}>
                  <Text style={s.selectAllText}>{selectedIds.size === scenes.length ? '取消全选' : '全选'}</Text>
                </TouchableOpacity>
                {selectedIds.size === 1 && (
                  <TouchableOpacity style={s.headerBtn} onPress={() => {
                    const scene = scenes.find(s => selectedIds.has(s.id));
                    if (scene) { setRenameTarget(scene); setRenameText(String(scene.sceneNumber)); }
                  }}>
                    <Ionicons name="create-outline" size={18} color={tokens.colors.primary} />
                  </TouchableOpacity>
                )}
                <TouchableOpacity style={s.headerBtn} onPress={handleBatchDelete}>
                  <Ionicons name="trash-outline" size={18} color={tokens.colors.error} />
                </TouchableOpacity>
              </View>
            </>
          ) : (
            <>
              <TouchableOpacity style={s.menuBtn} onPress={() => { setOpenProjectOnOpen(false); setSidebarOpen(true); }}>
                <Ionicons name="menu-outline" size={22} color={tokens.colors.ink} />
              </TouchableOpacity>
              <TouchableOpacity style={s.headerTitleWrap} activeOpacity={0.7} onPress={() => { setOpenProjectOnOpen(true); setSidebarOpen(true); }}>
                <Text style={s.headerTitle} numberOfLines={1}>{currentProject?.name ?? '分镜编辑器'}</Text>
              </TouchableOpacity>
              <View style={s.headerActions}>
                {currentProject && <Text style={s.headerSub}>{scenes.length} 个分镜</Text>}
                <TouchableOpacity style={s.scriptBtn} onPress={() => setScriptOpen(true)}>
                  <Text style={s.scriptBtnText}>剧本</Text>
                </TouchableOpacity>
              </View>
            </>
          )}
        </View>
      )}

      {viewMode === 'list' && currentProject?.status === 'draft' && !selectMode && (
        <View style={s.draftBanner}>
          <Text style={s.draftText}>有未保存的修改</Text>
          <TouchableOpacity style={s.saveBtn} onPress={() => updateProject(currentProject.id, { status: 'active' })}>
            <Text style={s.saveBtnText}>保存</Text>
          </TouchableOpacity>
        </View>
      )}

      {!currentProject ? (
        <View style={s.empty}>
          <Text style={s.emptyText}>请先在对话中选择或创建项目</Text>
          <TouchableOpacity style={s.scriptBtnLarge} onPress={() => setScriptOpen(true)}>
            <Text style={s.scriptBtnLargeText}>输入剧本</Text>
          </TouchableOpacity>
        </View>
      ) : viewMode === 'preview' && selectedScene ? (
        <ScenePreview
          scene={selectedScene}
          onBack={() => setViewMode('list')}
          insetsTop={insets.top}
          generatingImage={!!generatingImage[selectedScene.id]}
          generatingVideo={!!generatingVideo[selectedScene.id]}
          videoProgress={videoProgress[selectedScene.id]}
          imageModel={imageModel}
          imageSize={imageSize}
          imageQuality={imageQuality}
          videoModel={videoModel}
          imageModelOptions={imageModelOptions}
          videoModelOptions={videoModelOptions}
          setImageModel={setImageModel}
          setImageSize={setImageSize}
          setImageQuality={setImageQuality}
          setVideoModel={setVideoModel}
          onGenerateImage={(prompt, opts) => generateImage(selectedScene.id, prompt, imageModel, undefined, undefined, undefined, opts)}
          onGenerateVideo={(prompt, opts, imgUrl) => generateVideo(selectedScene.id, prompt, videoModel, undefined, imgUrl, opts)}
          updateScene={updateSceneInStore}
        />
      ) : (
        <FlatList
          data={scenes}
          keyExtractor={(item) => item.id}
          contentContainerStyle={s.list}
          renderItem={({ item }) => (
            <SceneCard
              scene={item}
              isGeneratingImage={!!generatingImage[item.id]}
              isGeneratingVideo={!!generatingVideo[item.id]}
              videoProgress={videoProgress[item.id]}
              selectMode={selectMode}
              isSelected={selectedIds.has(item.id)}
              onPress={() => selectMode ? toggleSelect(item.id) : handleSelectScene(item.id)}
              onLongPress={() => handleLongPress(item)}
              onDelete={() => handleSingleDelete(item)}
              onRename={() => { setRenameTarget(item); setRenameText(String(item.sceneNumber)); }}
            />
          )}
          ListEmptyComponent={
            <View style={s.empty}>
              <Text style={s.emptyText}>暂无分镜</Text>
              <Text style={s.emptyHint}>点右上角「剧本」输入剧本，或到对话里生成分镜</Text>
            </View>
          }
        />
      )}

      <ScriptInputDrawer visible={scriptOpen} onClose={() => setScriptOpen(false)} />
      <SidebarDrawer
        visible={sidebarOpen}
        onClose={() => setSidebarOpen(false)}
        onSelectConversation={(id) => { selectConversation(id); }}
        conversations={conversations}
        activeConversationId={activeConversationId}
        onNewConversation={() => { newConversation(); }}
        openProjectsOnOpen={openProjectOnOpen}
      />

      {/* 重命名弹窗 */}
      <Modal visible={!!renameTarget} transparent animationType="fade" onRequestClose={() => setRenameTarget(null)}>
        <View style={s.renameOverlay}>
          <View style={s.renameCard}>
            <Text style={s.renameTitle}>重命名分镜</Text>
            <TextInput style={s.renameInput} value={renameText} onChangeText={setRenameText} autoFocus />
            <View style={s.renameActions}>
              <TouchableOpacity style={s.cancelBtn} onPress={() => setRenameTarget(null)}>
                <Text style={s.cancelBtnText}>取消</Text>
              </TouchableOpacity>
              <TouchableOpacity style={s.confirmBtn} onPress={handleRename}>
                <Text style={s.confirmBtnText}>确定</Text>
              </TouchableOpacity>
            </View>
          </View>
        </View>
      </Modal>
    </View>
  );
}

function SceneCard({ scene, isGeneratingImage, isGeneratingVideo, videoProgress, selectMode, isSelected, onPress, onLongPress, onDelete, onRename }: {
  scene: SceneResponse;
  isGeneratingImage: boolean;
  isGeneratingVideo: boolean;
  videoProgress?: number;
  selectMode: boolean;
  isSelected: boolean;
  onPress: () => void;
  onLongPress: () => void;
  onDelete: () => void;
  onRename: () => void;
}) {
  const imageUrl = scene.imageUrl ? assetUrl(scene.imageUrl) : null;
  return (
    <TouchableOpacity
      style={[s.card, isSelected && s.cardSelected]}
      onPress={onPress}
      onLongPress={onLongPress}
      delayLongPress={400}
      activeOpacity={0.7}
    >
      <View style={s.cardRow}>
        {selectMode && (
          <View style={s.checkWrap}>
            <Ionicons
              name={isSelected ? 'checkbox' : 'square-outline'}
              size={20}
              color={isSelected ? tokens.colors.primary : tokens.colors.textCaption}
            />
          </View>
        )}
        <View style={s.thumb}>
          {imageUrl ? (
            <Image source={{ uri: imageUrl }} style={s.thumbImg} resizeMode="cover" />
          ) : (
            <View style={s.thumbPlaceholder}>
              <Text style={s.thumbNum}>{scene.sceneNumber}</Text>
            </View>
          )}
          {isGeneratingImage && (
            <View style={[StyleSheet.absoluteFill, s.thumbOverlay]}>
              <ActivityIndicator size="small" color={tokens.colors.white} />
            </View>
          )}
        </View>
        <View style={s.cardInfo}>
          <Text style={s.cardTitle}>分镜 {scene.sceneNumber}</Text>
          <Text style={s.cardDesc} numberOfLines={2}>{scene.scriptContent || scene.imagePrompt || '无描述'}</Text>
          <View style={s.cardStatus}>
            {scene.imageUrl ? <Text style={s.statusBadge}><Ionicons name="checkmark-circle" size={12} color={tokens.colors.success} /> 图片</Text> : scene.imageStatus === 'failed' ? <Text style={s.statusFail}>图片失败</Text> : null}
            {scene.videoUrl ? <Text style={s.statusBadge}><Ionicons name="checkmark-circle" size={12} color={tokens.colors.success} /> 视频</Text> : scene.videoStatus === 'failed' ? <Text style={s.statusFail}>视频失败</Text> : null}
            {isGeneratingImage && <Text style={s.statusGenerating}>生成图片中...</Text>}
            {isGeneratingVideo && <Text style={s.statusGenerating}>生成视频中{videoProgress ? ` ${videoProgress}%` : '...'}</Text>}
          </View>
        </View>
        {!selectMode && (
          <View style={s.cardActions}>
            <TouchableOpacity style={s.cardActionBtn} onPress={onRename}>
              <Ionicons name="create-outline" size={15} color={tokens.colors.textSecondary} />
            </TouchableOpacity>
            <TouchableOpacity style={s.cardActionBtn} onPress={onDelete}>
              <Ionicons name="trash-outline" size={15} color={tokens.colors.error} />
            </TouchableOpacity>
          </View>
        )}
      </View>
    </TouchableOpacity>
  );
}

/** 全屏图片预览 Modal */
function ImagePreviewModal({ url, onClose }: { url: string; onClose: () => void }) {
  const insets = useSafeAreaInsets();
  return (
    <Modal visible transparent animationType="fade" onRequestClose={onClose}>
      <View style={s.previewBg}>
        <View style={[s.previewHeader, { paddingTop: insets.top + 8 }]}>
          <TouchableOpacity onPress={onClose}>
            <Ionicons name="close" size={24} color={tokens.colors.white} />
          </TouchableOpacity>
        </View>
        <View style={s.previewBody}>
          <Image source={{ uri: assetUrl(url) }} style={s.fullImg} resizeMode="contain" />
        </View>
      </View>
    </Modal>
  );
}

/** 全屏视频播放 Modal */
function VideoPreviewModal({ url, onClose }: { url: string; onClose: () => void }) {
  const insets = useSafeAreaInsets();
  const player = useVideoPlayer(assetUrl(url), (p: VideoPlayer) => { p.loop = true; p.play(); });
  return (
    <Modal visible transparent animationType="fade" onRequestClose={onClose}>
      <View style={s.previewBg}>
        <View style={[s.previewHeader, { paddingTop: insets.top + 8 }]}>
          <TouchableOpacity onPress={onClose}>
            <Ionicons name="close" size={24} color={tokens.colors.white} />
          </TouchableOpacity>
        </View>
        <View style={s.previewBody}>
          <VideoView
            style={s.fullVideo}
            player={player}
            contentFit="contain"
          />
        </View>
      </View>
    </Modal>
  );
}

function ScenePreview({
  scene, onBack, insetsTop, generatingImage, generatingVideo, videoProgress,
  imageModel, imageSize, imageQuality, videoModel,
  imageModelOptions, videoModelOptions,
  setImageModel, setImageSize, setImageQuality, setVideoModel,
  onGenerateImage, onGenerateVideo, updateScene,
}: {
  scene: SceneResponse;
  onBack: () => void;
  insetsTop: number;
  generatingImage: boolean;
  generatingVideo: boolean;
  videoProgress?: number;
  imageModel: string;
  imageSize: string;
  imageQuality: string;
  videoModel: string;
  imageModelOptions: { value: string; label: string }[];
  videoModelOptions: { value: string; label: string }[];
  setImageModel: (m: string) => void;
  setImageSize: (s: string) => void;
  setImageQuality: (q: string) => void;
  setVideoModel: (m: string) => void;
  onGenerateImage: (prompt: string, opts: { size?: string; quality?: string }) => void;
  onGenerateVideo: (prompt: string, opts: { duration?: number; resolution?: string; aspectRatio?: string }, imgUrl?: string) => void;
  updateScene: (sceneId: string, data: Partial<SceneResponse>) => void;
}) {
  const [activeTab, setActiveTab] = useState<'image' | 'video'>('image');
  const [showParams, setShowParams] = useState(false);
  const [refineTarget, setRefineTarget] = useState<string | null>(null);
  const [imagePreviewUrl, setImagePreviewUrl] = useState<string | null>(null);
  const [videoPreviewUrl, setVideoPreviewUrl] = useState<string | null>(null);
  const [imgSize, setImgSize] = useState(imageSize);
  const [imgQuality, setImgQuality] = useState(imageQuality);
  const [vidDuration, setVidDuration] = useState(8);
  const [vidResolution, setVidResolution] = useState('768P');
  const [vidAspect, setVidAspect] = useState('16:9');
  const [scriptDraft, setScriptDraft] = useState(scene.scriptContent || '');
  const [imgPromptDraft, setImgPromptDraft] = useState(scene.imagePrompt || '');
  const [vidPromptDraft, setVidPromptDraft] = useState(scene.videoPrompt || '');
  const savePrompt = async (field: 'scriptContent' | 'imagePrompt' | 'videoPrompt') => {
    const draft = field === 'scriptContent' ? scriptDraft : field === 'imagePrompt' ? imgPromptDraft : vidPromptDraft;
    const original = field === 'scriptContent' ? scene.scriptContent : field === 'imagePrompt' ? scene.imagePrompt : scene.videoPrompt;
    if (draft.trim() === (original || '').trim()) return;
    try {
      await sceneApi.update(scene.id, { [field]: draft.trim() });
      updateScene(scene.id, { [field]: draft.trim() });
    } catch {}
  };

  const imageList = scene.imageUrls
    ? scene.imageUrls.split(',').filter(Boolean)
    : (scene.imageUrl ? [scene.imageUrl] : []);

  const handleImage = () => {
    const prompt = scene.imagePrompt || '';
    if (!prompt.trim()) { Alert.alert('提示', '请先填写生图提示词'); return; }
    onGenerateImage(prompt, { size: imgSize, quality: imgQuality });
  };
  const handleVideo = () => {
    const prompt = scene.videoPrompt || scene.imagePrompt || '';
    if (!prompt.trim()) { Alert.alert('提示', '请先填写生视频提示词'); return; }
    onGenerateVideo(prompt, { duration: vidDuration, resolution: vidResolution, aspectRatio: vidAspect }, scene.imageUrl || undefined);
  };
  const handleSetSize = (v: string) => { setImgSize(v); setImageSize(v); };
  const handleSetQuality = (v: string) => { setImgQuality(v); setImageQuality(v); };

  return (
    <View style={s.container}>
      <View style={[s.header, { height: 56 + insetsTop, paddingTop: insetsTop }]}>
        <TouchableOpacity onPress={onBack} style={s.backBtn}>
          <Ionicons name="arrow-back-outline" size={18} color={tokens.colors.primary} />
          <Text style={s.backText}>返回</Text>
        </TouchableOpacity>
        <Text style={s.headerTitle}>分镜 {scene.sceneNumber}</Text>
        <TouchableOpacity style={s.gearBtn} onPress={() => setShowParams(!showParams)}>
          <Ionicons name="settings-outline" size={18} color={showParams ? tokens.colors.primary : tokens.colors.textSecondary} />
        </TouchableOpacity>
      </View>

      {showParams && (
        <View style={s.paramsPanel}>
          <Text style={s.paramsTitle}>{activeTab === 'image' ? '图片生成参数' : '视频生成参数'}</Text>
          {activeTab === 'image' ? (
            <>
              <Text style={s.paramsLabel}>模型</Text>
              <OptionRow options={imageModelOptions} value={imageModel} onSelect={setImageModel} />
              <Text style={s.paramsLabel}>尺寸</Text>
              <OptionRow options={IMAGE_SIZES.map((v) => ({ value: v, label: v }))} value={imgSize} onSelect={handleSetSize} />
              <Text style={s.paramsLabel}>质量</Text>
              <OptionRow options={IMAGE_QUALITIES.map((v) => ({ value: v, label: v }))} value={imgQuality} onSelect={handleSetQuality} />
            </>
          ) : (
            <>
              <Text style={s.paramsLabel}>模型</Text>
              <OptionRow options={videoModelOptions} value={videoModel} onSelect={setVideoModel} />
              <Text style={s.paramsLabel}>时长</Text>
              <OptionRow options={VIDEO_DURATIONS.map((v) => ({ value: String(v), label: `${v}秒` }))} value={String(vidDuration)} onSelect={(v) => setVidDuration(Number(v))} />
              <Text style={s.paramsLabel}>分辨率</Text>
              <OptionRow options={VIDEO_RESOLUTIONS.map((v) => ({ value: v, label: v }))} value={vidResolution} onSelect={setVidResolution} />
              <Text style={s.paramsLabel}>画幅</Text>
              <OptionRow options={VIDEO_ASPECTS.map((v) => ({ value: v, label: v }))} value={vidAspect} onSelect={setVidAspect} />
            </>
          )}
        </View>
      )}

      <FlatList
        data={[]}
        renderItem={() => null}
        keyExtractor={() => 'x'}
        contentContainerStyle={s.previewContent}
        ListHeaderComponent={
          <View style={s.preview}>
            <View style={s.previewSection}>
              <Text style={s.previewLabel}>剧本内容</Text>
              <TextInput style={s.promptInput} value={scriptDraft} onChangeText={setScriptDraft} onBlur={() => savePrompt('scriptContent')} multiline placeholder="输入剧本内容..." placeholderTextColor={tokens.colors.textCaption} />
            </View>

            <View style={s.tabRow}>
              {(['image', 'video'] as const).map((t) => (
                <TouchableOpacity key={t} style={[s.tabBtn, activeTab === t && s.tabBtnActive]} onPress={() => setActiveTab(t)}>
                  <Text style={[s.tabText, activeTab === t && s.tabTextActive]}>{t === 'image' ? '图片' : '视频'}</Text>
                </TouchableOpacity>
              ))}
            </View>

            {activeTab === 'image' ? (
              <>
                {imageList.length > 0 ? (
                  <TouchableOpacity activeOpacity={0.9} onPress={() => setImagePreviewUrl(imageList[0])} onLongPress={() => showAssetActions(imageList[0])} delayLongPress={400}>
                    <Image source={{ uri: assetUrl(imageList[0]) }} style={s.previewImg} resizeMode="contain" />
                    <Text style={s.tapHint}>点击放大 · 长按保存/分享</Text>
                  </TouchableOpacity>
                ) : (
                  <View style={s.previewPlaceholder}>
                    <Text style={s.previewPlaceholderText}>暂无图片</Text>
                  </View>
                )}
                <View style={s.previewSection}>
                  <Text style={s.previewLabel}>生图提示词</Text>
                  <TextInput style={s.promptInput} value={imgPromptDraft} onChangeText={setImgPromptDraft} onBlur={() => savePrompt('imagePrompt')} multiline placeholder="输入生图提示词..." placeholderTextColor={tokens.colors.textCaption} />
                </View>
                <View style={s.actionRow}>
                  <TouchableOpacity style={[s.actionBtn, generatingImage && s.actionBtnDisabled]} onPress={handleImage} disabled={generatingImage}>
                    {generatingImage ? <ActivityIndicator size="small" color={tokens.colors.white} /> : <Text style={s.actionBtnText}>{imageList.length ? '重新生成' : '生成图片'}</Text>}
                  </TouchableOpacity>
                  {imageList.length > 0 && (
                    <TouchableOpacity style={s.refineBtn} onPress={() => setRefineTarget(imageList[0])}>
                      <Text style={s.refineBtnText}>完善</Text>
                    </TouchableOpacity>
                  )}
                </View>
              </>
            ) : (
              <>
                {scene.videoUrl ? (
                  <TouchableOpacity activeOpacity={0.9} onPress={() => setVideoPreviewUrl(scene.videoUrl!)} onLongPress={() => showAssetActions(scene.videoUrl!)} delayLongPress={400}>
                    <View style={s.videoThumbWrap}>
                      <Image source={{ uri: assetUrl(scene.imageUrl || scene.videoUrl) }} style={s.previewImg} resizeMode="contain" />
                      <View style={s.videoPlayOverlay}>
                        <Ionicons name="play-circle" size={40} color={tokens.colors.white} />
                      </View>
                    </View>
                    <Text style={s.tapHint}>点击播放 · 长按保存/分享</Text>
                  </TouchableOpacity>
                ) : (
                  <View style={s.previewPlaceholder}>
                    <Text style={s.previewPlaceholderText}>暂无视频</Text>
                  </View>
                )}
                <View style={s.previewSection}>
                  <Text style={s.previewLabel}>生视频提示词</Text>
                  <TextInput style={s.promptInput} value={vidPromptDraft} onChangeText={setVidPromptDraft} onBlur={() => savePrompt('videoPrompt')} multiline placeholder="输入生视频提示词..." placeholderTextColor={tokens.colors.textCaption} />
                </View>
                <TouchableOpacity style={[s.actionBtn, generatingVideo && s.actionBtnDisabled]} onPress={handleVideo} disabled={generatingVideo}>
                  {generatingVideo
                    ? <ActivityIndicator size="small" color={tokens.colors.white} />
                    : <Text style={s.actionBtnText}>{scene.videoUrl ? '重新生成' : '生成视频'}</Text>}
                </TouchableOpacity>
              </>
            )}
          </View>
        }
      />

      {refineTarget && (
        <RefineImageModal sceneId={scene.id} imageUrl={refineTarget} onClose={() => setRefineTarget(null)} />
      )}
      {imagePreviewUrl && (
        <ImagePreviewModal url={imagePreviewUrl} onClose={() => setImagePreviewUrl(null)} />
      )}
      {videoPreviewUrl && (
        <VideoPreviewModal url={videoPreviewUrl} onClose={() => setVideoPreviewUrl(null)} />
      )}
    </View>
  );
}

function OptionRow({ options, value, onSelect }: { options: { value: string; label: string }[]; value: string; onSelect: (v: string) => void }) {
  return (
    <View style={s.optionRow}>
      {options.map((o) => (
        <TouchableOpacity key={o.value} style={[s.optionChip, value === o.value && s.optionChipActive]} onPress={() => onSelect(o.value)}>
          <Text style={[s.optionChipText, value === o.value && s.optionChipTextActive]}>{o.label}</Text>
        </TouchableOpacity>
      ))}
    </View>
  );
}

const s = StyleSheet.create({
  container: { flex: 1, backgroundColor: tokens.colors.canvas },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', borderBottomWidth: 1, borderBottomColor: tokens.colors.hairline, paddingHorizontal: 12 },
  headerBtn: { width: 36, height: 36, alignItems: 'center', justifyContent: 'center' },
  menuBtn: { width: 40, height: 40, alignItems: 'center', justifyContent: 'center' },
  headerTitleWrap: { flex: 1, alignItems: 'center', justifyContent: 'center', flexDirection: 'row', marginHorizontal: 8 },
  headerTitle: { fontSize: tokens.fontSize.md, fontWeight: tokens.fontWeight.bold, color: tokens.colors.ink, textAlign: 'center', flexShrink: 1 },
  headerActions: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  headerSub: { fontSize: tokens.fontSize.sm, color: tokens.colors.textCaption },
  selectAllText: { fontSize: tokens.fontSize.sm, color: tokens.colors.primary, fontWeight: tokens.fontWeight.medium },
  scriptBtn: { paddingHorizontal: 12, paddingVertical: 6, borderRadius: 8, backgroundColor: tokens.colors.primary },
  scriptBtnText: { fontSize: tokens.fontSize.sm, color: tokens.colors.white, fontWeight: tokens.fontWeight.medium },
  scriptBtnLarge: { marginTop: 12, paddingHorizontal: 20, paddingVertical: 10, borderRadius: 10, backgroundColor: tokens.colors.primary },
  scriptBtnLargeText: { fontSize: tokens.fontSize.base, color: tokens.colors.white, fontWeight: tokens.fontWeight.medium },
  draftBanner: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    paddingHorizontal: 16, paddingVertical: 8, backgroundColor: tokens.colors.surface,
    borderBottomWidth: 1, borderBottomColor: tokens.colors.hairline,
  },
  draftText: { fontSize: tokens.fontSize.sm, color: tokens.colors.textSecondary },
  saveBtn: { paddingHorizontal: 14, paddingVertical: 6, borderRadius: 8, backgroundColor: tokens.colors.primary },
  saveBtnText: { fontSize: tokens.fontSize.sm, color: tokens.colors.white, fontWeight: tokens.fontWeight.medium },
  backBtn: { paddingVertical: 4, paddingRight: 8 },
  backText: { fontSize: tokens.fontSize.base, color: tokens.colors.primary, fontWeight: tokens.fontWeight.medium },
  gearBtn: { width: 36, height: 36, alignItems: 'center', justifyContent: 'center', borderRadius: 8 },
  list: { padding: 12, gap: 10 },
  card: { backgroundColor: tokens.colors.white, borderRadius: tokens.radius.md, borderWidth: 1, borderColor: tokens.colors.hairline, padding: 12 },
  cardSelected: { borderColor: tokens.colors.primary, backgroundColor: 'rgba(204,120,92,0.05)' },
  cardRow: { flexDirection: 'row', gap: 12, alignItems: 'center' },
  checkWrap: { width: 24, alignItems: 'center' },
  thumb: { width: 80, height: 60, borderRadius: tokens.radius.sm, overflow: 'hidden', backgroundColor: tokens.colors.surface },
  thumbImg: { width: '100%', height: '100%' },
  thumbPlaceholder: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  thumbNum: { fontSize: tokens.fontSize.lg, fontWeight: tokens.fontWeight.bold, color: tokens.colors.textCaption },
  thumbOverlay: { backgroundColor: 'rgba(0,0,0,0.4)', alignItems: 'center', justifyContent: 'center' },
  cardInfo: { flex: 1, gap: 4 },
  cardTitle: { fontSize: tokens.fontSize.base, fontWeight: tokens.fontWeight.semibold, color: tokens.colors.ink },
  cardDesc: { fontSize: tokens.fontSize.sm, color: tokens.colors.textSecondary, lineHeight: 18 },
  cardStatus: { flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 4 },
  cardActions: { flexDirection: 'column', gap: 4 },
  cardActionBtn: { width: 28, height: 28, alignItems: 'center', justifyContent: 'center' },
  statusBadge: { fontSize: tokens.fontSize.xs, color: tokens.colors.success, fontWeight: tokens.fontWeight.medium },
  statusFail: { fontSize: tokens.fontSize.xs, color: tokens.colors.error, fontWeight: tokens.fontWeight.medium },
  statusGenerating: { fontSize: tokens.fontSize.xs, color: tokens.colors.primary, fontWeight: tokens.fontWeight.medium },
  empty: { flex: 1, alignItems: 'center', justifyContent: 'center', paddingTop: 80 },
  emptyText: { fontSize: tokens.fontSize.base, color: tokens.colors.textSecondary, marginBottom: 4 },
  emptyHint: { fontSize: tokens.fontSize.sm, color: tokens.colors.textCaption, textAlign: 'center', paddingHorizontal: 40 },
  paramsPanel: { paddingHorizontal: 12, paddingVertical: 10, borderBottomWidth: 1, borderBottomColor: tokens.colors.hairline, backgroundColor: tokens.colors.canvas },
  paramsTitle: { fontSize: tokens.fontSize.xs, fontWeight: tokens.fontWeight.semibold, color: tokens.colors.textCaption, marginBottom: 8, letterSpacing: 0.5 },
  paramsLabel: { fontSize: tokens.fontSize.xs, color: tokens.colors.textSecondary, marginTop: 6, marginBottom: 4 },
  optionRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  optionChip: { paddingHorizontal: 12, paddingVertical: 6, borderRadius: tokens.radius.sm, backgroundColor: tokens.colors.surface },
  optionChipActive: { backgroundColor: tokens.colors.primary },
  optionChipText: { fontSize: tokens.fontSize.sm, color: tokens.colors.textSecondary },
  optionChipTextActive: { color: tokens.colors.white, fontWeight: tokens.fontWeight.medium },
  previewContent: { padding: 16, paddingBottom: 32 },
  preview: { gap: 14 },
  previewSection: { gap: 4 },
  previewLabel: { fontSize: tokens.fontSize.xs, fontWeight: tokens.fontWeight.semibold, color: tokens.colors.textCaption, letterSpacing: 0.5 },
  tabRow: { flexDirection: 'row', gap: 8 },
  tabBtn: { paddingHorizontal: 16, paddingVertical: 6, borderRadius: 8, backgroundColor: tokens.colors.surface },
  tabBtnActive: { backgroundColor: tokens.colors.primary },
  tabText: { fontSize: tokens.fontSize.sm, color: tokens.colors.textSecondary, fontWeight: tokens.fontWeight.medium },
  tabTextActive: { color: tokens.colors.white },
  previewImg: { width: '100%', height: 240, borderRadius: tokens.radius.md, backgroundColor: tokens.colors.surface },
  previewPlaceholder: { width: '100%', height: 160, borderRadius: tokens.radius.md, backgroundColor: tokens.colors.surface, alignItems: 'center', justifyContent: 'center' },
  previewPlaceholderText: { fontSize: tokens.fontSize.sm, color: tokens.colors.textCaption },
  promptInput: {
    minHeight: 56, borderWidth: 1, borderColor: tokens.colors.hairline,
    borderRadius: 8, paddingHorizontal: 10, paddingVertical: 8,
    fontSize: tokens.fontSize.sm, color: tokens.colors.ink,
    backgroundColor: tokens.colors.white, textAlignVertical: 'top', lineHeight: 20,
  },
  tapHint: { textAlign: 'center', fontSize: tokens.fontSize.xs, color: tokens.colors.textCaption, marginTop: 6 },
  videoThumbWrap: { position: 'relative' },
  videoPlayOverlay: { ...StyleSheet.absoluteFill, alignItems: 'center', justifyContent: 'center', backgroundColor: 'rgba(0,0,0,0.25)' },
  actionRow: { flexDirection: 'row', gap: 8 },
  actionBtn: { flex: 1, height: 44, borderRadius: 10, backgroundColor: tokens.colors.primary, alignItems: 'center', justifyContent: 'center' },
  actionBtnDisabled: { opacity: 0.5 },
  actionBtnText: { fontSize: tokens.fontSize.base, color: tokens.colors.white, fontWeight: tokens.fontWeight.medium },
  refineBtn: { paddingHorizontal: 18, borderRadius: 10, borderWidth: 1, borderColor: tokens.colors.primary, alignItems: 'center', justifyContent: 'center' },
  refineBtnText: { fontSize: tokens.fontSize.base, color: tokens.colors.primary, fontWeight: tokens.fontWeight.medium },
  // 重命名弹窗
  renameOverlay: { flex: 1, backgroundColor: 'rgba(0,0,0,0.35)', justifyContent: 'center', padding: 32 },
  renameCard: { backgroundColor: tokens.colors.white, borderRadius: 12, padding: 20 },
  renameTitle: { fontSize: tokens.fontSize.base, fontWeight: tokens.fontWeight.semibold, color: tokens.colors.ink, marginBottom: 12 },
  renameInput: { height: 44, borderWidth: 1, borderColor: tokens.colors.hairline, borderRadius: tokens.radius.sm, paddingHorizontal: 12, fontSize: tokens.fontSize.base, color: tokens.colors.ink, marginBottom: 16 },
  renameActions: { flexDirection: 'row', justifyContent: 'flex-end', gap: 8 },
  cancelBtn: { paddingHorizontal: 14, paddingVertical: 6, borderRadius: tokens.radius.sm, backgroundColor: tokens.colors.surface },
  cancelBtnText: { fontSize: tokens.fontSize.sm, color: tokens.colors.textSecondary },
  confirmBtn: { paddingHorizontal: 14, paddingVertical: 6, borderRadius: tokens.radius.sm, backgroundColor: tokens.colors.primary },
  confirmBtnText: { fontSize: tokens.fontSize.sm, color: tokens.colors.white, fontWeight: tokens.fontWeight.medium },
  // 全屏预览
  previewBg: { flex: 1, backgroundColor: 'rgba(0,0,0,0.92)' },
  previewHeader: { flexDirection: 'row', justifyContent: 'flex-end', paddingHorizontal: 16, paddingBottom: 8 },
  previewBody: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 12 },
  fullImg: { width: '100%', height: '100%' },
  fullVideo: { width: '100%', height: '100%' },
});

