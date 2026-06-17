<template>
  <div v-loading="loading">
    <div class="page-head">
      <h2 class="page-title">统计看板</h2>
      <div class="head-right">
        <el-switch v-model="autoRefresh" active-text="自动刷新(5s)" @change="toggleAuto" />
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
    </div>

    <!-- 会话指标 -->
    <div class="section-title">会话 / 缓存 / 降级(累计)</div>
    <el-row :gutter="16">
      <el-col :span="6"><stat-card label="会话总量" :value="chat.requests" color="#409eff" /></el-col>
      <el-col :span="6"><stat-card label="LLM 调用" :value="chat.llmCalls" color="#67c23a" /></el-col>
      <el-col :span="6"><stat-card label="限流拒绝" :value="chat.rateLimited" color="#e6a23c" /></el-col>
      <el-col :span="6"><stat-card label="降级兜底" :value="chat.degraded" color="#f56c6c" /></el-col>
    </el-row>
    <el-row :gutter="16" class="mt">
      <el-col :span="12">
        <el-card shadow="never">
          <div class="rate-row">
            <span>语义缓存命中率</span><strong>{{ chat.cacheHitRate }}%</strong>
          </div>
          <el-progress :percentage="num(chat.cacheHitRate)" :stroke-width="14" color="#67c23a" />
          <div class="muted sub">命中 {{ chat.cacheHit }} / 可命中 {{ chat.cacheEligible }}</div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never">
          <div class="rate-row">
            <span>降级率</span><strong>{{ chat.degradeRate }}%</strong>
          </div>
          <el-progress :percentage="num(chat.degradeRate)" :stroke-width="14" color="#f56c6c" />
          <div class="muted sub">降级 {{ chat.degraded }} / 会话 {{ chat.requests }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- token -->
    <div class="section-title">Token 消耗(真实用量)</div>
    <el-row :gutter="16">
      <el-col :span="6"><stat-card label="总 Token" :value="tokens.total" color="#409eff" /></el-col>
      <el-col :span="6"><stat-card label="Prompt" :value="tokens.prompt" color="#909399" /></el-col>
      <el-col :span="6"><stat-card label="Completion" :value="tokens.completion" color="#909399" /></el-col>
      <el-col :span="6"><stat-card label="每次调用均值" :value="tokens.avgPerCall" color="#67c23a" /></el-col>
    </el-row>

    <!-- 坐席 -->
    <div class="section-title">人工坐席效率</div>
    <el-row :gutter="16">
      <el-col :span="4"><stat-card label="工单总量" :value="agent.totalTickets" color="#409eff" /></el-col>
      <el-col :span="4"><stat-card label="待接入" :value="agent.waiting" color="#e6a23c" /></el-col>
      <el-col :span="4"><stat-card label="会话中" :value="agent.assigned" color="#67c23a" /></el-col>
      <el-col :span="4"><stat-card label="已结束" :value="agent.closed" color="#909399" /></el-col>
      <el-col :span="4"><stat-card label="平均等待(s)" :value="agent.avgWaitSeconds" color="#409eff" /></el-col>
      <el-col :span="4"><stat-card label="平均处理(s)" :value="agent.avgHandleSeconds" color="#409eff" /></el-col>
    </el-row>

    <!-- 治理实时态 -->
    <div class="section-title">高并发治理(实时态)</div>
    <el-row :gutter="16">
      <el-col :span="8">
        <el-card shadow="never" class="gov-card">
          <div class="gov-head">熔断器</div>
          <el-tag :type="breakerTag" effect="dark" size="large">{{ gov.circuitBreaker?.state }}</el-tag>
          <div class="gov-line">
            <span>故障注入</span>
            <el-switch v-model="faultOn" :loading="faultLoading" @change="onToggleFault" />
          </div>
          <div class="muted sub">开启后可演示熔断跳闸(混沌工程)</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never" class="gov-card">
          <div class="gov-head">租户信号量隔离(Bulkhead)</div>
          <el-progress type="dashboard" :percentage="permitPct"
            :color="permitPct > 80 ? '#f56c6c' : '#409eff'">
            <template #default>
              <span class="dash-val">{{ gov.isolation?.used }}/{{ gov.isolation?.permits }}</span>
            </template>
          </el-progress>
          <div class="muted sub">已占用 / 总名额</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never" class="gov-card">
          <div class="gov-head">令牌桶水位</div>
          <div class="bucket">
            <span>租户桶</span>
            <el-progress :percentage="tenantBucketPct" :stroke-width="12" />
            <span class="muted">{{ gov.rateLimit?.tenantTokens }}/{{ gov.rateLimit?.tenantCapacity }}</span>
          </div>
          <div class="bucket">
            <span>用户桶</span>
            <el-progress :percentage="userBucketPct" :stroke-width="12" color="#67c23a" />
            <span class="muted">{{ gov.rateLimit?.userTokens }}/{{ gov.rateLimit?.userCapacity }}</span>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { dashboardApi, governanceApi } from '@/api'
import StatCard from '@/components/StatCard.vue'

const loading = ref(false)
const chat = reactive({})
const tokens = reactive({})
const agent = reactive({})
const gov = reactive({})
const faultOn = ref(false)
const faultLoading = ref(false)
const autoRefresh = ref(true)
let timer = null

async function load() {
  loading.value = true
  try {
    const { data } = await dashboardApi.overview()
    Object.assign(chat, data.chat || {})
    Object.assign(tokens, data.tokens || {})
    Object.assign(agent, data.agent || {})
    Object.assign(gov, data.governance || {})
    faultOn.value = !!gov.circuitBreaker?.faultInjected
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

async function onToggleFault(val) {
  faultLoading.value = true
  try {
    await governanceApi.fault(val)
    ElMessage.success(`故障注入已${val ? '开启' : '关闭'}`)
    load()
  } catch {
    faultOn.value = !val // 回滚
  } finally {
    faultLoading.value = false
  }
}

function toggleAuto(val) {
  if (val) startAuto()
  else stopAuto()
}
function startAuto() {
  stopAuto()
  timer = setInterval(load, 5000)
}
function stopAuto() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

const num = (v) => Number(v) || 0
const breakerTag = computed(() => {
  return { CLOSED: 'success', OPEN: 'danger', HALF_OPEN: 'warning' }[gov.circuitBreaker?.state] || 'info'
})
const permitPct = computed(() => {
  const p = gov.isolation?.permits || 1
  return Math.round(((gov.isolation?.used || 0) / p) * 100)
})
const tenantBucketPct = computed(() => {
  const c = gov.rateLimit?.tenantCapacity || 1
  return Math.round(((gov.rateLimit?.tenantTokens || 0) / c) * 100)
})
const userBucketPct = computed(() => {
  const c = gov.rateLimit?.userCapacity || 1
  return Math.round(((gov.rateLimit?.userTokens || 0) / c) * 100)
})

onMounted(() => {
  load()
  if (autoRefresh.value) startAuto()
})
onUnmounted(stopAuto)
</script>

<style scoped>
.page-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}
.head-right {
  display: flex;
  gap: 12px;
  align-items: center;
}
.section-title {
  font-size: 15px;
  font-weight: 600;
  margin: 22px 0 12px;
  color: #303133;
}
.mt {
  margin-top: 16px;
}
.rate-row {
  display: flex;
  justify-content: space-between;
  margin-bottom: 10px;
  font-weight: 500;
}
.sub {
  font-size: 12px;
  margin-top: 8px;
}
.gov-card {
  text-align: center;
  min-height: 180px;
}
.gov-head {
  font-weight: 600;
  margin-bottom: 14px;
}
.gov-line {
  display: flex;
  justify-content: center;
  gap: 10px;
  align-items: center;
  margin-top: 16px;
}
.dash-val {
  font-size: 18px;
  font-weight: 600;
}
.bucket {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 12px 0;
}
.bucket > span:first-child {
  width: 52px;
  text-align: left;
  flex-shrink: 0;
}
.bucket :deep(.el-progress) {
  flex: 1;
}
</style>
