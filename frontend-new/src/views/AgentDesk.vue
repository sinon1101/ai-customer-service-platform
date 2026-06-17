<template>
  <div class="desk">
    <div class="page-head">
      <h2 class="page-title">坐席工作台</h2>
      <el-tag type="info" effect="plain">轮询待接入池 · 抢单 Redisson 锁 + DB 条件更新双保险</el-tag>
    </div>

    <div class="desk-body">
      <!-- 左:工单列表 -->
      <el-card class="list-panel" shadow="never">
        <el-tabs v-model="tab">
          <el-tab-pane name="pending">
            <template #label>
              待接入池 <el-badge v-if="pending.length" :value="pending.length" class="badge" />
            </template>
            <div class="ticket-list">
              <div v-if="!pending.length" class="empty muted">暂无待接入工单</div>
              <div v-for="t in pending" :key="t.id" class="ticket-item">
                <div class="ti-top">
                  <span class="ti-id">#{{ t.id }}</span>
                  <el-tag size="small" type="warning">{{ reasonText(t.reason) }}</el-tag>
                </div>
                <div class="ti-q">{{ t.lastQuestion || '(无最后提问)' }}</div>
                <div class="ti-foot">
                  <span class="muted">访客:{{ t.visitorName }}</span>
                  <el-button size="small" type="primary" :loading="claimingId === t.id"
                    @click="onClaim(t)">抢单</el-button>
                </div>
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane name="mine" label="我的会话">
            <div class="ticket-list">
              <div v-if="!mine.length" class="empty muted">暂无已接入会话</div>
              <div v-for="t in mine" :key="t.id" class="ticket-item clickable"
                :class="{ active: activeTicketId === t.id }" @click="openSession(t.id)">
                <div class="ti-top">
                  <span class="ti-id">#{{ t.id }}</span>
                  <el-tag size="small" :type="t.status === 'ASSIGNED' ? 'success' : 'info'">
                    {{ statusText(t.status) }}
                  </el-tag>
                </div>
                <div class="ti-q">{{ t.lastQuestion || '(无最后提问)' }}</div>
                <div class="ti-foot"><span class="muted">访客:{{ t.visitorName }}</span></div>
              </div>
            </div>
          </el-tab-pane>
        </el-tabs>
        <div class="refresh-hint muted">待接入池每 3s 自动刷新</div>
      </el-card>

      <!-- 右:实时会话 -->
      <el-card class="session-panel" shadow="never">
        <RealtimeSession v-if="activeTicketId" :key="activeTicketId" :ticket-id="activeTicketId"
          @closed="onSessionClosed" />
        <div v-else class="empty muted session-empty">
          <el-icon :size="40"><ChatLineRound /></el-icon>
          <p>从左侧抢单或选择「我的会话」开始实时对话</p>
        </div>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { ChatLineRound } from '@element-plus/icons-vue'
import { ticketApi } from '@/api'
import RealtimeSession from '@/components/RealtimeSession.vue'

const tab = ref('pending')
const pending = ref([])
const mine = ref([])
const claimingId = ref(null)
const activeTicketId = ref(null)
let pollTimer = null

async function loadPending() {
  try {
    const { data } = await ticketApi.pending()
    pending.value = data || []
  } catch {
    /* ignore */
  }
}
async function loadMine() {
  try {
    const { data } = await ticketApi.mine()
    mine.value = data || []
  } catch {
    /* ignore */
  }
}

async function onClaim(t) {
  claimingId.value = t.id
  try {
    await ticketApi.claim(t.id)
    ElMessage.success(`抢单成功 #${t.id}`)
    await Promise.all([loadPending(), loadMine()])
    tab.value = 'mine'
    openSession(t.id)
  } catch {
    // 抢单失败(已被他人接走 / 锁竞争失败),刷新池
    loadPending()
  } finally {
    claimingId.value = null
  }
}

function openSession(id) {
  activeTicketId.value = id
}
function onSessionClosed() {
  activeTicketId.value = null
  loadMine()
  loadPending()
}

function startPolling() {
  stopPolling()
  pollTimer = setInterval(loadPending, 3000)
}
function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function reasonText(r) {
  return { USER_REQUEST: '用户主动', BOT_FAILED: '机器人异常', NOT_FOUND: '未找到答案' }[r] || r
}
function statusText(s) {
  return { WAITING: '待接入', ASSIGNED: '会话中', CLOSED: '已结束' }[s] || s
}

onMounted(() => {
  loadPending()
  loadMine()
  startPolling()
})
onUnmounted(stopPolling)
</script>

<style scoped>
.page-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}
.page-head .page-title {
  margin: 0;
}
.desk-body {
  display: flex;
  gap: 16px;
  height: calc(100vh - 150px);
}
.list-panel {
  width: 340px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
}
.list-panel :deep(.el-card__body) {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.session-panel {
  flex: 1;
}
.session-panel :deep(.el-card__body) {
  height: 100%;
}
.ticket-list {
  overflow-y: auto;
}
.ticket-item {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 10px;
  margin-bottom: 10px;
}
.ticket-item.clickable {
  cursor: pointer;
}
.ticket-item.active {
  border-color: #409eff;
  background: #ecf5ff;
}
.ti-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.ti-id {
  font-weight: 600;
}
.ti-q {
  margin: 6px 0;
  font-size: 13px;
  color: #606266;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.ti-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.empty {
  text-align: center;
  padding: 30px 0;
}
.session-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
}
.refresh-hint {
  font-size: 12px;
  text-align: center;
  margin-top: auto;
  padding-top: 8px;
}
.badge {
  margin-left: 4px;
}
</style>
