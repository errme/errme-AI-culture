<template>
  <div class="container-fluid ad-page">
    <AdminPageHeader
      icon="mdi mdi-image-multiple"
      title="文化管理"
      desc="站点内容的增删改查：支持按名称 / 状态（草稿箱）筛选、批量改分类、批量改状态、批量删除与导出 CSV；行内「版本」可查看历史版本并回滚；新增 / 编辑可选择草稿、立即发布或定时发布。编辑弹窗会先取回正文再允许保存，避免把正文覆盖成空。"
    >
      <template #actions>
        <!-- 说明：#toolbar 只作为老后台「工具条容器」的标记保留（bootstrap-table 的 toolbar 选项并未启用） -->
        <div id="toolbar" class="ad-toolbar">
          <label class="ad-label" for="q_cultureName">文化名称：</label>
          <input id="q_cultureName" name="cultureName" type="text" class="form-control input-sm ad-input"
                 placeholder="输入文化名称">
          <!-- 状态筛选（草稿箱）：选「全部状态」时不传 status，既有筛选行为完全不变 -->
          <label class="ad-label" for="q_status">状态：</label>
          <select id="q_status" v-model="statusFilter" class="form-control input-sm ad-select" @change="search">
            <option value="">全部状态</option>
            <option :value="0">草稿</option>
            <option :value="1">已发布</option>
            <option :value="2">定时待发布</option>
          </select>
          <button id="search" type="button" class="btn btn-primary btn-sm" data-toggle="modal"
                  data-method="search" @click="search">
            <i class="mdi mdi-magnify"></i> 搜索
          </button>
          <button type="button" class="btn btn-primary btn-sm" data-toggle="modal" data-method="add" @click="openAdd">
            <i class="mdi mdi-plus"></i> 添加文化
          </button>
        </div>
      </template>
    </AdminPageHeader>

    <div class="ad-card">
      <div class="ad-card__head">
        <h5 class="ad-card__title"><i class="mdi mdi-format-list-bulleted"></i> 文化列表</h5>
        <!-- 说明：toolbar-btn-action 只作为老回归脚本（tools/ui-check.mjs）的选择器标记保留 -->
        <div class="ad-card__actions toolbar-btn-action">
          <span class="ad-hint">勾选后可批量处理</span>
          <span class="ad-hint">共 {{ total }} 条 · 第 {{ page }} / {{ maxPage }} 页 · 已选 {{ selectedIds.length }} 条</span>
          <button type="button" class="btn btn-default btn-sm" :disabled="loading" @click="reload()">
            <i class="mdi mdi-refresh" :class="{ 'mdi-spin': loading }"></i> 刷新
          </button>
          <select v-model="batchCategoryId" class="form-control input-sm ad-select">
            <option value="">批量改分类…</option>
            <option v-for="category in categories" :key="category.id" :value="category.id">
              {{ category.categoryName }}
            </option>
          </select>
          <button type="button" class="btn btn-info btn-sm" :disabled="!batchCategoryId" @click="batchChangeCategory">
            <i class="mdi mdi-folder-move"></i> 应用分类
          </button>
          <button type="button" class="btn btn-danger btn-sm" @click="batchDelete">
            <i class="mdi mdi-delete"></i> 批量删除
          </button>
          <!-- 批量改状态：后端只有 0=草稿 / 1=已发布 / 2=定时待发布 三态，没有独立的「下架」态，
               因此「下架」等价于「转为草稿」（status=0）；status=2 需要具体时间，批量接口不支持，故不提供 -->
          <button type="button" class="btn btn-success btn-sm" @click="batchSetStatus(1, 'publish')">
            <i class="mdi mdi-send"></i> 批量发布
          </button>
          <button type="button" class="btn btn-warning btn-sm" @click="batchSetStatus(0, 'offline')">
            <i class="mdi mdi-eye-off-outline"></i> 批量下架（转为草稿）
          </button>
          <button type="button" class="btn btn-success btn-sm" @click="exportCsv">
            <i class="mdi mdi-download"></i> 导出 CSV
          </button>
        </div>
      </div>
      <div class="ad-card__body ad-card__body--flush">
        <div class="ad-table-wrap">
          <!--
            表格改为纯 Vue 渲染（与 SentenceManageView / AnnouncementManageView / CategoryManageView /
            UserManageView 同一套写法）：
              · 保留 Bootstrap 3 的表格类（table / table-bordered / table-hover）保证视觉与老后台一致，
                同时叠加全局 ad-table 规范；外层仍是 .ad-table-wrap；
              · 勾选列仍是 thead 里的 input[type=checkbox]（全选）+ 行内 checkbox（v-model="selectedIds"），
                老回归脚本（tools/ui-check.mjs、tools/delete-check.mjs）据此命中；
              · 表头固定、表体 v-for；空状态用 AdminEmpty，加载态用元素级 v-if
                （v-for 直接挂在 <tr> 上，不用 <template v-else>，避免 Vue 生成 Fragment 造成补丁错位）。
          -->
          <table id="cultureTable" class="table table-bordered table-hover ad-table">
            <thead>
              <tr>
                <th style="width: 40px;" class="text-center">
                  <input
                    type="checkbox"
                    :checked="allChecked"
                    :disabled="!displayRows.length"
                    title="全选/取消全选"
                    @change="toggleAll"
                  />
                </th>
                <th style="width: 150px;">文化封面</th>
                <th class="ad-sort" :title="sortTitle('cultureName')" @click="toggleSort('cultureName')">
                  文化名称 <i :class="sortIcon('cultureName')"></i>
                </th>
                <th class="ad-sort" :title="sortTitle('address')" @click="toggleSort('address')">
                  地址 <i :class="sortIcon('address')"></i>
                </th>
                <th>文化描述</th>
                <th>文化介绍</th>
                <th class="ad-sort" :title="sortTitle('categoryName')" @click="toggleSort('categoryName')">
                  分类 <i :class="sortIcon('categoryName')"></i>
                </th>
                <th style="width: 110px;">状态</th>
                <th style="width: 190px;">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="loading">
                <td colspan="9" class="text-center text-muted">加载中…</td>
              </tr>
              <tr v-else-if="!displayRows.length">
                <td colspan="9">
                  <AdminEmpty icon="mdi mdi-image-multiple-outline"
                              text="暂无文化，点右上角「添加文化」创建第一条文化" />
                </td>
              </tr>
              <tr v-for="row in displayRows" :key="row.id">
                <td class="text-center"><input type="checkbox" :value="row.id" v-model="selectedIds" /></td>
                <!-- 封面：原 cultureFmFormatter 拼的 <img class="culture-cover-thumb">，改为 Vue 渲染 -->
                <td class="text-center">
                  <img v-if="coverUrl(row.fmUrl)" class="culture-cover-thumb" :src="coverUrl(row.fmUrl)" alt="文化封面">
                  <span v-else class="text-muted">—</span>
                </td>
                <td>{{ row.cultureName }}</td>
                <td>{{ row.address }}</td>
                <!-- 描述 / 介绍：沿用原 cellPreview(40) / cellPreview(80) 的两行截断（含 title 悬浮全文） -->
                <td v-html="descPreview(row.desc)"></td>
                <td v-html="infoPreview(row.infoSummary)"></td>
                <td>{{ categoryNameOf(row) }}</td>
                <!-- 状态：沿用原 cultureStatusFormatter 的徽章 + 定时时间小字 -->
                <td v-html="cultureStatusFormatter(row.status, row)"></td>
                <td class="ad-actions">
                  <button type="button" class="btn btn-xs btn-default" title="版本历史（可查看与回滚）"
                          @click="showVersions(row.id)">
                    <i class="mdi mdi-history"></i> 版本
                  </button>
                  <button type="button" class="btn btn-xs btn-default" title="编辑" @click="edit(row.id)">
                    <i class="mdi mdi-pencil"></i>
                  </button>
                  <button type="button" class="btn btn-xs btn-default" title="删除" @click="del(row.id)">
                    <i class="mdi mdi-window-close"></i>
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <!-- 分页（自建）：每页条数沿用原 pageList [10,15,20] + 上一页/下一页 + 第 x/y 页 -->
      <div class="ad-card__foot">
        <div class="ad-toolbar">
          <span class="ad-hint">共 {{ total }} 条记录{{ sortKey ? '（排序仅作用于当前页）' : '' }}</span>
          <select v-model.number="pageSize" class="form-control input-sm ad-select" @change="changePageSize">
            <option :value="10">每页 10 条</option>
            <option :value="15">每页 15 条</option>
            <option :value="20">每页 20 条</option>
          </select>
          <span class="ad-toolbar__spacer"></span>
          <ul class="pagination pagination-sm ad-pager">
            <li :class="{ disabled: page <= 1 }">
              <a href="javascript:void(0)" @click="goTo(page - 1)">上一页</a>
            </li>
            <li class="active"><a href="javascript:void(0)">{{ page }} / {{ maxPage }}</a></li>
            <li :class="{ disabled: page >= maxPage }">
              <a href="javascript:void(0)" @click="goTo(page + 1)">下一页</a>
            </li>
          </ul>
        </div>
      </div>
    </div>

    <!-- 修改表单start -->
    <div class="modal fade" id="cultureEditModal" tabindex="-1" role="dialog" aria-labelledby="cultureEditModalLabel">
      <div class="modal-dialog" role="document">
        <div class="modal-content">
          <div class="modal-header">
            <button type="button" class="close" data-dismiss="modal" aria-label="Close">
              <span aria-hidden="true">&times;</span></button>
            <h4 class="modal-title" id="cultureEditModalLabel">编辑文化</h4>
          </div>
          <form id="cultureEditForm" method="post" enctype="multipart/form-data">
            <div class="modal-body">
              <input type="hidden" id="e_id" class="form-control" name="id">

              <p class="ad-section-title">基本信息</p>
              <div class="form-group">
                <label class="control-label">文化名称：<span class="text-danger">*</span></label>
                <input type="text" id="e_cultureName" class="form-control" name="cultureName">
              </div>
              <div class="form-group">
                <label class="control-label">地址：<span class="text-danger">*</span></label>
                <input type="text" id="e_author" class="form-control" name="address">
              </div>
              <div class="form-group">
                <label class="control-label">文化描述：<span class="text-danger">*</span></label>
                <input type="text" class="form-control" name="desc" id="e_publish">
                <p class="ad-help">一句话摘要，用于列表与前台卡片的简介。</p>
              </div>
              <div class="form-group">
                <label class="control-label">文化类型：</label>
                <select class="form-control" id="e_categoryId" name="categoryId" size="1">
                  <option v-for="category in categories" :key="category.id"
                          :value="category.id">{{ category.categoryName }}</option>
                </select>
              </div>
              <div class="form-group">
                <label class="control-label">标签：</label>
                <div>
                  <label v-for="t in allTags" :key="t.id" class="checkbox-inline ad-check-inline">
                    <input type="checkbox" :value="t.id" v-model="editTagIds" /> {{ t.name }}
                  </label>
                  <span v-if="!allTags.length" class="ad-help">还没有标签，请先到「标签管理」新增</span>
                </div>
              </div>

              <p class="ad-section-title">发布设置</p>
              <div class="form-group">
                <label class="control-label">状态：</label>
                <!-- 不写 name，避免被 serializeForm 带进 payload（status/publishAt 单独组装，见 applyPublishFields） -->
                <select id="edit_status" class="form-control" v-model.number="editStatus">
                  <option :value="0">草稿（前台不可见）</option>
                  <option :value="1">立即发布</option>
                  <option :value="2">定时发布</option>
                </select>
              </div>
              <div class="form-group" v-if="editStatus === 2">
                <label class="control-label">定时发布时间：<span class="text-danger">*</span></label>
                <input id="edit_publishAt" type="datetime-local" class="form-control" v-model="editPublishAt">
                <p class="ad-help">东八区时间，必须晚于当前时间；到点后由后台定时任务自动转为「已发布」。</p>
              </div>

              <p class="ad-section-title">文化介绍</p>
              <div class="form-group">
                <label class="control-label">文化介绍（富文本）：<span class="text-danger">*</span></label>
                <input type="hidden" class="form-control" name="info" id="e_infoHidden">
                <p class="ad-help">
                  工具栏可插入图片与视频（可 Ctrl+V 粘贴或拖拽上传）；“&lt;/&gt;”为 HTML 源码，“⛶”为全屏编辑。
                </p>
                <div id="e_info-editor" style="background:#fff;"></div>
              </div>

              <p class="ad-section-title">文化封面</p>
              <!-- 封面：后端 editSaveCulture 不更新封面字段，需保存记录后单独上传 -->
              <div class="form-group">
                <label class="control-label">文化封面：</label>
                <input id="e_file-pic" name="file" type="file" multiple/>
                <p class="ad-help">支持 jpg、jpeg、png、gif、txt、docx、zip、xlsx 格式，大小不限。</p>
              </div>
            </div>
            <div class="modal-footer">
              <button type="button" class="btn btn-default" data-dismiss="modal">取消</button>
              <button type="button" data-method="editSave" class="btn btn-primary" @click="saveEdit">
                保存修改
              </button>
            </div>
          </form>
        </div>
      </div>
    </div>
    <!-- 修改表单end -->

    <!-- 新增表单start（原 admin/cultureAdd.html） -->
    <div class="modal fade" id="cultureAddModal" tabindex="-1" role="dialog" aria-labelledby="cultureAddModalLabel">
      <div class="modal-dialog" role="document">
        <div class="modal-content">
          <div class="modal-header">
            <button type="button" class="close" data-dismiss="modal" aria-label="Close">
              <span aria-hidden="true">&times;</span></button>
            <h4 class="modal-title" id="cultureAddModalLabel">添加文化</h4>
          </div>
          <form id="cultureAddForm" method="post" enctype="multipart/form-data">
            <div class="modal-body">

              <p class="ad-section-title">基本信息</p>
              <div class="form-group">
                <label for="recipient-cultureName" class="control-label">文化名称：<span class="text-danger">*</span></label>
                <input type="text" class="form-control" name="cultureName" id="recipient-cultureName">
              </div>
              <div class="form-group">
                <label for="recipient-address" class="control-label">地址：<span class="text-danger">*</span></label>
                <input type="text" class="form-control" name="address" id="recipient-address">
              </div>
              <div class="form-group">
                <label class="control-label">文化类型：</label>
                <select class="form-control" id="categoryId" name="categoryId" size="1">
                  <option v-for="category in categories" :key="category.id"
                          :value="category.id">{{ category.categoryName }}</option>
                </select>
              </div>
              <div class="form-group">
                <label class="control-label">标签：</label>
                <div>
                  <label v-for="t in allTags" :key="t.id" class="checkbox-inline ad-check-inline">
                    <input type="checkbox" :value="t.id" v-model="addTagIds" /> {{ t.name }}
                  </label>
                  <span v-if="!allTags.length" class="ad-help">还没有标签，请先到「标签管理」新增</span>
                </div>
              </div>
              <div class="form-group">
                <label for="recipient-desc" class="control-label">文化描述：<span class="text-danger">*</span></label>
                <textarea class="form-control" name="desc" id="recipient-desc" rows="2"></textarea>
                <p class="ad-help">一句话摘要，用于列表与前台卡片的简介。</p>
              </div>

              <p class="ad-section-title">发布设置</p>
              <div class="form-group">
                <label class="control-label">状态：</label>
                <!-- 不写 name，避免被 serializeForm 带进 payload（status/publishAt 单独组装，见 applyPublishFields） -->
                <select id="add_status" class="form-control" v-model.number="addStatus">
                  <option :value="0">草稿（前台不可见）</option>
                  <option :value="1">立即发布</option>
                  <option :value="2">定时发布</option>
                </select>
              </div>
              <div class="form-group" v-if="addStatus === 2">
                <label class="control-label">定时发布时间：<span class="text-danger">*</span></label>
                <input id="add_publishAt" type="datetime-local" class="form-control" v-model="addPublishAt">
                <p class="ad-help">东八区时间，必须晚于当前时间；到点后由后台定时任务自动转为「已发布」。</p>
              </div>

              <p class="ad-section-title">文化介绍</p>
              <div class="form-group">
                <label for="recipient-info" class="control-label">文化介绍（富文本）：<span class="text-danger">*</span></label>
                <input type="hidden" name="info" id="recipient-info">
                <p class="ad-help">
                  支持标题 / 字号 / 颜色 / 列表 / 对齐 / 引用 / 链接 / 表格；工具栏可插入图片与视频，
                  也可直接 <b>Ctrl+V 粘贴</b> 或拖拽图片视频上传；“&lt;/&gt;”为 HTML 源码，“⛶”为全屏编辑。
                </p>
                <div id="info-editor" style="background:#fff;"></div>
              </div>

              <p class="ad-section-title">文化封面</p>
              <!-- 封面上传 -->
              <div class="form-group">
                <label class="control-label">文化封面：</label>
                <input id="file-pic" name="file" type="file" multiple/>
                <p class="ad-help">支持 jpg、jpeg、png、gif、txt、docx、zip、xlsx 格式，大小不限。</p>
              </div>

            </div>
            <div class="modal-footer">
              <button type="button" class="btn btn-default" data-method="viewCulture"
                      data-dismiss="modal" @click="viewCulture">查看文化
              </button>
              <button type="button" data-method="save" class="btn btn-primary" @click="saveAdd">保存文化
              </button>
            </div>
          </form>
        </div>
      </div>
    </div>
    <!-- 新增表单结束 -->

    <!--
      版本历史弹窗（自建，刻意不复用 cultureAddModal / cultureEditModal：
      那两个弹窗绑定着富文本编辑器、上传控件与 bootstrapValidator 生命周期，
      塞进列表型内容会互相干扰）。列表只给正文长度，正文要单独调 /culture/version 取。
    -->
    <div class="modal fade" id="cultureVersionModal" tabindex="-1" role="dialog"
         aria-labelledby="cultureVersionModalLabel">
      <div class="modal-dialog modal-lg" role="document">
        <div class="modal-content">
          <div class="modal-header">
            <button type="button" class="close" data-dismiss="modal" aria-label="Close">
              <span aria-hidden="true">&times;</span></button>
            <h4 class="modal-title" id="cultureVersionModalLabel">版本历史</h4>
          </div>
          <div class="modal-body">
            <div class="ad-toolbar">
              <span class="ad-hint">{{ versionTitle }}</span>
              <span class="ad-toolbar__spacer"></span>
              <span class="ad-hint">共 {{ versionTotal }} 条 · 第 {{ versionPage }} / {{ versionMaxPage }} 页</span>
              <button type="button" class="btn btn-default btn-xs" :disabled="versionLoading || versionPage <= 1"
                      @click="goVersionPage(versionPage - 1)">上一页</button>
              <button type="button" class="btn btn-default btn-xs"
                      :disabled="versionLoading || versionPage >= versionMaxPage"
                      @click="goVersionPage(versionPage + 1)">下一页</button>
            </div>
            <p class="ad-help">
              每次保存修改/回滚前都会自动为「修改前的内容」留一条快照；回滚会覆盖当前正文、标题、描述、封面与分类。
            </p>
            <div class="ad-table-wrap">
              <table class="ad-table">
                <thead>
                  <tr>
                    <th style="width:70px;">版本</th>
                    <th style="width:170px;">时间</th>
                    <th style="width:130px;">操作人</th>
                    <th style="width:100px;">正文字数</th>
                    <th>标题</th>
                    <th style="width:190px;">操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-if="versionLoading">
                    <td colspan="6"><AdminEmpty icon="mdi mdi-loading mdi-spin" text="加载中…" /></td>
                  </tr>
                  <tr v-else-if="!versions.length">
                    <td colspan="6">
                      <AdminEmpty icon="mdi mdi-history"
                                  text="还没有历史版本：保存修改或执行回滚时会自动留痕" />
                    </td>
                  </tr>
                  <tr v-for="v in versions" :key="v.id">
                    <td class="ad-num">#{{ v.id }}</td>
                    <td class="ad-num">{{ v.createTime || '-' }}</td>
                    <td>{{ v.operatorName || '系统' }}</td>
                    <td class="ad-num">{{ v.contentLength != null ? v.contentLength : '-' }}</td>
                    <td class="ad-clip" :title="v.name || ''">{{ v.name || '-' }}</td>
                    <td class="ad-actions">
                      <button type="button" class="btn btn-default btn-xs" @click="previewVersion(v)">查看</button>
                      <button type="button" class="btn btn-danger btn-xs" @click="rollbackVersion(v)">回滚到该版本</button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>

            <!-- 正文预览：只读纯文本（后端 content 是富文本，这里剥标签后展示，不做任何渲染） -->
            <div v-if="versionPreview.show" class="version-preview">
              <p class="ad-section-title">
                版本 #{{ versionPreview.id }} 正文预览（纯文本，只读）
              </p>
              <p class="ad-help">
                {{ versionPreview.name || '（无标题）' }} · {{ versionPreview.operatorName }} ·
                {{ versionPreview.createTime }}
              </p>
              <div class="version-preview__text">{{ versionPreview.text }}</div>
            </div>
          </div>
          <div class="modal-footer">
            <button type="button" class="btn btn-default" data-dismiss="modal">关闭</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AdminPageHeader from '@/components/admin/AdminPageHeader.vue'
