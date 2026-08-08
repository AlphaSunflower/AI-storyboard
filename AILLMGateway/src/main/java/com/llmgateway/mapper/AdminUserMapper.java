package com.llmgateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmgateway.entity.AdminUser;
import org.apache.ibatis.annotations.Mapper;

/** 管理后台用户表 Mapper */
@Mapper
public interface AdminUserMapper extends BaseMapper<AdminUser> {}
