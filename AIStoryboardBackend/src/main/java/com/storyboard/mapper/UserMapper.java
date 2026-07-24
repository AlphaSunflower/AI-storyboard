package com.storyboard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyboard.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    @Select("SELECT * FROM public.users WHERE status = 'enabled' AND email = #{email}")
    User findByEmail(@Param("email") String email);

    @Update("UPDATE public.users SET last_login_at = #{now} WHERE id = #{id}")
    int updateLastLoginAt(@Param("id") String id, @Param("now") LocalDateTime now);
}