import AdminEmpty from '@/components/admin/AdminEmpty.vue'
import { categoryList, cultureAdminDetail, cultureBatchCategory, cultureBatchDelete, cultureBatchStatus, cultureDelete, cultureExport, cultureList, cultureRestore, cultureRollback, cultureSave, cultureVersion, cultureVersions, tagBind, tagList, tagOfCulture } from '@/api/admin'
import { coverUrl } from '@/utils/format'
import { loadScript, loadScripts, loadStyle, whenJQuery } from '@/utils/loadScript'
import { toast as dsToast, confirmDelete, confirmDialog } from '@/utils/notify'
import { saveBlob, filenameFromHeaders } from '@/utils/download'
import { LEGACY } from '@/utils/legacyAssets'

/**
 * 文化管理（移植自 Thymeleaf 后台 admin/culture.html + admin/cultureAdd.html）
 *
 * 合并结果：一个表格 + 新增弹窗（cultureAdd.html）+ 编辑弹窗（culture.html）+ 版本历史弹窗。
 * 排版已统一到 src/styles/admin-ui.css 的 ad-* 规范（页头 / 卡片 / 表格容器 / 弹窗分区）；
 * 富文本与封面上传沿用站点原有插件，#cultureTable / #cultureAddModal / #cultureEditModal /
 * #cultureVersionModal / #cultureAddForm / #cultureEditForm / #info-editor / #e_info-editor /
 * #file-pic / #e_file-pic / #q_cultureName / #q_status / #search 与全部 name 保持不变。
 *
 * ★ 列表已从 bootstrap-table 迁移为纯 Vue 渲染（与已完成的 4 个页面同一套写法）★
 *   · 旧实现：$('#cultureTable').bootstrapTable({ ajax, columns, ... }) —— 表头 / 表体 / 分页条 /
 *     列筛选 / 刷新按钮全部由 jQuery 插件运行时拼 HTML，行内操作列用 onclick="edit(id)" 拼串注入。
 *   · 新实现：Vue 自己渲染 <thead>/<tbody> + 自建分页（.ad-card__foot），行内按钮改用 @click，
 *     数据由本组件的 load() 直接调 cultureList；勾选列改为 selectedIds + 表头全选 toggleAll()。
 *   · 与表格插件无关的逻辑（版本历史 / 回滚、批量改分类 / 改状态 / 删除、状态筛选、导出 CSV、
 *     编辑回填的 cultureAdminDetail + editDetailLoaded 保存守卫）行为保持不变。
 */
