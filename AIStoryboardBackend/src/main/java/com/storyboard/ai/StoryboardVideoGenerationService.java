package com.storyboard.ai;

import com.moon.moonagent.ai.AiConfigProperties;
import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.VideoGenerationService;
import com.moon.moonagent.ai.video.ReferenceImage;
import com.storyboard.common.generation.GenerationRecord;
import com.storyboard.common.generation.GenerationRecordTypes;
import com.storyboard.common.generation.GenerationRecordUpdate;
import com.storyboard.common.generation.GenerationRecordWriter;
import com.storyboard.common.generation.GenerationReference;
import com.storyboard.entity.Project;
import com.storyboard.entity.Scene;
import com.storyboard.entity.ScenePrompt;
import com.storyboard.mapper.ProjectMapper;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.mapper.ScenePromptMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主后端生视频门面：生成/轮询全部委托 agent-core，仅保留生成记录与 taskId 关联。
 */
@Primary
@Service
public class StoryboardVideoGenerationService implements VideoGenerationService {

    private static final Logger log = LoggerFactory.getLogger(StoryboardVideoGenerationService.class);
    private static final int MAX_RECORDED_REF_URL_LENGTH = 8192;

    private final VideoGenerationService delegate;
    private final AiConfigProperties config;
    private final GatewayModelService gatewayModelService;
    private final SceneMapper sceneMapper;
    private final ScenePromptMapper scenePromptMapper;
    private final ProjectMapper projectMapper;
    private final GenerationRecordWriter generationRecordWriter;

    public StoryboardVideoGenerationService(
            @Qualifier("agentCoreVideoGenerationService") VideoGenerationService delegate,
            AiConfigProperties config,
            GatewayModelService gatewayModelService,
            SceneMapper sceneMapper,
            ScenePromptMapper scenePromptMapper,
            ProjectMapper projectMapper,
            GenerationRecordWriter generationRecordWriter) {
        this.delegate = delegate;
        this.config = config;
        this.gatewayModelService = gatewayModelService;
        this.sceneMapper = sceneMapper;
        this.scenePromptMapper = scenePromptMapper;
        this.projectMapper = projectMapper;
        this.generationRecordWriter = generationRecordWriter;
    }

    @Override
    public String createVideoTask(String sceneId, String prompt, String alias,
                                  String resolution, String size, String aspectRatio,
                                  Integer duration, String negativePrompt, Long seed,
                                  List<String> referenceImages, String generatedImageUrl) {
        return createVideoTask(sceneId, prompt, alias, resolution, size, aspectRatio,
                duration, negativePrompt, seed, referenceImages, generatedImageUrl, null, null);
    }

    @Override
    public String createVideoTask(String sceneId, String prompt, String alias,
                                  String resolution, String size, String aspectRatio,
                                  Integer duration, String negativePrompt, Long seed,
                                  List<String> referenceImages, String generatedImageUrl,
                                  List<String> referenceVideos, List<String> referenceAudios) {
        Scene scene = sceneId == null ? null : sceneMapper.selectById(sceneId);
        ScenePrompt scenePrompt = scene == null ? null : scenePromptMapper.selectById(sceneId);
        Project project = scene == null ? null : projectMapper.selectById(scene.getProjectId());
        String actualModel = resolveModel(alias, scenePrompt);
        String recordId = createRecord(
                scene, project, prompt, actualModel,
                videoParams(resolution, size, aspectRatio, duration, negativePrompt),
                videoReferences(referenceImages, generatedImageUrl, referenceVideos, referenceAudios));
        long startMs = System.currentTimeMillis();

        try {
            String taskId = delegate.createVideoTask(
                    sceneId, prompt, alias, resolution, size, aspectRatio, duration,
                    negativePrompt, seed, referenceImages, generatedImageUrl,
                    referenceVideos, referenceAudios);
            attachTaskId(recordId, taskId);
            return taskId;
        } catch (RuntimeException e) {
            updateRecord(recordId, GenerationRecordUpdate.failed(
                    e.getMessage(), System.currentTimeMillis() - startMs));
            throw e;
        }
    }

    @Override
    public String createVideoTask(String sceneId, String prompt, String alias,
                                  String resolution, String size, String aspectRatio,
                                  Integer duration, String negativePrompt, Long seed,
                                  List<ReferenceImage> referenceImages, String generatedImageUrl,
                                  List<String> referenceVideos, List<String> referenceAudios,
                                  boolean isReferenceImageWithRole) {
        Scene scene = sceneId == null ? null : sceneMapper.selectById(sceneId);
        ScenePrompt scenePrompt = scene == null ? null : scenePromptMapper.selectById(sceneId);
        Project project = scene == null ? null : projectMapper.selectById(scene.getProjectId());
        String actualModel = resolveModel(alias, scenePrompt);
        String recordId = createRecord(
                scene, project, prompt, actualModel,
                videoParams(resolution, size, aspectRatio, duration, negativePrompt),
                roleReferences(referenceImages, generatedImageUrl, referenceVideos, referenceAudios));
        long startMs = System.currentTimeMillis();

        try {
            String taskId = delegate.createVideoTask(
                    sceneId, prompt, alias, resolution, size, aspectRatio, duration,
                    negativePrompt, seed, referenceImages, generatedImageUrl,
                    referenceVideos, referenceAudios, isReferenceImageWithRole);
            attachTaskId(recordId, taskId);
            return taskId;
        } catch (RuntimeException e) {
            updateRecord(recordId, GenerationRecordUpdate.failed(
                    e.getMessage(), System.currentTimeMillis() - startMs));
            throw e;
        }
    }

