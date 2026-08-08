-- 模型路由增加类型标注：text（文本模型）/ image（生图模型）/ video（视频生成模型）/ vision（图片/视频理解模型）
-- 类型驱动路由测试走对应真实链路路径（text/vision→chat、image→images/generations、video→VideoGatewayService 创建任务）
ALTER TABLE model_route ADD COLUMN IF NOT EXISTS type VARCHAR(32) NOT NULL DEFAULT 'text';