const router = useRouter()
const route = useRoute()

/** 分类下拉数据源（原模板由服务端 ${categorys} 注入，现走 categoryList 接口） */
const categories = ref([])

/* ===== 原有静态资源（与 Thymeleaf 模板 head/script 中的引用一致） ===== */
const QUILL_CSS = '/static/admin/js/vendor/quill/quill.snow.css'
const EDITOR_CSS = '/static/admin/css/culture-editor.css'
const QUILL_JS = '/static/admin/js/vendor/quill/quill.js'
// 走 legacyAssets 统一维护（带 ?v= 版本号，绕过 1 天静态缓存）
const EDITOR_JS = LEGACY.editorJs
const SERIALIZE_JS = '/static/admin/js/jquery.serialize-object.min.js'

const ADMIN_TOKEN_KEY = 'culture_admin_token'

let disposed = false
/** 新增时后端不回传 id，保存后用列表接口补齐（列表按 id desc，取第一条） */
let addCultureId = null

/** 标签（Batch4）：全部标签 + 新增/编辑弹窗中勾选的标签 id */
const allTags = ref([])
const addTagIds = ref([])
const editTagIds = ref([])
/** 编辑弹窗当前记录 id（封面上传的额外参数） */
let editCultureId = null
/**
 * 正文是否已成功从「后台详情接口」加载。
 * 列表接口为了瘦身不再返回 info 正文，所以编辑弹窗必须单独取详情；
 * 取失败时**禁止保存**，否则会把正文覆盖成空（真实数据丢失）。
 */
let editDetailLoaded = false

/* ==================== 资源加载 ==================== */

async function loadEditorAssets() {
  await loadStyle(QUILL_CSS)
  await loadStyle(EDITOR_CSS)
  // quill.js 必须先于 culture-editor.js
  await loadScripts([QUILL_JS, EDITOR_JS])
  await loadScript(SERIALIZE_JS)
}

/* ==================== 富文本编辑器 ==================== */

function getAddEditor() {
  return window.infoEditor || null
}

function getEditEditor() {
  return window.editEditor || null
}

/** 确保编辑器资源（quill.js / culture-editor.js / serialize-object.js）已加载
 *  幂等：已加载则立即返回；loadEditorAssets 内部对已注入的脚本/CSS 会去重。 */
async function ensureEditorAssets() {
  if (window.CultureEditor) return
  try {
    await loadEditorAssets()
  } catch (e) {
    if (window.console) console.warn('[culture] 编辑器资源加载失败：' + ((e && e.message) || e))
  }
}

/** 新增弹窗编辑器（懒初始化：弹窗打开后创建） */
function ensureAddEditor() {
  if (!window.CultureEditor) return null
  if (!window.infoEditor) {
    window.infoEditor = window.CultureEditor.create('#info-editor', {
      name: 'infoEditor',
      height: 420,
      placeholder: '请输入文化介绍正文…',
      draftKey: ADD_DRAFT_KEY   // 草稿自动保存（Batch4/C10）
    })
  }
  return window.infoEditor
}

/** 编辑弹窗编辑器（懒初始化：弹窗打开后创建） */
function ensureEditEditor(id) {
  if (!window.CultureEditor) return null
  const key = editDraftKey(id)
  // create 对同名实例直接返回缓存，所以草稿 key 变化（换了记录）时必须先销毁重建
  if (window.editEditor && window.editEditor.draftKey !== key) {
    try { window.CultureEditor.destroy('editEditor') } catch (e) { /* 忽略 */ }
    delete window.editEditor
  }
  if (!window.editEditor) {
    window.editEditor = window.CultureEditor.create('#e_info-editor', {
      name: 'editEditor',
      height: 360,
      placeholder: '请输入文化介绍正文…',
      draftKey: key
    })
  }
  return window.editEditor
}

/** 新增/编辑草稿的 localStorage key */
const ADD_DRAFT_KEY = 'culture-add'
function editDraftKey(id) { return 'culture-edit-' + id }

/** 清理草稿（保存成功后调用；CultureEditor 未就绪时静默忽略） */
function clearDraftSafe(key) {
  try { if (window.CultureEditor && window.CultureEditor.clearDraft) window.CultureEditor.clearDraft(key) } catch (e) { /* 忽略 */ }
}

/** 有未保存草稿时询问是否恢复（不自动覆盖当前内容） */
function maybeRestoreDraft(editor, key) {
  if (!editor || !window.CultureEditor || !window.CultureEditor.loadDraft) return
  let draft = null
  try { draft = window.CultureEditor.loadDraft(key) } catch (e) { return }
  if (!draft || !draft.html) return
  const when = draft.savedAt ? new Date(draft.savedAt).toLocaleString() : '上次'
  confirmDialog({
    title: '发现未保存的草稿',
    content: `编辑器中存在 ${when} 自动保存的草稿，是否恢复？`,
    detail: '选择「不用了」会保留草稿，下次打开仍会询问。',
    confirmText: '恢复草稿',
    cancelText: '不用了'
  }).then(ok => { if (ok) editor.setHTML(draft.html) })
}

