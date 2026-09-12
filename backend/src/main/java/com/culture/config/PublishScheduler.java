package com.culture.config;

import com.culture.mapper.CultureMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 定时发布调度器（草稿箱 + 定时发布）。
 *
 * <p><b>做什么</b>：每分钟扫描一次 biz_culture，
 * 把 {@code status=2（定时待发布） and deleted=0 and publish_at <= now()} 的记录批量置为
 * {@code status=1（已发布）}。判断条件全部写在一条 UPDATE 里（见 CultureMapper.publishScheduled），
 * 天然并发安全，不需要「先查 id 再逐条更新」。</p>
 *
 * <p><b>开关</b>：由配置项 {@code app.publish.scheduler.enabled} 控制（默认 true）。</p>
 * <ul>
 *   <li>{@link ConditionalOnProperty} 放在类上：值为 false 时<b>整个 Bean 都不创建</b>，
 *       也就不会注册任何定时任务（比在方法里 if 判断更彻底，连线程都不占）；</li>
 *   <li>{@code matchIfMissing = true}：application.yml 里漏配时按「开启」处理，
 *       避免因为少一行配置导致定时发布静默失效。</li>
 * </ul>
 *
 * <p><b>异常保护</b>：@Scheduled 的 fixedDelay 本身不会因为抛异常而停止后续调度，
 * 但异常会打到日志里；这里再包一层 try/catch，保证「一次失败只跳过本次，
 * 下个周期照常执行」，并且异常绝不会冒泡出去影响调度线程。</p>
 *
 * <p><b>为什么 @EnableScheduling 放在这里而不是启动类</b>：
 * 这样开关一关，连 {@code @EnableScheduling} 一起失效，项目里不会残留一个空的调度基础设施；
 * 同时也避免改动 CultureApplication。</p>
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.publish.scheduler", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class PublishScheduler {

    private static final Logger log = LoggerFactory.getLogger(PublishScheduler.class);

    private final CultureMapper cultureMapper;

    /**
     * 直接注入 Mapper：本任务只允许增量修改 CultureServiceImpl（不新增 CultureService 接口方法），
     * 而这条「到点批量置为已发布」的语句没有业务校验，放在 Mapper 上最直接。
     */
    public PublishScheduler(CultureMapper cultureMapper) {
        this.cultureMapper = cultureMapper;
    }

    /**
     * 定时发布：每 60 秒执行一次（fixedDelay：上一次执行结束后再等 60 秒，避免任务叠加）。
     * initialDelay 也给 60 秒，给应用启动（数据源/连接池就绪）留出时间。
     */
    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void publishScheduledCultures() {
        try {
            int count = cultureMapper.publishScheduled();
            if (count > 0) {
                log.info("[publish-scheduler] 定时发布完成：{} 条内容已置为已发布(status=1)", count);
            }
        } catch (Exception e) {
            // 异常绝不能中断调度：这里吞掉并记日志，下一周期继续扫描
            log.error("[publish-scheduler] 定时发布任务异常（本次跳过，下个周期继续）：{}", e.getMessage(), e);
        }
    }
}
