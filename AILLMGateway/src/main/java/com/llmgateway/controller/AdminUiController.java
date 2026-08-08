package com.llmgateway.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 管理后台静态页入口：/admin-ui/（或 /admin-ui）重定向到 index.html。
 * 原因：Spring Boot 4 对子目录的 welcome page（index.html 隐式解析）不生效，
 * 直接访问 /admin-ui/ 会命中 NoResourceFoundException 返回 404；显式重定向保证
 * 浏览器输入 http://<host>:8083/admin-ui/ 即可打开管理界面。
 */
@Controller
public class AdminUiController {

    @GetMapping({"/admin-ui", "/admin-ui/"})
    public String adminUi() {
        return "redirect:/admin-ui/index.html";
    }
}
