import { FileText, FileSpreadsheet, Download, ListChecks } from 'lucide-react';
import { FileCard } from './shared';

/** 文档交付示意：一页真实的执行方案 + 聊天里的文件卡。 */
export function DocumentPreview() {
  return (
    <div className="mx-stack-12">
      <div className="mx-paper">
        <div className="mx-paper__meta">内部文档 · v3 · 2026-10-20</div>
        <h4 className="mx-paper__title">雨夜咖啡馆 · 拍摄执行方案</h4>
        <p>
          目标：用 40 秒讲完「雨夜进店 → 手冲 → 坐下」的一条情绪线，画面统一为低饱和暖调，
          所有镜头复用同一套人物与场景资产。
        </p>

        <h5>一、镜头清单（8 镜 / 110 秒）</h5>
        <table className="mx-paper__table">
          <thead>
            <tr><th>镜号</th><th>画面</th><th>时长</th><th>关键资产</th></tr>
          </thead>
          <tbody>
            <tr><td>01</td><td>雨夜招牌亮起</td><td>15s</td><td>街景 / 招牌</td></tr>
            <tr><td>02</td><td>推门进店，暖光扑面</td><td>15s</td><td>门店 / 门框</td></tr>
            <tr><td>03</td><td>吧台手冲，蒸汽升起</td><td>14s</td><td>咖啡师 / 手冲壶</td></tr>
            <tr><td>04</td><td>窗边坐下，举杯</td><td>14s</td><td>咖啡师 / 窗景</td></tr>
          </tbody>
        </table>

        <h5>二、素材与一致性</h5>
        <p>人物卡「阿伟」锁发型、围裙与手部细节；场景卡「咖啡馆」锁吧台材质与主光方向。每镜只允许追加，不允许改设定。</p>

        <h5>三、交付</h5>
        <p>成片 1080P / 16:9；同时交付可编辑的分镜脚本与预算表，方便二次修改。</p>
      </div>

      <div>
        <div className="mx-field__label" style={{ marginBottom: 6 }}>
          <b>聊天里的文件卡</b>
          <span>点开即预览 / 下载</span>
        </div>
        <div className="mx-filecards">
          <FileCard icon={<FileText size={14} strokeWidth={1.8} />} name="拍摄执行方案.docx" meta="Word · 42 KB" />
          <FileCard icon={<FileSpreadsheet size={14} strokeWidth={1.8} />} name="镜头清单.xlsx" meta="Excel · 18 KB" />
          <FileCard icon={<ListChecks size={14} strokeWidth={1.8} />} name="分镜脚本.md" meta="Markdown · 12 KB" />
          <FileCard icon={<Download size={14} strokeWidth={1.8} />} name="完整方案.pdf" meta="PDF · 1.2 MB" />
        </div>
      </div>

      <div className="mx-note" style={{ borderLeft: '2px solid var(--color-primary)', paddingLeft: 10 }}>
        文档、表格、PDF 和图表都会进「产出素材」，与图片视频同一套资产库，不用去翻聊天记录。
      </div>
    </div>
  );
}
