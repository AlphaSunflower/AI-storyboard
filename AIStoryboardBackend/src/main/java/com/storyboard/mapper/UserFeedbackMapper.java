package com.storyboard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyboard.entity.UserFeedback;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserFeedbackMapper extends BaseMapper<UserFeedback> {

    /** 反馈收件箱：按时间倒序带出提交人名称/邮箱。 */
    @Select("""
            SELECT f.id, f.user_id, f.content, f.image_urls, f.status, f.created_at,
                   u.email AS user_email, u.display_name AS user_display_name
            FROM public.user_feedback f
            LEFT JOIN public.users u ON u.id = f.user_id
            ORDER BY f.created_at DESC
            LIMIT #{limit}
            """)
    List<UserFeedback> listWithUser(@Param("limit") int limit);
}
