package com.culture.service.impl;

import com.culture.auth.service.BusinessException;
import com.culture.entity.Comment;
import com.culture.mapper.CommentMapper;
import com.culture.query.CommentQuery;
import com.culture.service.CommentService;
import com.culture.entity.User;
import com.culture.service.SensitiveWordService;
import com.culture.service.UserService;
import com.culture.util.HtmlSanitizer;
import com.culture.util.PageList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 评论服务实现。
 *
 * <p>安全要点（UGC 先审后发）：</p>
 * <ul>
 *   <li>content 入库前必须 {@link HtmlSanitizer#sanitize(String, int)} 白名单清洗（1000 字上限），
 *       否则任何访问者都能提交 &lt;script&gt; / onerror= 形成存储型 XSS；</li>
 *   <li>nickname 只取纯文本并限 20 字；email 格式非法直接置空；ip / UA 截断保存；</li>
 *   <li>敏感词：提交前对<b>清洗后的纯文本</b>过一遍启用词表（{@link SensitiveWordService}），
 *       命中 action=1 直接拒绝（400 且不入库），命中 action=2 正常入库但强制 status=0 并给出温和提示；
 *       词表为空/查询异常一律降级放行，不影响提交；</li>
 *   <li>提交频率：Redis key comment:limit:{ip}，60 秒内只允许 1 条（Redis 异常时放行，不影响主流程）。</li>
 * </ul>
 */
@Service
public class CommentServiceImpl implements CommentService {

    /** 正文清洗后最大长度 */
    private static final int MAX_CONTENT_LEN = 1000;
    /** 昵称最大长度 */
    private static final int MAX_NICKNAME_LEN = 20;
    /** 邮箱最大长度（与 biz_comment.email varchar(120) 对齐） */
    private static final int MAX_EMAIL_LEN = 120;
    /** IP 最大长度（与 ip varchar(64) 对齐） */
    private static final int MAX_IP_LEN = 64;
    /** UA 最大长度（与 user_agent varchar(255) 对齐） */
    private static final int MAX_UA_LEN = 255;
    /** 同一 IP 提交间隔（秒） */
    private static final int LIMIT_SECONDS = 60;
    /** 单次批量操作上限：避免 in (...) 过长导致 SQL 解析开销过大/超包 */
    private static final int MAX_BATCH_SIZE = 500;

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    @Autowired
    private CommentMapper commentMapper;

    /** 用于按登录用户回填昵称/邮箱（评论必须登录后提交，署名以服务端为准） */
    @Autowired
    private UserService userService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 敏感词（评论提交链路 + /api/admin/comment/recheck）。
     * 词表走 SensitiveWordServiceImpl 的进程内缓存，异常时 matchAction 自己降级放行，
     * 因此这里不需要 try/catch。
     */
    @Autowired
    private SensitiveWordService sensitiveWordService;

    @Override
    public Map<String, Object> submit(Comment comment, String ip, String userAgent) {
        if (comment == null || comment.getCultureId() == null) {
            throw new BusinessException("缺少所属文化");
        }

        // ---- 1) 正文：白名单清洗（丢弃 script/onerror/style 等）并截断 ----
        String safeContent = HtmlSanitizer.sanitize(comment.getContent(), MAX_CONTENT_LEN);
        if (HtmlSanitizer.toPlainText(safeContent).isEmpty()) {
            throw new BusinessException("评论内容不能为空");
        }

        // ---- 2) 身份：必须已登录；昵称/邮箱以服务端用户资料为准，不信任前端传值 ----
        if (comment.getUserId() == null) {
            throw new BusinessException("请先登录后再留言");
        }
        User author = userService.findById(comment.getUserId());
        if (author == null || (author.getDeleted() != null && author.getDeleted() == 1)) {
            throw new BusinessException("登录状态无效，请重新登录");
        }
        String nickname = HtmlSanitizer.toPlainText(
                author.getNickname() != null && !author.getNickname().trim().isEmpty()
                        ? author.getNickname() : author.getUsername());
        if (nickname.length() > MAX_NICKNAME_LEN) {
            nickname = nickname.substring(0, MAX_NICKNAME_LEN);
        }

        // ---- 3) 邮箱：取用户资料里的邮箱（不公开），格式不合法则置空 ----
        String email = author.getEmail() == null ? "" : author.getEmail().trim();
        if (email.length() > MAX_EMAIL_LEN || !EMAIL_PATTERN.matcher(email).matches()) {
            email = null;
        }

        // ---- 4) 敏感词：对清洗后的纯文本做大小写不敏感包含匹配 ----
        // 放在限流之前：命中 action=1 直接拒绝时，不占用用户 60 秒的提交额度；
        // 命中 action=2 仍然正常入库（评论本来就是先审后发 status=0），只是多给前端一个温和提示。
        int sensitiveAction = sensitiveWordService.matchAction(HtmlSanitizer.toPlainText(safeContent));
        if (sensitiveAction == SensitiveWordService.ACTION_REJECT) {
            throw new BusinessException("内容包含不允许的词汇，请修改后再提交");
        }

        // ---- 5) 频率限制：同 IP 60 秒 1 条 ----
        checkRateLimit(ip);

        Comment c = new Comment();
        c.setCultureId(comment.getCultureId());
        c.setParentId(comment.getParentId() == null ? 0L : comment.getParentId());
        c.setUserId(comment.getUserId());
        c.setNickname(nickname);
        c.setEmail(email);
        c.setContent(safeContent);
        c.setStatus(0);                 // 先审后发；命中 action=2 的敏感词同样落到这里（强制待审）
        c.setIp(truncate(ip, MAX_IP_LEN));
        c.setUserAgent(truncate(userAgent, MAX_UA_LEN));
        c.setDeleted(0);

        commentMapper.insert(c);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", c.getId());
        data.put("status", c.getStatus());
        if (sensitiveAction == SensitiveWordService.ACTION_PENDING) {
            // 命中「转待审核」词：不改变既有成功响应结构（id/status 照旧），只追加一个温和提示字段
            data.put("pendingReason", "内容包含需要人工复核的词汇，已转为待审核，通过后会展示");
        }
        return data;
    }

    /**
     * 频率限制：key comment:limit:{ip}，60 秒内只允许提交 1 条。
     * Redis 不可用时放行（评论是主流程，限频属于保护措施，不能因缓存故障而阻塞用户）。
     */
    private void checkRateLimit(String ip) {
        if (ip == null || ip.isEmpty()) return;
        try {
            String key = "comment:limit:" + ip;
            Boolean first = redisTemplate.opsForValue().setIfAbsent(key, "1", LIMIT_SECONDS, TimeUnit.SECONDS);
            if (!Boolean.TRUE.equals(first)) {
                throw new BusinessException("提交太频繁，请稍后再试");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            System.out.println("[comment] 频率限制不可用（已放行）：" + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> listApproved(Long cultureId) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (cultureId == null) {
            data.put("total", 0L);
            data.put("comments", Collections.emptyList());
            return data;
        }
        List<Comment> all = commentMapper.findApprovedByCulture(cultureId);
        Long total = commentMapper.countApprovedByCulture(cultureId);

        // ---- 组装两级树：parentId=0 为顶层，其余挂到父评论的 children ----
        Map<Long, Comment> index = new LinkedHashMap<>();
        for (Comment c : all) {
            c.setChildren(new ArrayList<Comment>());
            index.put(c.getId(), c);
        }
        List<Comment> roots = new ArrayList<>();
        for (Comment c : all) {
            Long pid = c.getParentId();
            Comment parent = (pid == null || pid == 0L) ? null : index.get(pid);
            if (parent != null) {
                parent.getChildren().add(c);
            } else {
                // 顶层评论，或父评论未通过审核（孤儿回复）——直接展示在顶层，避免丢数据
                roots.add(c);
            }
        }

        data.put("total", total == null ? 0L : total);
        data.put("comments", roots);
        return data;
    }

    @Override
    public PageList adminPage(CommentQuery query) {
        if (query == null) query = new CommentQuery();
        if (query.getPage() == null || query.getPage() < 1) query.setPage(1);
        if (query.getPageSize() == null || query.getPageSize() < 1) query.setPageSize(10);
        query.setOffset((query.getPage() - 1) * query.getPageSize());
        if (query.getKeyword() != null) {
            String kw = query.getKeyword().trim();
            query.setKeyword(kw.isEmpty() ? null : kw);
        }

        PageList pageList = new PageList();
        Long total = commentMapper.queryTotal(query);
        pageList.setTotal(total == null ? 0L : total);
        pageList.setRows(commentMapper.queryData(query));
        return pageList;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void audit(Long id, int status, Long adminId) {
        if (id == null) {
            throw new BusinessException("参数错误");
        }
        if (status != 1 && status != 2) {
            throw new BusinessException("审核状态只能是 1（通过）或 2（拒绝）");
        }
        if (commentMapper.findById(id) == null) {
            throw new BusinessException("评论不存在");
        }
        commentMapper.updateStatus(id, status, adminId, new Date());
    }

    @Override
    public void delete(Long id) {
        if (id == null) {
            throw new BusinessException("参数错误");
        }
        commentMapper.logicalDelete(id);
    }

    @Override
    public long pendingCount() {
        Long n = commentMapper.countByStatus(0);
        return n == null ? 0L : n;
    }

    // ===================== 后台批量操作（增量追加） =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchAudit(List<Long> ids, int status, Long adminId) {
        List<Long> safeIds = normalizeIds(ids);
        if (status != 1 && status != 2) {
            throw new BusinessException("审核状态只能是 1（通过）或 2（拒绝）");
        }
        // 单条 update 走批量 SQL（in 列表），审核人与审核时间统一为当前操作人/当前时间
        return commentMapper.batchUpdateStatus(safeIds, status, adminId, new Date());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchDelete(List<Long> ids) {
        List<Long> safeIds = normalizeIds(ids);
        return commentMapper.batchLogicalDelete(safeIds);
    }

    // ===================== 重新过词表（增量追加） =====================

    /**
     * 重新过词表：只处理传入 id 中「待审核（status=0）且未删除」的评论。
     * 命中 action=1（直接拒绝）的词 → 置为已拒绝（status=2），并记录操作人与时间；
     * 命中 action=2 的不做处理（评论本来就是待审）。
     *
     * <p>词表加载异常时 {@link SensitiveWordService#matchAction(String)} 返回「无命中」，
     * 因此词表故障不会把评论误判为拒绝。</p>
     *
     * @param ids     评论 id 列表；为空/全为 null 或超过 500 条时抛 BusinessException
     * @param adminId 操作人 sys_user.id（写入 audited_by）
     * @return { checked: 实际检查的待审评论数, rejected: 实际置为拒绝的条数 }
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> recheckSensitive(List<Long> ids, Long adminId) {
        List<Long> safeIds = normalizeIds(ids);
        List<Comment> pending = commentMapper.findPendingByIds(safeIds);
        List<Long> rejectIds = new ArrayList<>();
        if (pending != null) {
            for (Comment c : pending) {
                if (c == null || c.getId() == null) continue;
                int action = sensitiveWordService.matchAction(HtmlSanitizer.toPlainText(c.getContent()));
                if (action == SensitiveWordService.ACTION_REJECT) {
                    rejectIds.add(c.getId());
                }
            }
        }
        int rejected = 0;
        if (!rejectIds.isEmpty()) {
            rejected = commentMapper.batchUpdateStatus(rejectIds, 2, adminId, new Date());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("checked", pending == null ? 0 : pending.size());
        data.put("rejected", rejected);
        return data;
    }

    // ===================== 删除可撤销：恢复（增量追加） =====================

    /** 恢复单条评论：只把 deleted 改回 0（审核状态与审核信息不动），返回实际影响行数 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int restore(Long id) {
        if (id == null) {
            throw new BusinessException("参数错误：缺少评论 id");
        }
        return commentMapper.restoreById(id);
    }

    /** 批量恢复评论；ids 为空/全为 null 或超过 500 条时抛 BusinessException */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int restoreBatch(List<Long> ids) {
        return commentMapper.restoreByIds(normalizeIds(ids));
    }

    /**
     * 批量 id 规整：去 null、去重，并校验「非空 + 不超过 500 条」。
     * 校验失败抛 BusinessException，由控制层转成 400 业务错误（而不是 500）。
     */
    private List<Long> normalizeIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException("请至少选择一条评论");
        }
        List<Long> safeIds = new ArrayList<>();
        for (Long id : ids) {
            if (id == null || safeIds.contains(id)) continue;
            safeIds.add(id);
        }
        if (safeIds.isEmpty()) {
            throw new BusinessException("请至少选择一条评论");
        }
        if (safeIds.size() > MAX_BATCH_SIZE) {
            throw new BusinessException("一次最多操作 " + MAX_BATCH_SIZE + " 条评论");
        }
        return safeIds;
    }

    /** 按最大长度截断（null 安全） */
    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