function destroyEditors() {
  if (window.CultureEditor) {
    if (getAddEditor()) window.CultureEditor.destroy('infoEditor')
    if (getEditEditor()) window.CultureEditor.destroy('editEditor')
  }
  delete window.infoEditor
  delete window.editEditor
}

/* ==================== 通用弹窗 / 提示 ==================== */

function successTip(content) {
  dsToast.success(content)
}

function failTip(content) {
  dsToast.error(content)
}

function hideModal(selector) {
  const $modal = window.$(selector)
  if ($modal.length) $modal.modal('hide')
}

/* ==================== 表单 ==================== */

/** 优先用 jquery.serialize-object 插件（与原模板一致），缺失时退化为手工序列化 */
function serializeForm($form) {
  if (typeof window.$.fn.serializeObject === 'function') return $form.serializeObject()
  const data = {}
  $form.serializeArray().forEach(item => { data[item.name] = item.value })
  return data
}

/** 整理成 Culture 字段（id/cultureName/address/desc/info/categoryId） */
function normalizeCulture(form) {
  const data = {
    cultureName: form.cultureName,
    address: form.address,
    desc: form.desc,
    info: form.info
  }
  if (form.categoryId !== undefined && form.categoryId !== null && form.categoryId !== '') {
    data.categoryId = Number(form.categoryId)
  }
  if (form.id !== undefined && form.id !== null && form.id !== '') {
    data.id = Number(form.id)
  }
  return data
}

/* ==================== 状态 / 定时发布（Batch6） ==================== */

/**
 * 列表状态筛选：'' = 全部（不传 status）、0 草稿、1 已发布、2 定时待发布。
 * 用 ref 而不是读 DOM：bootstrap-table 的 ajax 回调里直接取值，语义更清楚。
 */
const statusFilter = ref('')

/** 新增弹窗的发布设置（默认立即发布，与后端列默认值一致） */
const addStatus = ref(1)
const addPublishAt = ref('')
/** 编辑弹窗的发布设置（由 /culture/detail 回填） */
const editStatus = ref(1)
const editPublishAt = ref('')

/** datetime-local 的值（YYYY-MM-DDTHH:mm[:ss]）→ 后端要求的 'yyyy-MM-dd HH:mm:ss'（东八区） */
function toPublishAt(value) {
  const s = String(value == null ? '' : value).trim().replace('T', ' ')
  if (!s) return ''
  if (s.length === 16) return s + ':00'
  return s.length > 19 ? s.slice(0, 19) : s
}

/** 后端的 'yyyy-MM-dd HH:mm:ss'（或 ISO 串）→ datetime-local 需要的 'YYYY-MM-DDTHH:mm' */
function toDatetimeLocal(value) {
  const s = String(value == null ? '' : value).trim()
  if (!s) return ''
  const m = s.match(/^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2})/)
  return m ? m[1] + 'T' + m[2] : ''
}

/**
 * 程序化修改表单控件后显式同步 DOM。
 * 原因：openAdd/edit 里会先 form.reset() 把控件复位，而 v-model 绑定的值「没变化」时
 * Vue 不会再 patch DOM，容易出现「界面显示草稿、提交的却是已发布」的不一致。
 */
function syncControl(id, value) {
  const el = document.getElementById(id)
  if (el) el.value = value == null ? '' : String(value)
}

/**
 * 组装 status / publishAt 到 payload。
 * status=2（定时发布）时前端先校验一次（必填 + 必须晚于当前时间），后端仍会再校验一遍。
 * @returns {boolean} 校验通过返回 true，不通过已给出提示并返回 false
 */
function applyPublishFields(payload, status, localValue) {
  if (status === 2) {
    const at = toPublishAt(localValue)
    if (!at) {
      failTip('选择「定时发布」时必须填写定时发布时间')
      return false
    }
    const t = new Date(at.replace(' ', 'T')).getTime()
    if (isNaN(t) || t <= Date.now()) {
      failTip('定时发布时间必须晚于当前时间')
      return false
    }
    payload.status = 2
    payload.publishAt = at
    return true
  }
  // 0=草稿 1=已发布；不传 publishAt（后端「取不到就保持原值」）
  payload.status = status === 0 ? 0 : 1
  return true
}

/** 新增后取新记录 id：/api/admin/culture/save 不回传 id，按名称+分类取最新一条（列表按 id desc） */
async function resolveCultureId(data) {
  try {
    const res = await cultureList({
      page: 1,
      pageSize: 1,
      cultureName: data.cultureName,
      categoryId: data.categoryId
    })
    const rows = (res && res.rows) || []
    return rows.length ? rows[0].id : null
  } catch (e) {
    return null
  }
}

/* ==================== 表格（纯 Vue 渲染） ==================== */

/**
 * 列表已从 bootstrap-table 迁移为纯 Vue 渲染（与 SentenceManageView / AnnouncementManageView /
 * CategoryManageView / UserManageView 同一套写法）。
 *
 * · 旧实现：$('#cultureTable').bootstrapTable({ ajax, columns, pagination ... }) ——
 *   表头 / 表体 / 分页条 / 列筛选 / 刷新按钮全部由 jQuery 插件运行时拼 HTML 字符串生成，
 *   行内操作列用 onclick="edit(id)" 拼串注入（依赖 window.edit / window.del），
 *   批量操作靠 $('#cultureTable').bootstrapTable('getSelections') 反查勾选行。
 * · 新实现：Vue 自己渲染 <thead> + <tr v-for> + 自建分页（放在 .ad-card__foot）；
 *   行内按钮改成 @click（optFormatter / cultureFmFormatter 已删除）；
 *   勾选列改为 v-model="selectedIds" + 表头全选 toggleAll()，批量操作直接读 selectedIds。
 * · 对外可见的一切保持不变：
 *   #cultureTable / #q_cultureName / #q_status / #search / #toolbar / .toolbar-btn-action /
 *   .ad-table-wrap / .ad-card* 全部保留；弹窗与表单字段（#cultureAddModal / #cultureEditModal /
 *   #cultureVersionModal / #cultureAddForm / #cultureEditForm / #e_* / #file-pic / #e_file-pic）
 *   以及全部 name 属性原样不动；增删改查走同一套 web/src/api/admin.js 接口，payload 结构未变；
 *   成功 / 失败提示仍走 dsToast / confirmDelete / confirmDialog（以及 $.confirm 兼容层）。
 * · 「状态」列与「描述 / 介绍」列沿用原来的 cultureStatusFormatter / cellPreview 输出
 *   （模板里用 v-html 渲染），保证徽章 class、文案与 40 / 80 字截断长度与迁移前逐字一致。
 * · 排序：默认沿用后端顺序（列表 mapper 固定 order by id desc），点表头只对当前页做前端排序
 *   （表头 title 与卡片脚已注明）——与原插件服务端分页下点表头不改变取数结果的行为等价。
 * · 仍然使用 jQuery 的地方（有意保留，都不属于「表格插件」）：bootstrap 3 弹窗（modal）、
 *   bootstrapValidator 表单校验、fileinput 封面上传、$.confirm 兼容层。
 * · 版本历史弹窗、批量改状态、状态筛选、导出 CSV、编辑回填（cultureAdminDetail + editDetailLoaded
 *   保存守卫）等与表格插件无关的逻辑保持行为不变。
 */

/** 当前页数据（顺序 = 后端返回顺序：id 倒序） */
const rows = ref([])
/** 总条数（后端返回） */
const total = ref(0)
/** 当前页码（从 1 开始） */
const page = ref(1)
/** 每页条数（原 bootstrap-table pageList: [10, 15, 20]） */
const pageSize = ref(10)
/** 加载中（首屏即为 true，避免闪一下空状态） */
const loading = ref(true)
/** 勾选列选中的行 id（替代旧的 $('#cultureTable').bootstrapTable('getSelections')） */
const selectedIds = ref([])
/** 排序字段：'' = 沿用后端默认顺序（id 倒序），其余 = 前端排序（仅作用于当前页） */
const sortKey = ref('')
/** 排序方向：'asc' | 'desc' */
const sortDir = ref('desc')

const maxPage = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

/**
 * 「状态」列（草稿箱体验）：把 status 渲染成徽章，让草稿 / 定时内容在后台列表里一眼可辨。
 *
 * 0=草稿、1=已发布、2=定时待发布（与 biz_culture.status 一致）；
 * status=2 时在同一格用小字补一行定时发布时间 publishAt（未设置时显示「未设置时间」并用 title 说明）。
 * 其它 / 空值统一显示「—」。
 * 数据来源：/api/admin/culture/list 已改走 queryAdminPage，额外返回 status / publishAt
 * （前台接口 /api/culture/list 不返回这两个字段，故本列只对后台有意义）。
 */
function cultureStatusFormatter(value, row) {
  // 兼容后端可能下发字符串（'' / null / undefined 一律按「无状态」处理，避免 Number(null)=0 被误判成草稿）
  const status = (value === null || value === undefined || value === '') ? null : Number(value)
  if (status === 1) return '<span class="ad-badge ad-badge--success">已发布</span>'
  if (status === 0) return '<span class="ad-badge">草稿</span>'
  if (status === 2) {
    const at = row && row.publishAt ? String(row.publishAt) : ''
    const tip = at ? '定时发布时间：' + at : '未设置定时发布时间（publish_at 为空，调度器不会自动发布）'
    return '<span class="ad-badge ad-badge--warning">定时</span>' +
      '<div class="ad-hint culture-publish-at" title="' + htmlEscape(tip) + '">' +
      htmlEscape(at || '未设置时间') + '</div>'
  }
  return '<span class="text-muted">—</span>'
}

