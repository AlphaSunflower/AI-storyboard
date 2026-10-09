package com.storyboard.controller;

import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.ImageGenerationService;
import com.storyboard.ai.ScriptGenerationService;
import com.moon.moonagent.ai.VideoGenerationService;
import com.storyboard.security.AiAccessGuard;
import com.storyboard.security.AuthSessionService;
import com.storyboard.security.FileUrlSigner;
import com.storyboard.common.ApiResponse;
import com.storyboard.config.SecurityConfig;
import com.storyboard.dto.response.AssetImageVO;
import com.storyboard.dto.response.AssetVO;
import com.storyboard.dto.response.LoginResponse;
import com.storyboard.dto.response.AuthResult;
import com.storyboard.dto.response.ProfileResponse;
import com.storyboard.dto.response.ProjectResponse;
import com.storyboard.dto.response.SceneAssetsResponse;
import com.storyboard.dto.response.SceneFull;
import com.storyboard.dto.response.SceneReferenceResponse;
import com.storyboard.dto.response.SceneResponse;
import com.storyboard.dto.response.TaskStatusResponse;
import com.storyboard.dto.response.UserStatsResponse;
import com.storyboard.entity.Project;
import com.storyboard.entity.Scene;
import com.storyboard.entity.SceneAsset;
import com.storyboard.asset.client.AssetContracts.ProjectOwnership;
import com.storyboard.mapper.AgentAssetMapper;
import com.storyboard.mapper.AssetImageMapper;
import com.storyboard.mapper.AssetMapper;
import com.storyboard.mapper.ProjectMapper;
import com.storyboard.mapper.SceneAssetMapper;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.mapper.ScenePromptMapper;
import com.storyboard.mapper.SceneReferenceImageMapper;
import com.storyboard.mapper.SceneMaterialGroupSnapshotMapper;
import com.storyboard.mapper.SceneAssetLinkMapper;
import com.storyboard.mapper.UserMapper;
import com.storyboard.security.JwtTokenProvider;
import com.storyboard.service.AssetService;
import com.storyboard.service.AuthService;
import com.storyboard.service.FileStorageService;
import com.storyboard.service.InternalApiService;
import com.storyboard.service.ProjectService;
import com.storyboard.service.SceneService;
import com.storyboard.service.SceneMaterialService;
import com.storyboard.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
    AuthController.class,
    UserController.class,
    ProjectController.class,
    SceneController.class,
    AssetController.class,
    FileController.class,
    AIController.class,
    InternalApiController.class
})
@Import(SecurityConfig.class)
@TestPropertySource(properties = "internal.secret=test-internal-secret")
class BackendControllerSmokeTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private ProjectService projectService;
    @MockitoBean
    private SceneService sceneService;
    @MockitoBean
    private SceneMaterialService sceneMaterialService;
    @MockitoBean
    private AssetService assetService;
    @MockitoBean
    private FileStorageService fileStorageService;
    @MockitoBean
    private FileUrlSigner fileUrlSigner;
    @MockitoBean
    private InternalApiService internalApiService;
    @MockitoBean
    private ScriptGenerationService scriptGenerationService;
    @MockitoBean
    private ImageGenerationService imageGenerationService;
    @MockitoBean
    private VideoGenerationService videoGenerationService;
    @MockitoBean
    private AiAccessGuard aiAccessGuard;
    @MockitoBean
    private AuthSessionService authSessionService;
    @MockitoBean
    private GatewayModelService gatewayModelService;
    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;
    @MockitoBean
    private AgentAssetMapper agentAssetMapper;
    @MockitoBean
    private AssetImageMapper assetImageMapper;
    @MockitoBean
    private AssetMapper assetMapper;
    @MockitoBean
    private ProjectMapper projectMapper;
    @MockitoBean
    private SceneAssetMapper sceneAssetMapper;
    @MockitoBean
    private SceneMapper sceneMapper;
    @MockitoBean
    private ScenePromptMapper scenePromptMapper;
    @MockitoBean
    private SceneReferenceImageMapper sceneReferenceImageMapper;
    @MockitoBean
    private SceneMaterialGroupSnapshotMapper sceneMaterialGroupSnapshotMapper;
    @MockitoBean
    private SceneAssetLinkMapper sceneAssetLinkMapper;
    @MockitoBean
    private UserMapper userMapper;

    private static final String AUTH = "X-Internal-Token";
    private static final String SECRET = "test-internal-secret";

    @Test
    void authEndpoints_arePublic_andReturnContract() throws Exception {
        when(authService.login(any())).thenReturn(
                new AuthResult(new LoginResponse("at", "u1", "n"), "rt"));
        when(authService.register(any())).thenReturn(
                new AuthResult(new LoginResponse("at", "u1", "n"), "rt"));
        when(authService.refresh(anyString())).thenReturn(
                new AuthResult(new LoginResponse("at2", "u1", "n"), "rt2"));
        when(authService.handleUnlogin(any(), any())).thenReturn(ApiResponse.ok(Map.of("ok", true)));

        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json").content("{\"email\":\"user@example.com\",\"password\":\"Passw0rd!\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(post("/api/auth/register")
                .contentType("application/json").content("{\"email\":\"user@example.com\",\"password\":\"Passw0rd!\",\"displayName\":\"n\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.accessToken").value("at"));
        mockMvc.perform(post("/api/auth/refresh")
                .contentType("application/json").content("{\"refreshToken\":\"rt\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.accessToken").value("at2"));
        mockMvc.perform(post("/api/auth/unlogin")
                .contentType("application/json").content("{\"authorizationCode\":\"x\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void userEndpoints_requireAuth_andReturnContract() throws Exception {
        mockMvc.perform(get("/api/user/profile"))
            .andExpect(status().isUnauthorized());

        when(userService.getProfile(anyString())).thenReturn(new ProfileResponse("u1", "n", "a@b.c"));
        when(userService.updateProfile(anyString(), any())).thenReturn(new ProfileResponse("u1", "n2", "a@b.c"));
        when(userService.getStats(anyString())).thenReturn(new UserStatsResponse(1, 2, 3));

        mockMvc.perform(get("/api/user/profile").with(user("u1")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.userId").value("u1"));
        mockMvc.perform(put("/api/user/profile").with(user("u1"))
                .contentType("application/json").content("{\"displayName\":\"n2\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.displayName").value("n2"));
        mockMvc.perform(put("/api/user/password").with(user("u1"))
                .contentType("application/json").content("{\"oldPassword\":\"a\",\"newPassword\":\"b\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get("/api/user/stats").with(user("u1")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.projectCount").value(3));
    }

    @Test
    void projectEndpoints_requireAuth_andReturnContract() throws Exception {
        mockMvc.perform(get("/api/projects"))
            .andExpect(status().isUnauthorized());

        ProjectResponse project = new ProjectResponse("p1", "u1", "项目", null, null, null, "16:9",
            null, null, null, "draft", null, null, List.of());
        when(projectService.listByUser(anyString())).thenReturn(List.of(project));
        when(projectService.create(anyString(), any())).thenReturn(project);
        when(projectService.getById(anyString(), anyString())).thenReturn(project);
        when(projectService.update(anyString(), anyString(), any())).thenReturn(new ProjectResponse(
            "p1", "u1", "改名", null, null, null, "16:9", null, null, null, "active", null, null, List.of()));
        when(projectService.getLatestDraft(anyString())).thenReturn(project);

        mockMvc.perform(get("/api/projects").with(user("u1")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value("p1"));
        mockMvc.perform(post("/api/projects").with(user("u1"))
                .contentType("application/json").content("{\"name\":\"项目\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("draft"));
        mockMvc.perform(get("/api/projects/draft").with(user("u1")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("p1"));
        mockMvc.perform(get("/api/projects/p1").with(user("u1")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("p1"));
        mockMvc.perform(put("/api/projects/p1").with(user("u1"))
                .contentType("application/json").content("{\"status\":\"active\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("active"));
        mockMvc.perform(delete("/api/projects/p1").with(user("u1")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void sceneEndpoints_requireAuth_andReturnContract() throws Exception {
        SceneResponse scene = new SceneResponse("s1", "p1", null, 1, "内容", "图", "视频", null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null);
        when(sceneService.addScene(anyString(), anyString(), any())).thenReturn(scene);
        when(sceneService.updateScene(anyString(), anyString(), any())).thenReturn(scene);
        when(sceneService.listReferences(anyString(), anyString()))
            .thenReturn(List.of(new SceneReferenceResponse("r1", "image", "参考", "/api/files/images/x.png",
                "REFERENCE", "upload", null, null, "x.png", 1L)));
        when(sceneService.uploadReference(anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(new SceneReferenceResponse("r1", "image", "参考", "/api/files/images/x.png",
                "REFERENCE", "upload", null, null, "x.png", 1L));
        when(sceneMaterialService.linkReferences(anyString(), anyString(), any()))
            .thenReturn(List.of(new SceneReferenceResponse("r2", "image", "image", "/api/files/images/output.png",
                "REFERENCE", "output", "out-1", "c1", null, null)));

        mockMvc.perform(post("/api/projects/p1/scenes").with(user("u1"))
                .contentType("application/json").content("{\"scriptContent\":\"内容\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("s1"));
        mockMvc.perform(put("/api/scenes/s1").with(user("u1"))
                .contentType("application/json").content("{\"scriptContent\":\"改\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("s1"));
        mockMvc.perform(delete("/api/scenes/s1").with(user("u1")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(put("/api/projects/p1/scenes/reorder").with(user("u1"))
                .contentType("application/json").content("{\"sceneIds\":[\"s1\",\"s2\"]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get("/api/scenes/s1/references").with(user("u1")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].type").value("image"));
        MockMultipartFile refFile = new MockMultipartFile("file", "ref.png", "image/png", new byte[]{1, 2, 3});
        mockMvc.perform(multipart("/api/scenes/s1/references").file(refFile)
                .param("type", "image").param("purpose", "参考").with(user("u1")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("r1"));
        mockMvc.perform(delete("/api/scenes/references/r1").with(user("u1")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(post("/api/scenes/s1/references/link").with(user("u1"))
                .contentType("application/json")
                .content("[{\"type\":\"image\",\"purpose\":\"image\",\"url\":\"/api/files/images/output.png\","
                    + "\"role\":\"REFERENCE\",\"sourceType\":\"output\",\"sourceId\":\"out-1\","
                    + "\"sourceConversationId\":\"c1\"}]"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].sourceType").value("output"));
        mockMvc.perform(put("/api/scenes/s1/materials").with(user("u1"))
                .contentType("application/json").content("{\"assetIds\":[\"a1\"],\"references\":[]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void assetEndpoints_requireAuth_andReturnContract() throws Exception {
        AssetImageVO img = new AssetImageVO("img1", "/api/files/images/a.png", 1, "a.png", "text");
        AssetVO asset = new AssetVO("a1", "character", "资产", null, "p1", List.of(img), null, null);
        when(assetService.create(anyString(), any())).thenReturn(asset);
        when(assetService.list(anyString(), any(), any())).thenReturn(List.of(asset));
        when(assetService.update(anyString(), anyString(), any())).thenReturn(asset);
        when(assetService.uploadImage(anyString(), anyString(), any(), any()))
            .thenReturn(new AssetImageVO("img1", "/api/files/images/a.png", 1, "a.png", "text"));
        when(assetService.listSceneAssets(anyString(), anyString()))
            .thenReturn(new SceneAssetsResponse(List.of(asset), List.of()));

        mockMvc.perform(post("/api/assets").with(user("u1"))
                .contentType("application/json").content("{\"name\":\"资产\",\"type\":\"character\",\"projectId\":\"p1\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("a1"));
        mockMvc.perform(get("/api/assets?projectId=p1&type=character").with(user("u1")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].type").value("character"));
        mockMvc.perform(put("/api/assets/a1").with(user("u1"))
                .contentType("application/json").content("{\"name\":\"改名\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("a1"));
        mockMvc.perform(delete("/api/assets/a1").with(user("u1")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        MockMultipartFile aFile = new MockMultipartFile("file", "a.png", "image/png", new byte[]{1});
        mockMvc.perform(multipart("/api/assets/a1/images").file(aFile).with(user("u1")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("img1"));
        mockMvc.perform(delete("/api/assets/a1/images/img1").with(user("u1")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(put("/api/scenes/s1/assets").with(user("u1"))
                .contentType("application/json").content("{\"imageAssetIds\":[\"a1\"],\"videoAssetIds\":[]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get("/api/scenes/s1/assets").with(user("u1")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.imageAssets[0].id").value("a1"));
    }

    @Test
    void fileEndpoints_requireAuth_andReturnContent() throws Exception {
        when(fileStorageService.saveUploadedImage(any()))
            .thenReturn("/api/files/images/x.png");
        when(fileStorageService.readImage("x.png")).thenReturn(new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47});
        when(fileStorageService.readVideo("v.mp4")).thenReturn(new byte[]{1, 2, 3});
        when(fileStorageService.readAudio("a.mp3")).thenReturn(null);

        MockMultipartFile f = new MockMultipartFile("file", "x.png", "image/png", new byte[]{1});
        mockMvc.perform(multipart("/api/files/upload").file(f))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(multipart("/api/files/upload").file(f).with(user("u1")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.url").value("/api/files/images/x.png"));
        mockMvc.perform(get("/api/files/images/x.png").with(user("u1")))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/files/videos/v.mp4").with(user("u1")))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/files/audios/a.mp3").with(user("u1")))
            .andExpect(status().isNotFound());
    }

    @Test
    void aiEndpoints_requireAuth_andReturnProtocolContract() throws Exception {
        mockMvc.perform(post("/api/ai/generate-script")
                .contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized());

        when(scriptGenerationService.generateAndSaveScenes(
                anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Map.of("projectId", "p1", "sceneCount", 5));
        when(imageGenerationService.generateImage(
                anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn("/api/files/images/gen.png");
        when(videoGenerationService.createVideoTask(
                anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn("task-123");
        when(videoGenerationService.pollVideoTask(anyString()))
            .thenReturn(Map.of("taskId", "task-123", "status", "processing", "progress", "0"));
        when(gatewayModelService.fetchModels(anyString())).thenReturn(List.of());

        mockMvc.perform(post("/api/ai/generate-script").with(user("u1"))
                .contentType("application/json").content("{\"projectId\":\"p1\",\"scriptText\":\"内容\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.sceneCount").value(5));
        mockMvc.perform(post("/api/ai/generate-image").with(user("u1"))
                .contentType("application/json").content("{\"sceneId\":\"s1\",\"prompt\":\"图\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.imageUrl").value("/api/files/images/gen.png"));
        mockMvc.perform(post("/api/ai/generate-video").with(user("u1"))
                .contentType("application/json").content("{\"sceneId\":\"s1\",\"prompt\":\"视频\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.taskId").value("task-123"));
        mockMvc.perform(get("/api/ai/task/task-123").with(user("u1")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("processing"));
        mockMvc.perform(get("/api/ai/models").with(user("u1")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.imageModels").isArray());
    }

    @Test
    void internalEndpoints_requireSecret() throws Exception {
        mockMvc.perform(get("/api/internal/projects/p1"))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/internal/projects/p1").header(AUTH, "wrong"))
            .andExpect(status().isForbidden());

        Project project = new Project();
        project.setId("p1");
        SceneFull scene = new SceneFull("s1", "p1", null, 1, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null);
        SceneAsset sceneAsset = new SceneAsset();
        sceneAsset.setId("sa1");
        when(internalApiService.getProject(anyString())).thenReturn(project);
        when(internalApiService.getProjectScenes(anyString())).thenReturn(List.of(scene));
        when(internalApiService.getProjectAssets(anyString())).thenReturn(List.of());
        when(internalApiService.getSceneAssets(anyString())).thenReturn(List.of(sceneAsset));
        when(internalApiService.getScene(anyString())).thenReturn(scene);
        when(internalApiService.getSceneByVideoTaskId(anyString())).thenReturn(scene);

        mockMvc.perform(get("/api/internal/projects/p1").header(AUTH, SECRET))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("p1"));
        mockMvc.perform(get("/api/internal/projects/p1/scenes").header(AUTH, SECRET))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/internal/scenes/batch").header(AUTH, SECRET)
                .contentType("application/json").content("[]"))
            .andExpect(status().isOk());
        mockMvc.perform(delete("/api/internal/scenes/project/p1").header(AUTH, SECRET))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/internal/scenes/s1/assets").header(AUTH, SECRET))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/internal/scenes/s1/assets").header(AUTH, SECRET)
                .contentType("application/json").content("[\"a1\"]"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/internal/scenes/s1").header(AUTH, SECRET))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("s1"));
        mockMvc.perform(put("/api/internal/scenes/s1").header(AUTH, SECRET)
                .contentType("application/json").content("{\"id\":\"s1\"}"))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/internal/scenes/project/p1/params").header(AUTH, SECRET)
                .contentType("application/json").content("{\"imageModel\":\"gpt-image-2\"}"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/internal/projects/p1/assets").header(AUTH, SECRET))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/internal/scenes/by-video-task/task-123").header(AUTH, SECRET))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("s1"));
    }

    @Test
    void projectOwnershipEndpoint_returnsReadOnlyProjectOwner() throws Exception {
        when(internalApiService.getProjectOwnership("p1"))
                .thenReturn(new ProjectOwnership("p1", "u1", true));

        mockMvc.perform(get("/api/internal/projects/p1/ownership").header(AUTH, SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projectId").value("p1"))
                .andExpect(jsonPath("$.data.userId").value("u1"))
                .andExpect(jsonPath("$.data.exists").value(true));
    }
}
