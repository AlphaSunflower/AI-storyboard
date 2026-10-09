package com.storyboard.ai;

import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.ImageGenerationService;
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
 * 主后端生图门面：生成逻辑全部委托 agent-core，仅保留分镜生成记录语义。
 */
@Primary
@Service
public class StoryboardImageGenerationService implements ImageGenerationService {

    private static final Logger log = LoggerFactory.getLogger(StoryboardImageGenerationService.class);
    private static final int MAX_RECORDED_REF_URL_LENGTH = 8192;

    private final ImageGenerationService delegate;
    private final GatewayModelService gatewayModelService;
    private final SceneMapper sceneMapper;
    private final ScenePromptMapper scenePromptMapper;
    private final ProjectMapper projectMapper;
    private final GenerationRecordWriter generationRecordWriter;

    public StoryboardImageGenerationService(
            @Qualifier("agentCoreImageGenerationService") ImageGenerationService delegate,
            GatewayModelService gatewayModelService,
            SceneMapper sceneMapper,
            ScenePromptMapper scenePromptMapper,
            ProjectMapper projectMapper,
            GenerationRecordWriter generationRecordWriter) {
        this.delegate = delegate;
        this.gatewayModelService = gatewayModelService;
        this.sceneMapper = sceneMapper;
        this.scenePromptMapper = scenePromptMapper;
        this.projectMapper = projectMapper;
        this.generationRecordWriter = generationRecordWriter;
    }

    @Override
    public String generateImage(String sceneId, String prompt, String model,
                                String size, String quality, String aspectRatio,
                                List<String> referenceImages, String mode,
                                String generatedImageUrl) {
        return generateImage(sceneId, prompt, model, size, quality, aspectRatio,
                referenceImages, mode, generatedImageUrl, null);
    }

    @Override
    public String generateImage(String sceneId, String prompt, String model,
                                String size, String quality, String aspectRatio,
                                List<String> referenceImages, String mode,
                                String generatedImageUrl, Integer n) {
        List<String> paths = generateImages(sceneId, prompt, model, size, quality, aspectRatio,
                referenceImages, mode, generatedImageUrl, n);
        return paths.isEmpty() ? null : paths.getFirst();
    }

    @Override
    public List<String> generateImages(String sceneId, String prompt, String model,
                                       String size, String quality, String aspectRatio,
                                       List<String> referenceImages, String mode,
                                       String generatedImageUrl, Integer n) {
        return generateAndRecord(sceneId, prompt, model, size, quality, aspectRatio,
                referenceImages, mode, generatedImageUrl, n, null, Map.of());
    }

    @Override
    public List<String> generateImagesWithMask(String sceneId, String prompt, String model,
                                               String size, String quality, String aspectRatio,
                                               List<String> referenceImages, String generatedImageUrl,
                                               String maskImageUrl, Map<String, Object> editParams) {
        return generateAndRecord(sceneId, prompt, model, size, quality, aspectRatio,
                referenceImages, "edit", generatedImageUrl, null, maskImageUrl, editParams);
    }

    private List<String> generateAndRecord(String sceneId, String prompt, String model,
                                           String size, String quality, String aspectRatio,
                                           List<String> referenceImages, String mode,
                                           String generatedImageUrl, Integer n,
                                           String maskImageUrl, Map<String, Object> editParams) {
        Scene scene = sceneId == null ? null : sceneMapper.selectById(sceneId);
        ScenePrompt scenePrompt = scene == null ? null : scenePromptMapper.selectById(sceneId);
        Project project = scene == null ? null : projectMapper.selectById(scene.getProjectId());
        String effectiveModel = model != null
                ? model
                : scenePrompt != null && scenePrompt.getImageModel() != null && !scenePrompt.getImageModel().isBlank()
                ? scenePrompt.getImageModel()
                : gatewayModelService.getDefaultImageModel();
        long startMs = System.currentTimeMillis();
        String recordId = createRecord(scene, project, prompt, effectiveModel,
                imageParams(size, quality, n, mode, aspectRatio, maskImageUrl),
                imageReferences(referenceImages, generatedImageUrl));

        try {
            List<String> paths = maskImageUrl == null
                    ? delegate.generateImages(sceneId, prompt, model, size, quality, aspectRatio,
                    referenceImages, mode, generatedImageUrl, n)
                    : delegate.generateImagesWithMask(sceneId, prompt, model, size, quality, aspectRatio,
                    referenceImages, generatedImageUrl, maskImageUrl,
                    editParams == null ? Map.of() : editParams);
            updateRecord(recordId, GenerationRecordUpdate.completed(
                    paths.isEmpty() ? null : paths.getFirst(), paths,
                    System.currentTimeMillis() - startMs));
            return paths;
        } catch (RuntimeException e) {
            updateRecord(recordId, GenerationRecordUpdate.failed(
                    e.getMessage(), System.currentTimeMillis() - startMs));
            throw e;
        }
    }

    private String createRecord(Scene scene, Project project, String prompt, String model,
                                Map<String, Object> params, List<GenerationReference> references) {
        if (scene == null || project == null) return null;
        try {
            return generationRecordWriter.create(new GenerationRecord(
                    project.getUserId(), project.getId(), null, scene.getId(),
                    GenerationRecordTypes.IMAGE, GenerationRecordTypes.STORYBOARD_IMAGE,
                    prompt, model, null, null, List.of(), references, params,
                    GenerationRecordTypes.GENERATING, null, null, OffsetDateTime.now()));
        } catch (Exception e) {
            log.warn("图片生成记录落库失败(不影响生成): sceneId={}, error={}", scene.getId(), e.getMessage());
            return null;
        }
    }

    private void updateRecord(String recordId, GenerationRecordUpdate update) {
        if (recordId == null) return;
        try {
            generationRecordWriter.update(recordId, update);
        } catch (Exception e) {
            log.warn("图片生成记录更新失败(不影响生成): recordId={}, error={}", recordId, e.getMessage());
        }
    }

    private static Map<String, Object> imageParams(String size, String quality, Integer n,
                                                   String mode, String aspectRatio, String maskImageUrl) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (size != null && !size.isBlank()) params.put("size", size);
        if (quality != null && !quality.isBlank()) params.put("quality", quality);
        if (n != null) params.put("n", n);
        if (mode != null && !mode.isBlank()) params.put("mode", mode);
        if (aspectRatio != null && !aspectRatio.isBlank()) params.put("aspectRatio", aspectRatio);
        if (maskImageUrl != null && !maskImageUrl.isBlank()) params.put("masked", true);
        return params;
    }

    private static List<GenerationReference> imageReferences(List<String> referenceImages,
                                                             String generatedImageUrl) {
        List<GenerationReference> refs = new ArrayList<>();
        if (referenceImages != null) {
            for (String url : referenceImages) {
                if (url == null || url.isBlank()) continue;
                refs.add(new GenerationReference(
                        GenerationRecordTypes.IMAGE, safeRecordUrl(url), "REFERENCE"));
            }
        }
        if (generatedImageUrl != null && !generatedImageUrl.isBlank()) {
            refs.add(new GenerationReference(
                    GenerationRecordTypes.IMAGE, safeRecordUrl(generatedImageUrl), "source"));
        }
        return refs;
    }

    private static String safeRecordUrl(String url) {
        return url.length() > MAX_RECORDED_REF_URL_LENGTH ? null : url;
    }
}
