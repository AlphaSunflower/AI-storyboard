import { useMemo, useState } from "react";
import { useAgentStore, type HumanInputInfo } from "../../stores/agentStore";
import { AgentParamSelector } from "./AgentParamSelector";
import { planCardPrefill } from "../../lib/generationPreferences";
import { assetUrl } from "../../config";
import { ChevronDown, ChevronUp } from "lucide-react";
import { Tooltip } from "../common/Tooltip";

/**
 * 人工确认卡片（human_input 事件）：渲染 actions 选项按钮；
 * id=custom 的「自定义输入」选项点击后展开内联输入框，用户可输入选项之外的想法。
 * 后端下发 models 时渲染模型/参数选择器（如图片确认卡片可选模型/尺寸），提交时携带所选。
 * 后端下发 assets 时渲染资产/分镜勾选列表（默认全选），
 * 所有 action 均携带勾选 ID（asset-skip 携带空数组）。
 *
 * 新增功能：
 * - collapsed/onToggleCollapse: 控制卡片折叠/展开（上下箭头）
 * - 选择后仍显示卡片，可查看已选择的选项和其他未选择的选项
 */
export function HumanInputCard({
  info,
  archived = false,
  collapsed = false,
  onToggleCollapse,
  submittedActionId,
  submittedActionTitle,
  customText,
  params,
  assetIds,
}: {
  info: HumanInputInfo;
  archived?: boolean;
  collapsed?: boolean;
  onToggleCollapse?: () => void;
  submittedActionId?: string;
  submittedActionTitle?: string;
  customText?: string;
  params?: Record<string, string>;
  assetIds?: string[];
}) {
  const submitHumanInput = useAgentStore((s) => s.submitHumanInput);
  const skipCurrentHITL = useAgentStore((s) => s.skipCurrentHITL);
  const streaming = useAgentStore((s) => s.streaming);
  // 生成偏好（自动时不预选，自定义时按偏好预选；见 计划卡预选）
  const generationPreferences = useAgentStore((s) => s.generationPreferences);
  const imagePrefill = useMemo(
    () => planCardPrefill(generationPreferences, 'image', info.imageModels),
    [generationPreferences, info.imageModels],
  );
  const videoPrefill = useMemo(
    () => planCardPrefill(generationPreferences, 'video', info.videoModels),
    [generationPreferences, info.videoModels],
  );
  const expired = info.expirationTime > 0 && Date.now() / 1000 > info.expirationTime;
  // 自定义输入展开态：点「自定义输入」按钮展开内联输入框，确认后 submitHumanInput("custom", text)
  const [customOpen, setCustomOpen] = useState(false);
  const [customInputText, setCustomInputText] = useState("");
  // 卡片参数选择器的当前选择（模型/尺寸等；无选择器时为 {}）
  const [selectedParams, setSelectedParams] = useState<Record<string, string>>({});
  // storyboard 分镜卡片：图片/视频两个分区选择器的提交值（键带 image*/video* 前缀）
  const mergeParams = (p: Record<string, string>) => setSelectedParams((prev) => ({ ...prev, ...p }));
  // 分区选择器模式：后端下发 imageModels/videoModels 时渲染图片+视频两组；否则保持原单选择器逻辑
  const hasSplitSelectors = !!((info.imageModels && info.imageModels.length > 0) || (info.videoModels && info.videoModels.length > 0));
  // 资产/分镜勾选状态：默认全选（与卡片文档一致）；用户可逐个取消勾选，再点下方动作按钮确认
  const [selectedAssets, setSelectedAssets] = useState<Set<string>>(
    () => new Set((info.assets ?? []).map((a) => a.id)),
  );
  const toggleAsset = (id: string) =>
    setSelectedAssets((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  const typeLabel = (t: string) => (t === "character" ? "人物" : t === "prop" ? "道具" : t === "scene" ? "场景" : t);
  // 按钮提交：有 assets 时所有 action 均携带勾选 ID；asset-skip 携带空数组
  const hasAssets = !!(info.assets && info.assets.length > 0);
  // 已选择的 action ID（用于选择后仍显示卡片）
  const [selectedActionId, setSelectedActionId] = useState<string | null>(submittedActionId || null);
  
  const handleActionClick = (a: { id: string; title: string }) => {
    if (archived) return;
    if (a.id === "custom") {
      setCustomOpen(true);
      return;
    }
    if (a.id === "asset-skip") {
      submitHumanInput(a.id, undefined, selectedParams, []);
    } else if (hasAssets) {
      // 有资产/分镜列表时，所有 action 均携带勾选 ID
      submitHumanInput(a.id, undefined, selectedParams, Array.from(selectedAssets));
    } else {
      submitHumanInput(a.id, undefined, selectedParams);
    }
    // 记录已选择的 action
    setSelectedActionId(a.id);
  };

  // 折叠状态：只显示标题和上下箭头
  if (collapsed) {
    const displayTitle = submittedActionTitle || info.actions.find(a => a.id === selectedActionId)?.title || selectedActionId;
    return (
      <div style={{
        background: submittedActionId ? "var(--color-canvas)" : "white",
        borderRadius: 12,
        border: "1px solid var(--color-hairline)",
        boxShadow: "0 2px 8px rgba(20,20,19,0.06)",
        padding: "10px 16px",
        display: "flex",
        alignItems: "center",
        justifyContent: "space-between",
      }}>
        <div style={{ fontSize: 13, color: "var(--color-muted)", letterSpacing: 1 }}>
          需要您确认
          {selectedActionId && displayTitle && (
            <span style={{ marginLeft: 8, color: "var(--color-primary)", fontWeight: 500 }}>
              （{displayTitle}）
            </span>
          )}
        </div>
        <button
          onClick={onToggleCollapse}
          aria-label="展开"
          style={{
            border: "none",
            background: "transparent",
            cursor: "pointer",
            padding: 4,
            display: "flex",
            alignItems: "center",
            color: "var(--color-muted)",
          }}
        >
          <ChevronUp size={16} />
        </button>
      </div>
    );
  }

  return (
    <div style={{
      background: "white",
      borderRadius: 16,
      border: "1px solid var(--color-hairline)",
      boxShadow: "0 2px 8px rgba(20,20,19,0.06)",
      padding: 20,
      maxHeight: "min(46vh, 480px)",
      overflowY: "auto",
      overflowX: "hidden",
      scrollbarWidth: "thin",
      textAlign: "left",
      opacity: archived ? 0.68 : 1,
      pointerEvents: archived ? "none" : undefined,
    }}>
      {/* 头部：标题 + 折叠按钮 */}
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 8 }}>
        <div style={{ fontSize: 13, color: "var(--color-muted)", letterSpacing: 1 }}>需要您确认</div>
        {onToggleCollapse && (
          <Tooltip label="折叠">
          <button
            onClick={onToggleCollapse}
            aria-label="折叠"
            style={{
              border: "none",
              background: "transparent",
              cursor: "pointer",
              padding: 4,
              display: "flex",
              alignItems: "center",
              color: "var(--color-muted)",
            }}
          >
            <ChevronDown size={16} />
          </button>
          </Tooltip>
        )}
      </div>
      
      <div style={{ fontSize: 15, color: "var(--color-ink)", lineHeight: 1.7, marginBottom: 12, whiteSpace: "pre-wrap" }}>
        {info.formContent || "请确认是否继续？"}
      </div>
      {/* 已提交信息：自定义文本 / 勾选资产 / 参数摘要 */}
      {submittedActionId && (
        <div style={{ fontSize: 12, color: "var(--color-muted)", marginBottom: 10, display: "flex", flexWrap: "wrap", gap: 6 }}>
          {customText && <span style={{ background: "var(--color-surface-soft)", borderRadius: 6, padding: "2px 8px" }}>输入：{customText}</span>}
          {assetIds && assetIds.length > 0 && <span style={{ background: "var(--color-surface-soft)", borderRadius: 6, padding: "2px 8px" }}>勾选 {assetIds.length} 项</span>}
          {params && Object.keys(params).length > 0 && <span style={{ background: "var(--color-surface-soft)", borderRadius: 6, padding: "2px 8px" }}>已选参数</span>}
        </div>
      )}
      
      {/* 资产/分镜勾选列表：默认不选，用户自行勾选 */}
      {info.assets && info.assets.length > 0 && (
        <div style={{ marginBottom: 10, border: "1px solid var(--color-hairline)", borderRadius: "var(--rounded-md)", padding: 8, maxHeight: 240, overflowY: "auto" }}>
          <label style={{ display: "flex", alignItems: "center", gap: 8, padding: "2px 0 6px", cursor: streaming ? "not-allowed" : "pointer", opacity: streaming ? 0.6 : 1, borderBottom: "1px solid var(--color-hairline)", marginBottom: 4 }}>
            <input
              type="checkbox"
              checked={selectedAssets.size === info.assets!.length && info.assets!.length > 0}
              disabled={streaming}
              onChange={() => {
                if (selectedAssets.size === info.assets!.length) setSelectedAssets(new Set());
                else setSelectedAssets(new Set(info.assets!.map((a) => a.id)));
              }}
              style={{ accentColor: "var(--color-primary)", cursor: "inherit" }}
            />
            <span style={{ fontSize: 13, fontWeight: 500, color: "var(--color-ink)" }}>全选</span>
            <span style={{ marginLeft: "auto", fontSize: 11, color: "var(--color-muted)" }}>
              已选 {selectedAssets.size}/{info.assets.length} 项，取消勾选后点下方按钮确认
            </span>
          </label>
          {info.assets.map((a) => (
            <label key={a.id} style={{ display: "flex", alignItems: "center", gap: 8, padding: "3px 0", cursor: streaming ? "not-allowed" : "pointer", opacity: streaming ? 0.6 : 1 }}>
              <input
                type="checkbox"
                checked={selectedAssets.has(a.id)}
                disabled={streaming}
                onChange={() => toggleAsset(a.id)}
                style={{ accentColor: "var(--color-primary)", cursor: "inherit" }}
              />
              {a.image && (
                <img
                  src={assetUrl(a.image)}
                  alt={a.name}
                  style={{ width: 28, height: 28, borderRadius: 4, objectFit: "cover" }}
                />
              )}
              <span style={{ fontSize: 13, color: "var(--color-ink)" }}>
                {a.name}
                <span style={{ fontSize: 11, color: "var(--color-muted)", marginLeft: 4 }}>{typeLabel(a.type)}</span>
              </span>
            </label>
          ))}
        </div>
      )}
      
      {/* 模型/参数选择器：storyboard 分镜卡片渲染图片+视频两组（LLM 推荐预选+理由）；其余卡片保持单选择器 */}
      {hasSplitSelectors ? (
        <div style={{ marginBottom: 10 }}>
          {info.imageModels && info.imageModels.length > 0 && (
            <AgentParamSelector
              keyPrefix="image"
              models={info.imageModels}
              recommended={info.recommended}
              reasons={info.reasons}
              initialValue={imagePrefill}
              onParamsChange={mergeParams}
            />
          )}
          {info.videoModels && info.videoModels.length > 0 && (
            <AgentParamSelector
              keyPrefix="video"
              models={info.videoModels}
              recommended={info.recommended}
              reasons={info.reasons}
              initialValue={videoPrefill}
              onParamsChange={mergeParams}
            />
          )}
        </div>
      ) : info.models && info.models.length > 0 && !customOpen ? (
        <AgentParamSelector
          models={info.models}
          recommended={info.recommended}
          reasons={info.reasons}
          onParamsChange={setSelectedParams}
        />
      ) : null}
      
      {expired ? (
        <div style={{ fontSize: 14, color: "var(--color-warning)" }}>确认已过期，请重新发起对话</div>
      ) : customOpen ? (
        <div>
          <input
            autoFocus
            value={customInputText}
            onChange={(e) => setCustomInputText(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && customInputText.trim() && !streaming) {
                submitHumanInput("custom", customInputText.trim(), selectedParams);
              }
            }}
            placeholder="输入你的想法…"
            style={{
              width: "100%", boxSizing: "border-box", padding: "6px 10px",
              border: "1px solid var(--color-hairline)", borderRadius: "var(--rounded-md)",
              fontSize: 13, outline: "none", marginBottom: 8,
            }}
          />
          <div style={{ display: "flex", gap: 8 }}>
            <button
              disabled={streaming || !customInputText.trim()}
              onClick={() => submitHumanInput("custom", customInputText.trim(), selectedParams)}
              style={{
                padding: "10px 18px", border: "none", borderRadius: "var(--rounded-md)",
                background: "var(--color-primary)", color: "white", fontSize: 15,
                cursor: streaming || !customInputText.trim() ? "not-allowed" : "pointer",
                opacity: streaming || !customInputText.trim() ? 0.6 : 1,
              }}
            >
              确认输入
            </button>
            <button
              disabled={streaming}
              onClick={() => { setCustomOpen(false); setCustomInputText(""); }}
              style={{
                padding: "10px 18px", border: "1px solid var(--color-hairline)",
                borderRadius: "var(--rounded-md)", background: "transparent",
                color: "var(--color-muted)", fontSize: 13,
                cursor: streaming ? "not-allowed" : "pointer", opacity: streaming ? 0.6 : 1,
              }}
            >
              取消
            </button>
          </div>
        </div>
      ) : (
        <>
          <div style={{ display: "flex", flexDirection: "column", gap: 8, marginBottom: 8 }}>
            {info.actions.map((a) => (
              <button
                key={a.id}
                disabled={streaming || !!selectedActionId || !!submittedActionId}
                onClick={() => handleActionClick(a)}
                style={{
                  padding: "10px 18px", border: "none", borderRadius: "var(--rounded-md)",
                  background: selectedActionId === a.id ? "var(--color-primary)" : selectedActionId ? "var(--color-canvas)" : "var(--color-primary)",
                  color: selectedActionId === a.id ? "white" : selectedActionId ? "var(--color-muted)" : "white",
                  fontSize: 15,
                  cursor: streaming || selectedActionId ? "not-allowed" : "pointer",
                  opacity: streaming || ((selectedActionId || submittedActionId) && (selectedActionId || submittedActionId) !== a.id) ? 0.6 : 1,
                  width: "100%", textAlign: "center",
                  fontWeight: (selectedActionId === a.id || submittedActionId === a.id) ? 600 : 400,
                }}
              >
                {a.title}
                {selectedActionId === a.id && " ✓"}
              </button>
            ))}
          </div>
          {/* 跳过按钮：允许用户切换话题（checkpoint 30 分钟后自动过期） */}
          {!selectedActionId && !submittedActionId && (
            <button
              disabled={streaming}
              onClick={skipCurrentHITL}
              style={{
                padding: "6px 12px", border: "none", borderRadius: "var(--rounded-md)",
                background: "transparent", color: "var(--color-muted)", fontSize: 12,
                cursor: streaming ? "not-allowed" : "pointer", opacity: streaming ? 0.6 : 1,
                width: "100%", textAlign: "center",
              }}
            >
              跳过，换个话题
            </button>
          )}
        </>
      )}
    </div>
  );
}