/**
 * 列表单元格预览：内容过长时只显示 max 个字符 + 两行截断（ellipsis），悬浮查看前 200 字，
 * 避免「介绍一多就把表格撑破」。
 * 返回值仍是 HTML 串（与迁移前的 formatter 输出逐字一致），模板里用 v-html 渲染。
 */
function htmlEscape(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;')
}

/** 富文本/纯文本统一转成一行纯文本（列表不渲染 HTML） */
function toPlainText(value) {
  return String(value == null ? '' : value)
    .replace(/<[^>]*>/g, ' ')
    // 后端摘要是 LEFT(content,300)，可能正好把 HTML 标签切断（末尾残留 "<img src=... ），
    // 这里再把末尾未闭合的标签碎片去掉，避免界面上出现半截标签
    .replace(/<[^>]*$/, ' ')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/\s+/g, ' ')
    .trim()
}

function cellPreview(max) {
  return (value) => {
    const text = toPlainText(value)
    if (!text) return '<span class="text-muted">—</span>'
    const short = text.length > max ? text.slice(0, max) + '…' : text
    const tip = text.length > max ? text.slice(0, 200) + (text.length > 200 ? '…' : '') : text
    // 两行截断的样式收敛到 .culture-cell-preview（v-html 渲染的 DOM 拿不到 scoped 属性，故为全局样式）
    return '<div class="culture-cell-preview" title="' + htmlEscape(tip) + '">' + htmlEscape(short) + '</div>'
  }
}

/** 文化描述列：沿用原来的 cellPreview(40)（含末尾省略号与两行截断） */
const descPreview = cellPreview(40)
/** 文化介绍列：沿用原来的 cellPreview(80)（列表接口只回摘要 infoSummary） */
const infoPreview = cellPreview(80)

/** 分类列：原 bootstrap-table 的 field 为 category.categoryName（嵌套字段，单独取） */
function categoryNameOf(row) {
  return (row && row.category && row.category.categoryName) || ''
}

/** 从当前页数据里按 id 找行（替代旧的 bootstrapTable('getRowByUniqueId')） */
function findRow(id) {
  return rows.value.find(r => String(r.id) === String(id)) || null
}

/** 表头文案（排序 tip 用） */
const SORT_LABEL = { cultureName: '文化名称', address: '地址', categoryName: '分类' }

/** id 数值比较（缺失按 0 处理，避免 NaN 让 sort 结果不稳定） */
function numId(v) {
  const n = Number(v)
  return isFinite(n) ? n : 0
}

/** 排序取值（分类是嵌套字段，单独取；其余按列 field 取） */
function sortValue(key, row) {
  if (key === 'categoryName') return categoryNameOf(row)
  const v = row ? row[key] : ''
  return String(v == null ? '' : v)
}

/**
 * 显示行 = 当前页数据 + 可选的前端排序。
 * 注意：排序只作用于「当前页」——旧实现是服务端分页（sidePagination: 'server'），
 * 后端列表 mapper 也没有动态 order by，点表头同样只影响当页，因此行为等价。
 */
const displayRows = computed(() => {
  const list = rows.value
  if (!sortKey.value) return list
  const dir = sortDir.value === 'asc' ? 1 : -1
  const key = sortKey.value
  return list.slice().sort((a, b) => {
    let ret = sortValue(key, a).localeCompare(sortValue(key, b), 'zh-Hans-CN')
    // 完全相同时退回 id 倒序，保证顺序稳定
    if (ret === 0) ret = numId(b.id) - numId(a.id)
    return ret * dir
  })
})

/** 勾选列是否全选（只在当前页范围内判断） */
const allChecked = computed(() => displayRows.value.length > 0 && selectedIds.value.length === displayRows.value.length)

/**
 * 点表头切换排序：未点过任何表头时保持后端默认顺序（order by id desc，sortKey=''）；
 * 首次点某个表头先按「正序」（相对默认的 id 倒序一定有可见变化，不会出现「点了没反应」），
 * 再点同一个表头切换为倒序。排序只作用于当前页（表头 title 已注明）。
 */
function toggleSort(key) {
  if (sortKey.value !== key) {
    sortKey.value = key
    sortDir.value = 'asc'
    return
  }
  sortDir.value = sortDir.value === 'asc' ? 'desc' : 'asc'
}

function sortIcon(key) {
  if (sortKey.value !== key) return 'mdi mdi-sort-variant'
  return sortDir.value === 'asc' ? 'mdi mdi-sort-ascending' : 'mdi mdi-sort-descending'
}

/** 表头 tip：明确「点表头排序」以及「排序只作用于当前页」（避免被误认为全量排序） */
function sortTitle(key) {
  const label = SORT_LABEL[key] || '该列'
  const tip = '（排序仅作用于当前页）'
  return sortKey.value !== key
    ? `点击按${label}排序${tip}`
    : `再点一次切换为${sortDir.value === 'asc' ? '倒序' : '正序'}${tip}`
}

/** 全选 / 取消全选（与其它已迁移页面同一套写法） */
function toggleAll(e) {
  selectedIds.value = e.target.checked ? displayRows.value.map(r => r.id) : []
}

/* ==================== 列表加载 ==================== */

/**
 * 拉取当前页（替代旧 bootstrap-table 的 ajax 选项）。
 * 查询条件仍沿用原实现：直接读 #q_cultureName / #q_categoryId 的值 + statusFilter；
 * 失败时清空列表并提示（旧实现的 ajax error 分支只 console.warn、不告诉用户原因）。
 */
async function load() {
  loading.value = true
  let data = null
  try {
    const query = { page: page.value, pageSize: pageSize.value }
    const $ = window.$
    if ($ && $.fn) {
      const cultureName = $('#q_cultureName').val()
      if (cultureName) query.cultureName = cultureName
      const categoryId = $('#q_categoryId').val()
      if (categoryId) query.categoryId = categoryId
    }
    // 状态筛选（草稿箱）：只在下拉选了具体状态时才带上 status
    if (statusFilter.value !== '' && statusFilter.value != null) query.status = statusFilter.value
    data = await cultureList(query)
  } catch (err) {
    if (disposed) return
    rows.value = []
    total.value = 0
    selectedIds.value = []
    loading.value = false
    dsToast.error((err && err.message) || '文化列表加载失败')
    return
  }
  if (disposed) return

  rows.value = (data && Array.isArray(data.rows)) ? data.rows : []
  total.value = Number((data && data.total) || 0)
  // 勾选只保留仍在当前页的记录，避免勾选态被带到已经翻走 / 已删除的数据上
  const alive = new Set(rows.value.map(r => String(r.id)))
  selectedIds.value = selectedIds.value.filter(id => alive.has(String(id)))

  // 删掉末页最后一条后自动回到有效页，不停在空白页
  const target = Math.min(page.value, maxPage.value)
  if (!rows.value.length && target < page.value) {
    page.value = target
    return load()
  }
  loading.value = false
}

/** 刷新列表：原 $("#cultureTable").bootstrapTable('refresh') 的等价实现 */
function refreshTable() {
  if (disposed) return
  load()
}

/** 回到第一页并刷新（点「刷新」按钮的入口） */
function reload() {
  page.value = 1
  return load()
}

/** 翻页（越界直接忽略） */
function goTo(p) {
  if (p < 1 || p > maxPage.value || p === page.value) return
  page.value = p
  load()
}

/** 切换每页条数：回到第一页重新拉取（与旧 bootstrap-table 的 pageSize 行为一致） */
function changePageSize() {
  page.value = 1
  load()
}

/* ==================== 批量操作与导出（Batch4） ==================== */

/** 批量改分类的目标分类（空串表示未选择） */
const batchCategoryId = ref('')

/**
 * 当前勾选的行（替代旧的 $('#cultureTable').bootstrapTable('getSelections')）。
 * 用 String(id) 比对，兼容后端 id 为数字、checkbox 值为字符串的场景；
 * 顺序 = 当前显示顺序（displayRows），与旧插件返回的顺序语义一致。
 */
function selectedRows() {
  const ids = new Set(selectedIds.value.map(String))
  return displayRows.value.filter(r => ids.has(String(r.id)))
}

function batchDelete() {
  const rows = selectedRows()
  if (!rows.length) { dsToast.warning('请先勾选要删除的文化'); return }
  confirmDelete({
    target: `选中的 ${rows.length} 条文化`,
    extra: '正文、封面图与标签关联会一并删除，前台立即无法访问，且不可恢复。',
    onConfirm: async () => {
      const ids = rows.map(r => r.id)
      const res = await cultureBatchDelete({ ids })
      refreshTable()
      dsToast.withUndo(`已删除 ${(res && res.count) || ids.length} 条文化`, async () => {
        await cultureRestore({ ids })
        refreshTable()
        dsToast.success('已恢复 ' + ids.length + ' 条文化')
      })
    }
  })
}

function batchChangeCategory() {
  const rows = selectedRows()
  if (!rows.length) { dsToast.warning('请先勾选要修改的文化'); return }
  if (!batchCategoryId.value) return
  const name = (categories.value.find(c => String(c.id) === String(batchCategoryId.value)) || {}).categoryName || '目标分类'
  confirmDialog({
    title: '批量修改分类',
    content: `把选中的 ${rows.length} 条文化改为「${name}」？`,
    detail: '只影响分类归属，不改动正文与封面。',
    confirmText: '确认修改',
    onConfirm: async () => {
      const res = await cultureBatchCategory({ ids: rows.map(r => r.id), categoryId: Number(batchCategoryId.value) })
      dsToast.success(`已修改 ${(res && res.count) || rows.length} 条文化的分类`)
      batchCategoryId.value = ''
      refreshTable()
    }
  })
}

