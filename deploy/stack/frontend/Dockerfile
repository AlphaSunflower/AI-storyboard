# 前端入口镜像：构建上下文内已含 dist（主站）与 canvas（画布站）以及 default.conf
FROM nginx:alpine

RUN rm -f /etc/nginx/conf.d/default.conf

COPY default.conf /etc/nginx/conf.d/default.conf
COPY dist/ /usr/share/nginx/html/
COPY canvas/ /usr/share/nginx/html/canvas/

EXPOSE 80
