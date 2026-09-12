package com.culture.mapper;

import com.culture.entity.MailConfig;
import com.culture.entity.MailLog;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 邮件配置/日志 Mapper（sys_mail_config / sys_mail_log）。
 */
@Mapper
public interface MailConfigMapper {

    /** 读取当前生效的邮件配置（仅一条） */
    @Select("select * from sys_mail_config order by id limit 1")
    MailConfig getConfig();

    /** 更新配置（密码字段由调用方决定是否覆盖） */
    @Update("update sys_mail_config set host=#{host}, port=#{port}, username=#{username}, " +
            "password=#{password}, from_name=#{fromName}, subject_template=#{subjectTemplate}, " +
            "body_template=#{bodyTemplate}, daily_limit=#{dailyLimit}, updated_at=now() where id=#{id}")
    int updateConfig(MailConfig c);

    /** 今日已发送 +1 */
    @Update("update sys_mail_config set sent_today=sent_today+1 where id=#{id}")
    int incrementSent(@Param("id") Long id);

    /** 跨天时重置今日计数 */
    @Update("update sys_mail_config set sent_today=0, sent_date=#{date} where id=#{id}")
    int resetSent(@Param("id") Long id, @Param("date") java.sql.Date date);

    /** 写发送日志 */
    @Insert("insert into sys_mail_log(to_email, scene, subject, status, error_msg) " +
            "values(#{toEmail},#{scene},#{subject},#{status},#{errorMsg})")
    int insertLog(MailLog log);

    /** 最近发送日志 */
    @Select("select * from sys_mail_log order by id desc limit #{limit}")
    List<MailLog> recentLogs(@Param("limit") int limit);

    /** 今日成功/失败数 */
    @Select("select count(*) from sys_mail_log where status=#{status} and created_at >= #{start}")
    Long countByStatus(@Param("status") int status, @Param("start") java.util.Date start);
}
