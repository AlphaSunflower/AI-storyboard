package com.llmgateway.controller;

import com.llmgateway.service.GatewayRoutingService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** OpenAI 兼容对外入口（静态 Key 鉴权由 StaticApiKeyFilter 完成） */
@RestController
@RequestMapping("/v1")
public class OpenAiCompatController {

    private final GatewayRoutingService routingService;

    public OpenAiCompatController(GatewayRoutingService routingService) {
        this.routingService = routingService;
    }

    @PostMapping(value = "/chat/completions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> chatCompletions(@RequestBody String body) {
        GatewayRoutingService.RouteResult result = routingService.route("/chat/completions", body);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @PostMapping(value = "/images/generations", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> imageGenerations(@RequestBody String body) {
        GatewayRoutingService.RouteResult result = routingService.route("/images/generations", body);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @GetMapping(value = "/models", produces = MediaType.APPLICATION_JSON_VALUE)
    public String models() {
        return "{\"object\":\"list\",\"data\":[]}";
    }
}
