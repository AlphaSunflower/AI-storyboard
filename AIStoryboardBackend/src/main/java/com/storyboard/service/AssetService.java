package com.storyboard.service;

import com.storyboard.dto.request.AssetCreateRequest;
import com.storyboard.dto.request.AssetUpdateRequest;
import com.storyboard.dto.response.AssetImageVO;
import com.storyboard.dto.response.AssetVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * AI 资产库服务：人物/道具/场景资产的 CRUD、图片上传、分镜关联，以及生成注入辅助。
 */
public interface AssetService {

    /** 创建资产（projectId 空 = 全局资产库）。 */
    AssetVO create(String userId, AssetCreateRequest request);

    /** 列表：项目资产（project_id=指定项目）∪ 用户全局资产（project_id IS NULL），可按 type 过滤。 */
    List<AssetVO> list(String userId, String projectId, String type);

    /** 更新资产（改名/改文字约束）。 */
    AssetVO update(String userId, String assetId, AssetUpdateRequest request);

    /** 删除资产（DB 级联删图 + scene_assets 关联）。 */
    void delete(String userId, String assetId);

    /** 上传资产图片（存 uploads/images，落 asset_images；主图 = 首张，即 sort_order 最小）。 */
    AssetImageVO uploadImage(String userId, String assetId, MultipartFile file);

    /** 删除资产图片。 */
    void deleteImage(String userId, String assetId, String imageId);

    /** 覆盖式设置分镜关联的资产。 */
    void setSceneAssets(String userId, String sceneId, List<String> assetIds);

    /** 查询分镜关联的资产（含图）。 */
    List<AssetVO> listSceneAssets(String userId, String sceneId);

    // ─────────── 生成注入辅助（供分镜/视频/图片生成服务复用，内部解析归属） ───────────

    /** 项目的可用资产（项目资产 + 用户全局资产），无归属校验，供分镜脚本生成用。 */
    List<AssetVO> projectAssets(String projectId);

    /** 分镜关联的资产（含图），无归属校验，供视频/图片生成用。 */
    List<AssetVO> sceneAssets(String sceneId);

    /** 拼「设定集」文字块（空列表返回空串），注入分镜脚本 system prompt 与视频/图片 prompt。 */
    String buildSheetText(List<AssetVO> assets);

    /** 资产参考图列表：每资产取主图 1 张，按 character > prop > scene 优先级，累计 ≤ 9（H3 硬上限）。 */
    List<String> buildReferenceImages(List<AssetVO> assets);
}
