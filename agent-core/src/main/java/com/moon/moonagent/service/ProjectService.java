package com.moon.moonagent.service;

import com.moon.moonagent.entity.Project;

import java.util.List;

/**
 * 共享项目数据服务——画布(Morrow)与 ai分镜 复用同一张 projects 表。
 * 项目按 (userId, key) 取或建；会话的 project_id 外键依赖此表，项目映射是所有会话同步的前置。
 */
public interface ProjectService {

    /**
     * 按 (userId, key) 取或建项目（key 为跨端一致的项目键，如 Morrow 项目 id 或 ai分镜 项目名）。
     *
     * @param userId 属主
     * @param key    项目键（作为 project.name，保证跨端确定性映射）
     * @return 项目实体（已落库）
     */
    Project createOrGet(String userId, String key);

    /** 新建共享项目（creation_type=morrow，title 为项目名）。 */
    Project create(String userId, String title);

    /** 当前用户的 morrow 项目列表（updated_at 倒序）。 */
    List<Project> listMine(String userId);

    /** 取项目（属主校验，不存在/无权抛 40401）。 */
    Project get(String userId, String id);

    /** 改项目名（属主校验）。 */
    Project rename(String userId, String id, String title);

    /** 删项目（属主校验，依赖 DB 级联删会话/画布）。 */
    void delete(String userId, String id);
}
