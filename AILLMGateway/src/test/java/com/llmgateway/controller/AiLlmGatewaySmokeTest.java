package com.llmgateway.controller;

import com.llmgateway.config.AccessLogFilter;
import com.llmgateway.config.SecurityConfig;
import com.llmgateway.controller.admin.AdminApiKeyController;
import com.llmgateway.controller.admin.AdminAuthController;
import com.llmgateway.controller.admin.AdminChannelController;
import com.llmgateway.controller.admin.AdminConfigController;
import com.llmgateway.controller.admin.AdminLogController;
import com.llmgateway.controller.admin.AdminRouteController;
import com.llmgateway.controller.admin.AdminStatsController;
import com.llmgateway.controller.admin.AdminTestController;
import com.llmgateway.controller.admin.AdminUserController;
import com.llmgateway.controller.admin.ModelParamsController;
import com.llmgateway.dto.admin.AdminLoginResponse;
import com.llmgateway.entity.GatewayApiKey;
import com.llmgateway.mapper.GatewayApiKeyMapper;
import com.llmgateway.security.AdminJwtFilter;
import com.llmgateway.security.JwtTokenProvider;
import com.llmgateway.security.StaticApiKeyFilter;
import com.llmgateway.service.AdminUserService;
import com.llmgateway.service.CallLogService;
import com.llmgateway.service.ChannelService;
import com.llmgateway.service.FileUploadService;
import com.llmgateway.service.GatewayRoutingService;
import com.llmgateway.service.ImageEditService;
import com.llmgateway.service.KeyService;
import com.llmgateway.service.ModelParamsService;
import com.llmgateway.service.ModelRouteService;
import com.llmgateway.service.OllamaModelSyncService;
import com.llmgateway.service.RouteResult;
import com.llmgateway.service.SysConfigService;
import com.llmgateway.service.UpstreamClient;
import com.llmgateway.service.VideoGatewayService;
import com.llmgateway.service.VideoResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
    OpenAiCompatController.class,
    AdminUiController.class,
    AdminApiKeyController.class,
    AdminAuthController.class,
    AdminChannelController.class,
    AdminConfigController.class,
    AdminLogController.class,
    AdminRouteController.class,
    AdminStatsController.class,
    AdminTestController.class,
    AdminUserController.class,
    ModelParamsController.class
})
@Import(SecurityConfig.class)
class AiLlmGatewaySmokeTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GatewayRoutingService routingService;
    @MockitoBean
    private VideoGatewayService videoGatewayService;
    @MockitoBean
    private ImageEditService imageEditService;
    @MockitoBean
    private FileUploadService fileUploadService;
    @MockitoBean
    private ModelParamsService modelParamsService;
    @MockitoBean
    private AdminUserService adminUserService;
    @MockitoBean
    private ChannelService channelService;
    @MockitoBean
    private ModelRouteService routeService;
    @MockitoBean
    private KeyService keyService;
    @MockitoBean
    private UpstreamClient upstreamClient;
    @MockitoBean
    private CallLogService callLogService;
    @MockitoBean
    private SysConfigService sysConfigService;
    @MockitoBean
    private OllamaModelSyncService ollamaModelSyncService;
    @MockitoBean
    private GatewayApiKeyMapper apiKeyMapper;
    @MockitoBean
    private JwtTokenProvider tokenProvider;

    private void allowApiKey() {
        when(keyService.sha256(anyString())).thenReturn("hash");
        when(apiKeyMapper.selectOne(any())).thenReturn(new GatewayApiKey());
    }

    @Test
    void v1_requiresApiKey() throws Exception {
        mockMvc.perform(get("/v1/models"))
            .andExpect(status().isUnauthorized());
        when(keyService.sha256(anyString())).thenReturn("hash");
        when(apiKeyMapper.selectOne(any())).thenReturn(null);
        mockMvc.perform(get("/v1/models").header("Authorization", "Bearer wrong"))
            .andExpect(status().isUnauthorized());

        allowApiKey();
        when(routingService.fetchModels(any())).thenReturn("{\"data\":[]}");
        mockMvc.perform(get("/v1/models").header("Authorization", "Bearer valid"))
            .andExpect(status().isOk());
    }

    @Test
    void v1Endpoints_returnContract() throws Exception {
        allowApiKey();
        when(routingService.route(anyString(), anyString())).thenReturn(new RouteResult(200, "{}"));
        when(routingService.fetchModels(any())).thenReturn("{\"data\":[]}");
        when(videoGatewayService.create(anyString())).thenReturn(new VideoResult(200, "{}"));
        when(videoGatewayService.poll(anyString())).thenReturn(new VideoResult(200, "{}"));
        when(videoGatewayService.cancel(anyString())).thenReturn(new VideoResult(200, "{}"));
        when(videoGatewayService.download(anyString()))
            .thenReturn(ResponseEntity.ok((StreamingResponseBody) out -> { }));
        when(imageEditService.edit(any(), anyString())).thenReturn(new RouteResult(200, "{}"));
        when(fileUploadService.upload(anyString(), any())).thenReturn("file-1");

        mockMvc.perform(post("/v1/chat/completions").header("Authorization", "Bearer valid")
                .contentType("application/json").content("{\"model\":\"m\",\"messages\":[]}"))
            .andExpect(status().isOk());
        mockMvc.perform(post("/v1/images/generations").header("Authorization", "Bearer valid")
                .contentType("application/json").content("{\"prompt\":\"p\"}"))
            .andExpect(status().isOk());
        mockMvc.perform(post("/v1/images/edits").header("Authorization", "Bearer valid")
                .header("Content-Type", "multipart/form-data; boundary=x").content(new byte[]{1, 2}))
            .andExpect(status().isOk());
        mockMvc.perform(get("/v1/models").header("Authorization", "Bearer valid"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(post("/v1/videos").header("Authorization", "Bearer valid")
                .contentType("application/json").content("{\"model\":\"m\"}"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/v1/videos/t1").header("Authorization", "Bearer valid"))
            .andExpect(status().isOk());
        mockMvc.perform(delete("/v1/videos/t1").header("Authorization", "Bearer valid"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/v1/videos/t1/content").header("Authorization", "Bearer valid"))
            .andExpect(status().isOk());
        mockMvc.perform(post("/v1/files/upload").header("Authorization", "Bearer valid")
                .header("Content-Type", "multipart/form-data; boundary=x").content(new byte[]{1}))
            .andExpect(status().isOk());
    }

    @Test
    void adminLogin_andUi_arePublic() throws Exception {
        when(adminUserService.login(any())).thenReturn(new AdminLoginResponse("at", "rt"));
        mockMvc.perform(post("/admin/login")
                .contentType("application/json").content("{\"username\":\"admin\",\"password\":\"pw\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.accessToken").value("at"));
        mockMvc.perform(get("/admin-ui"))
            .andExpect(status().is3xxRedirection());
    }

    @Test
    void admin_requiresJwt() throws Exception {
        mockMvc.perform(get("/admin/channels"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/channels").header("Authorization", "Bearer bad"))
            .andExpect(status().isUnauthorized());
    }
}