/**
 * 批量改状态（Batch6）：0=草稿（含「下架」语义，前台不可见）、1=已发布。
 * mode 只影响文案：'draft' = 设为草稿、'publish' = 发布、'offline' = 下架（同样落到 status=0）。
 */
function batchSetStatus(status, mode) {
  const rows = selectedRows()
  if (!rows.length) { dsToast.warning('请先勾选要处理的文化'); return }
  const ids = rows.map(r => r.id)
  const copy = mode === 'publish'
    ? {
        title: '批量发布',
        content: `把选中的 ${rows.length} 条文化设为「已发布」？`,
        detail: '发布后前台立即可见；定时待发布的内容也会被直接改为已发布。',
        danger: false,
        confirmText: '确认发布'
      }
    : mode === 'offline'
      ? {
          title: '批量下架（转为草稿）',
          content: `把选中的 ${rows.length} 条文化下架，转为「草稿」？`,
          detail: '下架等价于转为草稿：前台列表与详情不再显示，内容、封面与标签都保留，可随时重新发布。',
          danger: true,
          confirmText: '确认下架'
        }
      : {
          title: '批量设为草稿',
          content: `把选中的 ${rows.length} 条文化设为「草稿」？`,
          detail: '草稿不会出现在前台；内容、封面与标签都保留，可随时重新发布。',
          danger: true,
          confirmText: '确认设为草稿'
        }
  confirmDialog({
    ...copy,
    onConfirm: async () => {
      try {
        const res = await cultureBatchStatus({ ids, status })
        if (disposed) return
        const count = (res && res.count) || ids.length
        dsToast.success(mode === 'publish' ? `已发布 ${count} 条文化` : `已把 ${count} 条文化转为草稿`)
        refreshTable()
      } catch (e) {
        if (disposed) return
        dsToast.error((e && e.message) || '批量修改状态失败')
      }
    }
  })
}

/* ==================== 版本历史 / 回滚（Batch6） ==================== */

/** 版本列表（不含正文，只有 contentLength） */
const versions = ref([])
const versionTotal = ref(0)
const versionPage = ref(1)
const VERSION_PAGE_SIZE = 10
const versionLoading = ref(false)
const versionTitle = ref('')
/** 当前查看的文化 id（弹窗打开期间固定） */
const versionCultureId = ref(null)
/** 正文预览（点「查看」后才有内容；纯文本，只读） */
const versionPreview = ref({ show: false, id: null, name: '', operatorName: '', createTime: '', text: '' })

const versionMaxPage = computed(() => Math.max(1, Math.ceil(versionTotal.value / VERSION_PAGE_SIZE)))

