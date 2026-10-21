-- V9: 归档从未使用的 library_ai_tag_suppressions（0 行，全仓库零代码引用）。
-- 保留表结构作为历史记录，不直接 DROP。
ALTER TABLE IF EXISTS asset.library_ai_tag_suppressions
    RENAME TO library_ai_tag_suppressions_archive;
