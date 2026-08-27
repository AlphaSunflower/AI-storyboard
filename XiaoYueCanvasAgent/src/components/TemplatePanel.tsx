import { ArrowUpRight, X } from 'lucide-react';
import { CANVAS_TEMPLATES } from '../data/presets';
import { useCanvasStore } from '../store/canvasStore';

export function TemplatePanel({ onClose }: { onClose: () => void }) {
  const loadTemplate = useCanvasStore((state) => state.loadTemplate);

  return (
    <aside className="floating-drawer template-drawer">
      <div className="drawer-heading">
        <div><span className="eyebrow">REUSABLE GRAPHS</span><h2>工作流模板</h2></div>
        <button className="icon-button" onClick={onClose} aria-label="关闭模板"><X size={17} /></button>
      </div>
      <p className="drawer-intro">模板导入的是一组节点和边，不是不可编辑的黑盒。</p>
      <div className="template-list">
        {CANVAS_TEMPLATES.map((template, index) => (
          <button key={template.id} className="template-card" onClick={() => { loadTemplate(template.id); onClose(); }}>
            <div className="template-visual">
              <span className="template-number">0{index + 1}</span>
              <span className="template-line" />
              {template.colors.map((color) => <i key={color} style={{ background: color }} />)}
            </div>
            <span className="template-copy"><strong>{template.title}</strong><small>{template.description}</small></span>
            <ArrowUpRight size={17} />
          </button>
        ))}
      </div>
    </aside>
  );
}