    @Override
    public Map<String, String> pollVideoTask(String taskId) {
        return delegate.pollVideoTask(taskId);
    }

    @Override
    public Map<String, String> cancelVideoTask(String taskId) {
        return delegate.cancelVideoTask(taskId);
    }

    private String resolveModel(String alias, ScenePrompt prompt) {
        if (alias != null) return config.getVideoModelAliasMap().getOrDefault(alias, alias);
        if (prompt != null && prompt.getVideoModel() != null && !prompt.getVideoModel().isBlank()) {
            return prompt.getVideoModel();
        }
        return gatewayModelService.getDefaultVideoModel();
    }

    private String createRecord(Scene scene, Project project, String prompt, String model,
                                Map<String, Object> params, List<GenerationReference> references) {
        if (scene == null || project == null) return null;
        try {
            return generationRecordWriter.create(new GenerationRecord(
                    project.getUserId(), project.getId(), null, scene.getId(),
                    GenerationRecordTypes.VIDEO, GenerationRecordTypes.STORYBOARD_VIDEO,
                    prompt, model, null, null, List.of(), references, params,
                    GenerationRecordTypes.QUEUED, null, null, OffsetDateTime.now()));
        } catch (Exception e) {
            log.warn("视频生成记录落库失败(不影响生成): sceneId={}, error={}", scene.getId(), e.getMessage());
            return null;
        }
    }

    private void attachTaskId(String recordId, String taskId) {
        if (recordId == null) return;
        try {
            generationRecordWriter.attachTaskId(recordId, taskId);
        } catch (Exception e) {
            log.warn("视频生成记录关联 taskId 失败(不影响生成): recordId={}, taskId={}, error={}",
                    recordId, taskId, e.getMessage());
        }
    }

    private void updateRecord(String recordId, GenerationRecordUpdate update) {
        if (recordId == null) return;
        try {
            generationRecordWriter.update(recordId, update);
        } catch (Exception e) {
            log.warn("视频生成记录更新失败(不影响生成): recordId={}, error={}", recordId, e.getMessage());
        }
    }

    private static Map<String, Object> videoParams(String resolution, String size, String aspectRatio,
                                                   Integer duration, String negativePrompt) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (resolution != null && !resolution.isBlank()) params.put("resolution", resolution);
        if (size != null && !size.isBlank()) params.put("size", size);
        if (aspectRatio != null && !aspectRatio.isBlank()) params.put("aspectRatio", aspectRatio);
        if (duration != null) params.put("duration", duration);
        if (negativePrompt != null && !negativePrompt.isBlank()) params.put("negativePrompt", negativePrompt);
        return params;
    }

    private static List<GenerationReference> videoReferences(List<String> images, String source,
                                                             List<String> videos, List<String> audios) {
        List<GenerationReference> refs = new ArrayList<>();
        addReferences(refs, "image", images, "REFERENCE");
        if (source != null && !source.isBlank()) {
            refs.add(new GenerationReference("image", safeRecordUrl(source), "source"));
        }
        addReferences(refs, "video", videos, "REFERENCE");
        addReferences(refs, "audio", audios, "REFERENCE");
        return refs;
    }

    private static List<GenerationReference> roleReferences(List<ReferenceImage> images, String source,
                                                            List<String> videos, List<String> audios) {
        List<GenerationReference> refs = new ArrayList<>();
        if (images != null) {
            for (ReferenceImage image : images) {
                if (image == null || image.url() == null || image.url().isBlank()) continue;
                String role = image.role() == null ? "REFERENCE" : image.role().name();
                refs.add(new GenerationReference("image", safeRecordUrl(image.url()), role));
            }
        }
        if (source != null && !source.isBlank()) {
            refs.add(new GenerationReference("image", safeRecordUrl(source), "source"));
        }
        addReferences(refs, "video", videos, "REFERENCE");
        addReferences(refs, "audio", audios, "REFERENCE");
        return refs;
    }

    private static void addReferences(List<GenerationReference> target, String type,
                                      List<String> urls, String role) {
        if (urls == null) return;
        for (String url : urls) {
            if (url == null || url.isBlank()) continue;
            target.add(new GenerationReference(type, safeRecordUrl(url), role));
        }
    }

    private static String safeRecordUrl(String url) {
        return url.length() > MAX_RECORDED_REF_URL_LENGTH ? null : url;
    }
}