/** 富文本 → 保留换行的纯文本（版本预览只读展示，不渲染任何 HTML） */
function toPlainLines(value) {
  return String(value == null ? '' : value)
    .replace(/<\s*br\s*\/?>/gi, '\n')
    .replace(/<\/(p|div|li|h[1-6]|tr|blockquote|section)>/gi, '\n')
    .replace(/<[^>]*>/g, '')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&quot;/gi, '"')
    .replace(/&#39;/gi, "'")
    .replace(/&amp;/gi, '&')
    .replace(/[ \t]+\n/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

function resetVersionPreview() {
  versionPreview.value = { show: false, id: null, name: '', operatorName: '', createTime: '', text: '' }
}

/** 打开版本历史弹窗（行内「版本」按钮 / window.showVersions 调用） */
function showVersions(id) {
  const row = findRow(id)
  versionCultureId.value = id
  versionTitle.value = '《' + ((row && row.cultureName) || ('#' + id)) + '》的历史版本'
  versionPage.value = 1
  versions.value = []
  versionTotal.value = 0
  resetVersionPreview()
  window.$('#cultureVersionModal').modal({ show: true, backdrop: 'static' })
  loadVersions()
}

async function loadVersions() {
  const id = versionCultureId.value
  if (!id) return
  versionLoading.value = true
  try {
    const data = await cultureVersions({ id, page: versionPage.value, pageSize: VERSION_PAGE_SIZE })
    if (disposed) return
    versions.value = (data && data.rows) || []
    versionTotal.value = (data && data.total) || 0
  } catch (e) {
    if (disposed) return
    versions.value = []
    versionTotal.value = 0
    dsToast.error((e && e.message) || '版本历史加载失败')
  } finally {
    if (!disposed) versionLoading.value = false
  }
}

function goVersionPage(p) {
  if (p < 1 || p > versionMaxPage.value) return
  versionPage.value = p
  resetVersionPreview()
  loadVersions()
}

/** 查看某个版本的正文（列表接口不含正文，这里单独取全文） */
async function previewVersion(v) {
  try {
    const detail = await cultureVersion(v.id)
    if (disposed) return
    versionPreview.value = {
      show: true,
      id: v.id,
      name: (detail && detail.name) || v.name || '',
      operatorName: (detail && detail.operatorName) || v.operatorName || '系统',
      createTime: (detail && detail.createTime) || v.createTime || '',
      text: toPlainLines(detail && detail.content) || '（该版本正文为空）'
    }
  } catch (e) {
    if (disposed) return
    dsToast.error((e && e.message) || '版本正文加载失败')
  }
}

/** 回滚到指定版本（危险操作：会覆盖当前正文/标题/描述/封面/分类） */
function rollbackVersion(v) {
  confirmDialog({
    title: '回滚到该版本',
    content: `把${versionTitle.value.replace(/的历史版本$/, '')}的内容回滚到版本 #${v.id}（${v.createTime || '-'}）？`,
    detail: '回滚会覆盖当前的正文、标题、描述、封面与分类；回滚前系统会自动为「当前内容」留一条快照，因此回滚本身可以再滚回来。',
    danger: true,
    confirmText: '确认回滚',
    onConfirm: async () => {
      try {
        await cultureRollback(v.id)
        if (disposed) return
        refreshTable()
        dsToast.success('已回滚')
        hideModal('#cultureVersionModal')
      } catch (e) {
        if (disposed) return
        dsToast.error((e && e.message) || '回滚失败')
      }
    }
  })
}

/** 导出当前筛选条件下的 CSV（带鉴权头，走 blob 下载） */
async function exportCsv() {
  try {
    const params = {}
    const name = window.$('#q_cultureName').val()
    if (name) params.cultureName = name
    const cid = window.$('#q_categoryId').val()
    if (cid && String(cid) !== '-1') params.categoryId = cid
    // 导出与列表筛选保持一致（含状态筛选）
    if (statusFilter.value !== '' && statusFilter.value != null) params.status = statusFilter.value
    const res = await cultureExport(params)
    // http 拦截器对 blob 请求不解包（返回完整响应，便于读 Content-Disposition）；
    // 这里再兜一层，兼容返回 Blob 或 { data: Blob } 的情况
    const blob = res instanceof Blob ? res : (res && res.data)
    if (!(blob instanceof Blob)) throw new Error('导出响应格式不正确')
    const headers = (res && res.headers) || {}
    saveBlob(blob, filenameFromHeaders(headers, 'cultures.csv'))
    dsToast.success('CSV 已开始下载')
  } catch (e) {
    dsToast.error((e && e.message) || '导出失败')
  }
}

/* ==================== 搜索 ==================== */

/** 搜索 / 切换状态筛选：回到第一页重新拉取（原 bootstrapTable('refresh', { pageNumber: 1 }) 的等价实现） */
function search() {
  page.value = 1
  load()
}

/* ==================== 新增 ==================== */

/** 原模板「查看文化」按钮（原跳 /culture/index?id=5，现统一走干净路由 /admin/culture） */
function viewCulture() {
  hideModal('#cultureAddModal')
  router.push('/admin/culture')
}

async function loadAllTags() {
  try {
    allTags.value = (await tagList()) || []
  } catch (e) {
    allTags.value = []
  }
}

async function openAdd() {
  // 首次进入页面时编辑器资源是异步加载的：若用户抢在加载完成前点击，
  // 直接创建编辑器会静默失败（弹窗里没有富文本区域），这里先确保资源就绪。
  await ensureEditorAssets()
  addTagIds.value = []
  window.$('#cultureAddModal').modal({
    show: true,
    backdrop: 'static'
  })
  //重置表单
  const form = document.getElementById('cultureAddForm')
  if (form) form.reset()
  const validator = window.$('#cultureAddForm').data('bootstrapValidator')
  if (validator) validator.resetForm()
  // 发布设置回到默认（立即发布、无定时时间）。必须放在 form.reset() 之后：
  // reset 会把 select 复位成第一个选项，而 v-model 值没变化时 Vue 不会再 patch DOM，
  // 所以这里显式同步一次 DOM，保证「界面显示的」和「提交的」一致（见 syncControl）。
  addStatus.value = 1
  addPublishAt.value = ''
  syncControl('add_status', 1)
  syncControl('add_publishAt', '')
  // 上传控件由 initUploads() 异步初始化：用户抢在初始化前点击时这里会抛错，
  // 一旦抛错后面的编辑器就不会创建，因此单独兜底
  try {
    window.$('#file-pic').fileinput('clear')
  } catch (e) { /* 上传控件尚未初始化，忽略 */ }
  addCultureId = null
  const editor = ensureAddEditor()
  if (editor) {
    editor.setHTML('')
    maybeRestoreDraft(editor, ADD_DRAFT_KEY)
  }
}

async function saveAdd() {
  //提交表单：先同步富文本内容到隐藏 input（name=info）
  const $form = window.$('#cultureAddForm')
  const editor = getAddEditor()
  window.$('#recipient-info').val(editor ? editor.getHTML() : '')

  const validator = $form.data('bootstrapValidator')
  if (validator) {
    validator.validate()
    if (!validator.isValid()) return
  }

  const payload = normalizeCulture(serializeForm($form))
  // 状态 / 定时发布时间（status=2 时前端先校验：必填 + 必须晚于当前时间）
  if (!applyPublishFields(payload, addStatus.value, addPublishAt.value)) return
  try {
    await cultureSave(payload)
  } catch (e) {
    failTip(e.message || '保存失败')
    return
  }
  // 记录已落库：清掉草稿
  clearDraftSafe(ADD_DRAFT_KEY)
  // 新增接口不回传 id，按名称回查（mapper 为 name LIKE + id desc，rows[0] 即新记录）
  if (addTagIds.value.length) {
    try {
      const found = await cultureList({ page: 1, pageSize: 1, cultureName: payload.cultureName })
      const rows = (found && found.rows) || []
      if (rows.length) await tagBind(rows[0].id, addTagIds.value)
    } catch (e) {
      console.warn('[CultureManage] 标签绑定失败：', e.message)
    }
  }

  // 不上传图片时，不触发 bootstrap 上传插件的初始化方法，仅将表单内容提交
  if (!hasSelectedFile('#file-pic')) {
    hideModal('#cultureAddModal')
    refreshTable()
    successTip(payload.status === 0 ? '已保存为草稿' : payload.status === 2 ? '已设置定时发布' : '保存成功')
    return
  }

  // 选了封面：先保存记录拿到 id，再单独上传封面
  const id = payload.id || await resolveCultureId(payload)
  if (!id) {
    hideModal('#cultureAddModal')
    refreshTable()
    failTip('保存成功，但未获取到记录 ID，封面未上传')
    return
  }
  addCultureId = id
  window.$('#file-pic').fileinput('upload') //触发插件开始上传。
}

/* ==================== 编辑 ==================== */

async function edit(id) {
  // 同 openAdd：编辑弹窗的富文本也需要资源就绪
  await ensureEditorAssets()
  const editRow = findRow(id)//行的数据
  if (!editRow) return
  window.$('#cultureEditModal').modal({
    show: true,
    backdrop: 'static'
  })
  //重置表单
  const form = document.getElementById('cultureEditForm')
  if (form) form.reset()
  const validator = window.$('#cultureEditForm').data('bootstrapValidator')
  if (validator) validator.resetForm()
  // 发布设置的默认值（详情回来后再覆盖）；同 openAdd，必须在 reset 之后同步 DOM
  editStatus.value = 1
  editPublishAt.value = ''
  syncControl('edit_status', 1)
  syncControl('edit_publishAt', '')
  window.$('#e_id').val(editRow.id)
  window.$('#e_cultureName').val(editRow.cultureName)
  window.$('#e_author').val(editRow.address)
  window.$('#e_publish').val(editRow.desc)
  // 富文本回填（懒初始化：CultureEditor = 本地 Quill 2 封装）
  // ⚠️ 列表接口不再返回 info 正文（瘦身），必须用后台详情接口取全文；
  //    取不到时置 editDetailLoaded=false，saveEdit 会拒绝保存，避免把正文写成空。
  const editor = ensureEditEditor(editRow.id)
  editDetailLoaded = false
  if (editor) editor.setHTML('')
  try {
    const detail = await cultureAdminDetail(editRow.id)
    if (editor) editor.setHTML((detail && detail.info) || '')
    // 回显状态 / 定时发布时间（只有详情接口返回这两个字段）
    editStatus.value = detail && detail.status != null ? detail.status : 1
    editPublishAt.value = toDatetimeLocal(detail && detail.publishAt)
    syncControl('edit_status', editStatus.value)
    syncControl('edit_publishAt', editPublishAt.value)
    editDetailLoaded = true
  } catch (e) {
    dsToast.error('正文加载失败，已阻止保存以免覆盖正文', { detail: (e && e.message) || '请关闭弹窗后重试' })
  }
  if (editor) maybeRestoreDraft(editor, editDraftKey(editRow.id))
  window.$('#e_categoryId').val(editRow.category ? editRow.category.id : '')
  // 回显标签（Batch4）
  editTagIds.value = []
  try {
    const bound = await tagOfCulture(editRow.id)
    editTagIds.value = (bound || []).map(t => t.id)
  } catch (e) { /* 忽略 */ }
  try {
    window.$('#e_file-pic').fileinput('clear')
  } catch (e) { /* 上传控件尚未初始化，忽略 */ }
  editCultureId = editRow.id
}

async function saveEdit() {
  // 数据丢失保护：正文没成功加载时不允许保存（否则会用空正文覆盖原内容）
  if (!editDetailLoaded) {
    failTip('正文尚未加载成功，为避免覆盖原文已阻止保存；请关闭弹窗后重试')
    return
  }
  //提交表单：先同步富文本内容到隐藏 input（name=info）
  const $form = window.$('#cultureEditForm')
  const editor = getEditEditor()
  window.$('#e_infoHidden').val(editor ? editor.getHTML() : '')

  const validator = $form.data('bootstrapValidator')
  if (validator) {
    validator.validate()
    if (!validator.isValid()) return
  }

  const payload = normalizeCulture(serializeForm($form))
  if (!payload.id) {
    failTip('缺少记录 ID，无法修改')
    return
  }
  // 状态 / 定时发布时间（status=2 时前端先校验：必填 + 必须晚于当前时间）
  if (!applyPublishFields(payload, editStatus.value, editPublishAt.value)) return
  try {
    await cultureSave(payload)
    await tagBind(payload.id, editTagIds.value)
  } catch (e) {
    failTip(e.message || '修改失败')
    return
  }
  // 记录已落库：清掉该记录的草稿
  clearDraftSafe(editDraftKey(payload.id))

  if (!hasSelectedFile('#e_file-pic')) {
    hideModal('#cultureEditModal')
    refreshTable()
    successTip('修改成功')
    return
  }

  // 封面必须单独上传（后端 editSaveCulture 不更新封面字段）
  editCultureId = payload.id
  window.$('#e_file-pic').fileinput('upload')
}

/* ==================== 删除 ==================== */

function del(id) {
  const row = findRow(id)
  const name = (row && row.cultureName) || '该文化'
  confirmDelete({
    name,
    extra: '删除后前台立即无法访问；8 秒内可在提示里点「撤销」恢复（逻辑删除）。',
    onConfirm: () => doDelete(id, name)
  })
}

async function doDelete(id, name) {
  try {
    await cultureDelete(id)
    refreshTable()
    // 逻辑删除：给「撤销」入口（8 秒）
    dsToast.withUndo('文化「' + (name || id) + '」已删除', async () => {
      await cultureRestore({ id })
      refreshTable()
      dsToast.success('已恢复「' + (name || id) + '」')
    })
  } catch (e) {
    failTip(e.message || '删除失败')
  }
}

/* ==================== 封面上传（fileinput） ==================== */

function hasSelectedFile(selector) {
  const $el = window.$(selector)
  if (!$el.length) return false
  if ($el.val()) return true
  // fileinput 接管后原生 input 的值可能为空，改判插件内部文件栈
  const instance = $el.data('fileinput')
  if (instance && typeof instance.getFilesCount === 'function') {
    try { return instance.getFilesCount() > 0 } catch (e) { return false }
  }
  return false
}

function parseUploadResponse(data) {
  let response = data && data.response
  if (typeof response === 'string') {
    try { response = JSON.parse(response) } catch (e) { response = null }
  }
  return response || {}
}

/** 照抄原模板的 fileinput 选项（新增/编辑各一份，uploadExtraData 带当前记录 id） */
function fileinputOptions(getId) {
  return {
    //初始化上传文件框
    language: "zh",//配置语言
    showUpload: false, //显示整体上传的按钮
    showRemove: true,//显示整体删除的按钮
    uploadAsync: true,//默认异步上传
    uploadLabel: "上传",//设置整体上传按钮的汉字
    removeLabel: "移除",//设置整体删除按钮的汉字
    uploadClass: "btn btn-primary",//设置上传按钮样式
    showCaption: true,//是否显示标题
    dropZoneEnabled: false,//是否显示拖拽区域
    uploadUrl: '/file/uploadCultureFmFile',//这个是配置上传调取的后台地址
    maxFileSize: 9999,//文件大小限制
    maxFileCount: 9999,//允许最大上传数，可以多个，
    enctype: 'multipart/form-data',
    allowedFileExtensions: ["jpg", "png", "gif", "docx", "zip", "xlsx", "txt"],/*上传文件格式限制*/
    msgFilesTooMany: "选择上传的文件数量({n}) 超过允许的最大数值{m}！",
    showBrowse: true,
    browseOnZoneClick: true,
    slugCallback: function (filename) {
      return filename.replace('(', '_').replace(']', '_');
    },
    uploadExtraData: function (previewId, index) {   //额外参数的关键点
      return { id: getId() };
    },
    // 后台 Token（原 Thymeleaf 后台走 Session，分离后必须显式携带）
    ajaxSettings: {
      headers: {
        Authorization: 'Bearer ' + (localStorage.getItem(ADMIN_TOKEN_KEY) || '')
      }
    }
  }
}

function initUploads() {
  const $ = window.$

  $('#file-pic').fileinput(fileinputOptions(() => addCultureId))
  $('#file-pic').on("fileuploaded", function (event, data, previewId, index) {
    var response = parseUploadResponse(data);
    if (window.console) console.log(response);
    if (response.isSuccess) {
      hideModal('#cultureAddModal')
      refreshTable()
      successTip('保存成功')
      router.push('/admin/culture')
    } else {
      failTip('操作失败')
    }
  })

  $('#e_file-pic').fileinput(fileinputOptions(() => editCultureId))
  $('#e_file-pic').on("fileuploaded", function (event, data, previewId, index) {
    var response = parseUploadResponse(data);
    if (window.console) console.log(response);
    if (response.isSuccess) {
      hideModal('#cultureEditModal')
      refreshTable()
      successTip('修改成功')
    } else {
      failTip('操作失败')
    }
  })
}

/* ==================== 校验规则（bootstrapValidator） ==================== */

function initValidators() {
  const $ = window.$
  const feedbackIcons = {
    valid: 'glyphicon glyphicon-ok',
    invalid: 'glyphicon glyphicon-remove',
    validating: 'glyphicon glyphicon-refresh'
  }

  const $addForm = $('#cultureAddForm')
  if ($addForm.data('bootstrapValidator')) $addForm.bootstrapValidator('destroy')
  $addForm.bootstrapValidator({
    live: 'enabled',//字段值有变化就触发验证 disabled,submitted 当点击提交时验证并展示错误信息
    message: '信息不合法',
    feedbackIcons: feedbackIcons,
    fields: {
      cultureName: {
        message: '文化名不合法',
        validators: {
          notEmpty: {
            message: '文化名称必须填写,不能为空'
          }
        }
      },
      address: {
        message: '地址不为空',
        validators: {
          notEmpty: {
            message: '地址不为空'
          }
        }
      },
      desc: {
        message: '描述不为空',
        validators: {
          notEmpty: {
            message: '描述不能空'
          }
        }
      },
      info: {
        message: '介绍不为空',
        validators: {
          notEmpty: {
            message: '介绍不能为空'
          }
        }
      }
    }
  })

  const $editForm = $('#cultureEditForm')
  if ($editForm.data('bootstrapValidator')) $editForm.bootstrapValidator('destroy')
  $editForm.bootstrapValidator({
    live: 'enabled',//字段值有变化就触发验证 disabled,submitted 当点击提交时验证并展示错误信息
    message: '信息不合法',
    feedbackIcons: feedbackIcons,
    fields: {
      cultureName: {
        message: '文化名不合法',
        validators: {
          notEmpty: {
            message: '文化名必须填写,不能为空'
          }
        }
      },
      address: {
        message: '地址不合法',
        validators: {
          notEmpty: {
            message: '地址必须填写,不能为空'
          }
        }
      },
      desc: {
        message: '描述不合法',
        validators: {
          notEmpty: {
            message: '描述必须填写,不能为空'
          }
        }
      },
      info: {
        message: '介绍不合法',
        validators: {
          notEmpty: {
            message: '介绍必须填写,不能为空'
          }
        }
      }
    }
  })
}

/* ==================== 分类下拉 ==================== */

async function loadCategories() {
  try {
    const res = await categoryList({ page: 1, pageSize: 500 })
    categories.value = (res && res.rows) || []
  } catch (e) {
    categories.value = []
  }
}

/* ==================== 生命周期 ==================== */

/**
 * 侧边栏「添加文化」进入时（/admin/culture?action=add）自动打开新增弹窗。
 * 两种时机都要处理：
 *   1) 首次进入该路由 —— 组件刚挂载，watch 不会触发，故在 onMounted 里显式调用；
 *   2) 已在本页（如从「文化列表」再点「添加文化」）—— query.t 变化触发 watch。
 * 打开后清掉 query，保证下次再点仍然有效。
 */
function handleAddShortcut() {
  if (route.query.action === 'add') {
    openAdd()
    router.replace({ path: '/admin/culture' })
  }
}
watch(() => route.query.t, handleAddShortcut)

onMounted(async () => {
  loadAllTags()
  await nextTick()
  await whenJQuery()
  /*
   * 编辑器资源（quill.js 约 209KB + culture-editor.js + serialize-object.js）**不在这里预加载**。
   *
   * 原来这里会 await loadEditorAssets()，也就是「只要打开过文化管理页」就要下载这 200 多 KB，
   * 哪怕用户只是看看列表、从不点「新增/编辑」。
   * 现在改为完全按需：打开新增/编辑弹窗时由 ensureEditorAssets() 触发
   * （该函数与弹窗级懒初始化早已存在，此处只是去掉这份多余的急切加载）。
   */
  if (disposed) return

  // 行内按钮改用 @click；同时仍挂到 window 上（旧行内 onclick / 外部脚本按 id 调用 edit / del / showVersions）
  window.edit = edit
  window.del = del
  window.showVersions = showVersions

  load()   // 表格数据由 Vue 组件自己拉取（旧实现是 bootstrap-table 的 ajax 选项）
  initValidators()
  initUploads()
  await loadCategories()
  // 若通过侧边栏「添加文化」进入，则打开新增弹窗
  handleAddShortcut()
})

onBeforeUnmount(() => {
  // 清理阶段整体兜底：任何清理异常都不能中断 SPA 路由切换
  // （曾因某个页面卸载清理抛错，导致之后所有后台菜单点击都失效）
  try {
    disposed = true

    destroyEditors()

    const $ = window.$
    if (!$) return

    // 说明：原本这里还有 bootstrapTable('destroy')；表格已改由 Vue 渲染，
    // 相关 DOM 随组件卸载自动回收，不再需要销毁表格插件实例（也不再依赖 $.fn.bootstrapTable）。

    try {
      if ($('#file-pic').data('fileinput')) $('#file-pic').fileinput('destroy')
      if ($('#e_file-pic').data('fileinput')) $('#e_file-pic').fileinput('destroy')
    } catch (e) { /* ignore */ }

    try {
      if ($('#cultureAddForm').data('bootstrapValidator')) $('#cultureAddForm').bootstrapValidator('destroy')
      if ($('#cultureEditForm').data('bootstrapValidator')) $('#cultureEditForm').bootstrapValidator('destroy')
    } catch (e) { /* ignore */ }

    $('#file-pic, #e_file-pic').off('fileuploaded')
    $('#cultureAddModal, #cultureEditModal').off()

    // 移除自建 modal 的残留（backdrop / body 锁滚动）
    hideModal('#cultureAddModal')
    hideModal('#cultureEditModal')
    hideModal('#cultureVersionModal')
    $('.modal-backdrop').remove()
    $('body').removeClass('modal-open').css('padding-right', '')

    delete window.edit
    delete window.del
    delete window.showVersions

  } catch (err) {
    console.warn('[cleanup]', err && err.message)
  }
})
</script>

<style scoped>
/* 原 culture.html 页内的 .my-container / .myLabel-content / .myText-content / .myBtn-content
   样式块已随工具栏改版移除（这些类在后台已无使用方）。
   原先为 bootstrap-table 的列筛选 / 每页条数下拉预留的 .ad-card{overflow:visible}
   与 .ad-card__head 圆角补偿已不再需要（表格改由 Vue 渲染）。 */

/* 搜索区：标签与输入框同一行，窄屏可换行（.ad-toolbar 已支持 flex-wrap） */
.ad-label { margin: 0; font-weight: 400; color: var(--ad-text-sub); font-size: 12.5px; white-space: nowrap; }
.ad-input { width: 200px; }
.ad-header__actions .ad-input { width: 190px; }
.ad-select { width: 170px; }
.ad-check-inline { margin-right: 12px; }

/* 分页条紧贴卡片脚 */
.ad-pager { margin: 0; }
/* 可排序表头（点表头切换排序，仅作用于当前页） */
.ad-sort { cursor: pointer; user-select: none; white-space: nowrap; }
.ad-sort:hover { color: var(--ad-primary); }
.ad-sort i { margin-left: 4px; color: var(--ad-muted); }
/* 行内操作按钮间距（旧 bootstrap-table 的操作列挨在一起，这里保持同样紧凑） */
.ad-actions .btn + .btn { margin-left: 4px; }

/* 版本历史弹窗 */
.ad-clip { max-width: 320px; word-break: break-all; }
.version-preview {
  margin-top: 14px;
  padding: 12px;
  border: 1px solid var(--ad-border);
  border-radius: var(--ad-radius);
  background: #fbfcfe;
}
.version-preview__text {
  margin: 0;
  max-height: 260px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--ad-text-sub);
}
</style>

<!--
  以下类用于 v-html 渲染的单元格（cultureStatusFormatter / cellPreview 的 HTML 串）——
  v-html 生成的 DOM 不会带上 scoped 的 data-v 属性，因此必须是全局样式；
  类名带 culture- 前缀，避免与其它页面冲突。这里只是把原来写死在 formatter 里的 inline style 收敛成类。
-->
<style>
.culture-cover-thumb {
  height: 100px;
  width: 140px;
  line-height: 135px !important;
  object-fit: cover;
  border-radius: 6px;
}
.culture-cell-preview {
  max-width: 320px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-word;
  line-height: 1.45;
  text-align: left;
}
/* 状态列（草稿箱）：定时发布时间的小字说明，跟着「定时」徽章显示在同一格 */
.culture-publish-at {
  margin-top: 2px;
  font-size: 11.5px;
  line-height: 1.45;
  word-break: break-all;
}
</style>
