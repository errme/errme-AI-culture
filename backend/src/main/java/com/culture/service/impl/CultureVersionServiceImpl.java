package com.culture.service.impl;

import com.culture.auth.service.BusinessException;
import com.culture.entity.Culture;
import com.culture.entity.CultureVersion;
import com.culture.entity.User;
import com.culture.mapper.CultureMapper;
import com.culture.mapper.CultureVersionMapper;
import com.culture.mapper.UserMapper;
import com.culture.service.CultureVersionService;
import com.culture.util.CommonUtil;
import com.culture.util.PageList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内容版本历史实现。
 *
 * <p>与主保存流程的关系：{@link #snapshotBeforeSave} 由 CultureServiceImpl.editSaveCulture 在
 * update <b>之前</b>调用，内部 try/catch 吞掉全部异常（只打日志），
 * 因此版本表不存在、写失败等任何问题都不会导致「保存文化」失败。</p>
 *
 * <p>回滚的原子性：{@link #rollback} 带 @Transactional，
 * 「当前内容快照 + 写回 biz_culture」在同一个事务里；任一步失败都整体回滚，
 * 不会出现「快照写了但没回滚」或「回滚了但没快照」的中间态。</p>
 */
@Service
public class CultureVersionServiceImpl implements CultureVersionService {

    private static final Logger log = LoggerFactory.getLogger(CultureVersionServiceImpl.class);

    /** 版本列表默认每页条数（与接口契约一致） */
    private static final int DEFAULT_PAGE_SIZE = 20;
    /** 版本列表每页上限：避免一次把几百条快照的元数据全查出来 */
    private static final int MAX_PAGE_SIZE = 100;

    @Autowired
    private CultureVersionMapper cultureVersionMapper;

    @Autowired
    private CultureMapper cultureMapper;

    @Autowired
    private UserMapper userMapper;

    // ===================== 写快照 =====================

    @Override
    public void snapshotBeforeSave(Long cultureId, Long operatorId, String operatorName) {
        if (cultureId == null) {
            return;   // 新增：没有「修改前」，不写快照
        }
        try {
            // 同一自然分钟内已经写过就跳过（去重）
            if (cultureVersionMapper.countRecentInCurrentMinute(cultureId) > 0) {
                return;
            }
            // 用详情查询读「修改前」的整条记录（含 longtext 正文），此时 update 还没执行
            Culture before = cultureMapper.findDetailById(cultureId);
            if (before == null) {
                return;   // 记录不存在或已逻辑删除：没有可快照的内容
            }
            insertSnapshot(before, operatorId, operatorName);
        } catch (Exception e) {
            // 硬性要求：版本留痕失败绝不影响主保存流程
            log.warn("[culture-version] 保存前快照失败（已忽略，不影响主保存）：cultureId={}, err={}",
                    cultureId, e.getMessage(), e);
        }
    }

    @Override
    public void snapshotBeforeRollback(Culture current, Long operatorId, String operatorName) {
        if (current == null || current.getId() == null) {
            throw new BusinessException("回滚失败：当前内容不存在");
        }
        try {
            // 不做分钟去重：同分钟内保存过也要再写一条，否则回滚不可逆
            insertSnapshot(current, operatorId, operatorName);
        } catch (Exception e) {
            log.error("[culture-version] 回滚前快照写入失败，已放弃本次回滚：cultureId={}, err={}",
                    current.getId(), e.getMessage(), e);
            // 抛业务异常 → 控制层返回 400，且整个回滚事务回滚（不会留下半成品）
            throw new BusinessException("回滚已取消：回滚前快照写入失败（" + e.getMessage() + "）");
        }
    }

    /** 把一条 biz_culture 记录的快照插入版本表（不做任何异常处理，由调用方决定） */
    private void insertSnapshot(Culture c, Long operatorId, String operatorName) {
        CultureVersion v = new CultureVersion();
        v.setCultureId(c.getId());
        v.setName(c.getCultureName());
        v.setDescription(c.getDesc());
        v.setContent(c.getInfo());
        v.setCoverUrl(c.getFmUrl());
        v.setCategoryId(c.getCategoryId());
        v.setOperatorId(operatorId);
        v.setOperatorName(resolveOperatorName(operatorId, operatorName));
        // created_at 交给数据库默认值 CURRENT_TIMESTAMP（与分钟去重用同一套时钟）
        cultureVersionMapper.insert(v);
    }

    /**
     * 操作人用户名：优先用调用方传进来的，其次按 operatorId 反查 sys_user，
     * 再其次用当前 Spring Security 登录态（老的后台 Session 调用路径）。
     * 查不到就返回 null（不阻断快照写入）。
     */
    private String resolveOperatorName(Long operatorId, String operatorName) {
        if (operatorName != null && !operatorName.trim().isEmpty()) {
            return operatorName;
        }
        Long uid = operatorId;
        String username = null;
        try {
            if (uid == null) {
                // 兜底：老的后台 Session 调用路径（JWT 路径由控制层显式传 operatorId）
                User login = CommonUtil.getLoginUser();
                if (login != null) {
                    uid = login.getId();
                    username = login.getUsername();
                }
            }
            if (username == null && uid != null) {
                User u = userMapper.findById(uid);
                if (u != null) {
                    username = u.getUsername();
                }
            }
        } catch (Exception e) {
            log.warn("[culture-version] 查询操作人用户名失败（忽略）：operatorId={}, err={}", uid, e.getMessage());
        }
        return username;
    }

    // ===================== 查询 =====================

    @Override
    public PageList listVersions(Long cultureId, Integer page, Integer pageSize) {
        if (cultureId == null) {
            throw new BusinessException("参数错误：缺少文化 id");
        }
        int p = (page == null || page < 1) ? 1 : page;
        int size = (pageSize == null || pageSize < 1) ? DEFAULT_PAGE_SIZE : pageSize;
        if (size > MAX_PAGE_SIZE) {
            size = MAX_PAGE_SIZE;
        }
        PageList pageList = new PageList();
        Long total = cultureVersionMapper.countByCultureId(cultureId);
        pageList.setTotal(total == null ? 0L : total);
        // 列表不查 content 全文，只带 contentLength
        pageList.setRows(cultureVersionMapper.queryPage(cultureId, (p - 1) * size, size));
        return pageList;
    }

    @Override
    public CultureVersion findVersion(Long id) {
        if (id == null) {
            return null;
        }
        return cultureVersionMapper.findById(id);
    }

    // ===================== 回滚 =====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int rollback(Long versionId, Long operatorId, String operatorName) {
        if (versionId == null) {
            throw new BusinessException("参数错误：缺少版本 id");
        }
        CultureVersion version = cultureVersionMapper.findById(versionId);
        if (version == null) {
            throw new BusinessException("版本不存在或已被清理");
        }
        Long cultureId = version.getCultureId();
        if (cultureId == null) {
            throw new BusinessException("版本数据异常：缺少所属内容 id");
        }
        // 只允许回滚到「仍然存在且未逻辑删除」的内容上
        Culture current = cultureMapper.findDetailById(cultureId);
        if (current == null) {
            throw new BusinessException("内容不存在或已被删除，无法回滚");
        }

        // 关键第一步：先把「当前内容」快照下来，保证本次回滚可逆（这一步失败会抛异常取消回滚）
        snapshotBeforeRollback(current, operatorId, operatorName);

        // 第二步：把版本内容写回 biz_culture（只还原版本表里有的 5 个字段）
        int count = cultureMapper.applyVersion(cultureId, version.getName(), version.getDescription(),
                version.getContent(), version.getCoverUrl(), version.getCategoryId());
        if (count <= 0) {
            // 理论上不会发生（上一步刚查到记录）；真发生了就让事务整体回滚
            throw new BusinessException("回滚失败：内容不存在或已被删除");
        }
        log.info("[culture-version] 回滚完成：cultureId={}, versionId={}, operatorId={}, count={}",
                cultureId, versionId, operatorId, count);
        return count;
    }
}
