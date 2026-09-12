<template>
  <FrontLayout>
    <div class="container slider-content" style="padding-top: 30px;">
      <div class="row">
        <div class="col-md-12">
          <h2 class="page-title" style="text-align:center;">标签：{{ tagNameResolved }}</h2>
          <p style="text-align:center;color:#999;">
            共 {{ total }} 篇相关内容
            <a href="/search" style="margin-left:10px;">去搜索</a>
          </p>
        </div>
      </div>

      <div class="row content-area recent-property">
        <div class="proerty-th" v-for="item in list" :key="item.id" style="text-align: center;margin: 0 auto">
          <div class="col-sm-6 col-md-3 p0">
            <div class="box-two proerty-item">
              <div class="item-thumb">
                <a :href="`/culture/${item.id}`">
                  <img :src="coverUrl(item.fmUrl)" loading="lazy" style="height: 150px;width: 222px;margin: 0 auto" />
                </a>
              </div>
              <div class="item-entry overflow">
                <h5>
                  <a :href="`/culture/${item.id}`">{{ item.cultureName }}</a>
                </h5>
                <div class="dot-hr"></div>
                <span class="pull-left"><b> 地址：</b>{{ item.address }}</span>
                <span class="pull-right"><b> 浏览：</b>{{ item.view }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div v-if="!loading && !list.length" style="text-align:center;padding:60px 0;color:#999;">
        该标签下还没有内容。
      </div>

      <div class="row" v-if="maxPage > 1">
        <div class="col-md-12" style="text-align:center;">
          <ul class="pagination">
            <li :class="{ disabled: page <= 1 }">
              <a href="javascript:void(0)" @click="go(page - 1)">上一页</a>
            </li>
            <li class="active"><a href="javascript:void(0)">{{ page }} / {{ maxPage }}</a></li>
            <li :class="{ disabled: page >= maxPage }">
              <a href="javascript:void(0)" @click="go(page + 1)">下一页</a>
            </li>
          </ul>
        </div>
      </div>
    </div>
  </FrontLayout>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import FrontLayout from '@/layouts/FrontLayout.vue'
import { getTagCultures, getTags } from '@/api/front'
import { coverUrl, pickPage } from '@/utils/format'

/**
 * 标签聚合页（/tag/:id）
 * 复用文化列表页的卡片结构（col-sm-6 col-md-3 p0 / box-two proerty-item …），样式与列表页一致。
 */
const route = useRoute()
const list = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 12
const loading = ref(true)

const tagId = computed(() => route.params.id)
const tagName = ref('')
const tagNameResolved = computed(() => tagName.value || ('#' + tagId.value))
const maxPage = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

async function load() {
  loading.value = true
  try {
    const data = await getTagCultures({ tagId: tagId.value, page: page.value, pageSize })
    const { rows, total: t } = pickPage(data)
    list.value = rows
    total.value = t
  } catch (e) {
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function go(p) {
  if (p < 1 || p > maxPage.value) return
  page.value = p
  load()
}

async function loadTagName() {
  try {
    const all = await getTags()
    const hit = (all || []).find(t => String(t.id) === String(tagId.value))
    tagName.value = hit ? hit.name : ''
  } catch (e) { tagName.value = '' }
}

onMounted(() => { loadTagName(); load() })
watch(() => route.params.id, () => { page.value = 1; loadTagName(); load() })
</script>
