# 前端入口镜像（公网测试版）：nginx 静态站 + /api 反代 + 关闭注册
# 构建上下文 = deploy/allinone/build/frontend（由 scripts/build-apps.ps1 暂存）：
#   dist/          主站（AIStoryboardClient）
#   canvas/        画布站（XiaoYueCanvasAgent，vite base=/canvas/）
#   default.conf   nginx 入口配置（含注册关闭规则）
FROM nginx:alpine

RUN rm -f /etc/nginx/conf.d/default.conf

COPY default.conf /etc/nginx/conf.d/default.conf
COPY dist/ /usr/share/nginx/html/
COPY canvas/ /usr/share/nginx/html/canvas/

EXPOSE 80
