<template>
  <div class="session">
    <div class="session-head">
      <div>
        <el-tag size="small" :type="connected ? 'success' : 'info'" effect="dark">
          {{ connected ? '已连接' : '未连接' }}
        </el-tag>
        <span class="ticket-id">工单 #{{ ticketId }}</span>
        <el-tag v-if="ticket" size="small" :type="statusTag(ticket.status)" effect="plain">
          {{ statusText(ticket.status) }}
        </el-tag>
      </div>
      <el-button v-if="canClose && ticket?.status !== 'CLOSED'" size="small" type="danger" plain
        @click="onClose">结束会话</el-button>
    </div>

    <div v-if="ticket?.lastQuestion" class="context-bar">
      <span class="muted">转人工上下文:</span>{{ ticket.lastQuestion }}
      <el-tag size="small" class="reason">{{ reasonText(ticket.reason) }}</el-tag>
    </div>

    <div class="msg-list" ref="listEl">
      <div v-for="(m, i) in messages" :key="i" class="msg-row" :class="rowClass(m)">
        <div v-if="m.senderRole === 'SYSTEM'" class="sys">{{ m.content }}</div>
        <template v-else>
          <div class="bubble">
            <div class="sender">{{ m.senderName }} · {{ roleText(m.senderRole) }}</div>
            <div class="content">{{ m.content }}</div>
            <div class="time">{{ fmtTime(m.timestamp) }}</div>
          </div>
        </template>
      </div>
      <div v-if="!messages.length" class="empty muted">暂无消息,发一条开始吧</div>
    </div>

    <div class="input-bar">
      <el-input v-model="draft" type="textarea" :rows="2" resize="none"
        placeholder="输入消息,Enter 发送 / Shift+Enter 换行"
        :disabled="!connected || ticket?.status === 'CLOSED'"
        @keydown.enter.exact.prevent="onSend" />
      <el-button type="primary" :disabled="!connected || ticket?.status === 'CLOSED'" @click="onSend">
        发送
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, nextTick, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { ticketApi } from '@/api'
import { useTicketSocket } from '@/composables/useTicketSocket'

const props = defineProps({
  ticketId: { type: [Number, String], required: true },
  // 是否显示「结束会话」按钮(访客与接单坐席都可结束)
  canClose: { type: Boolean, default: true }
})
const emit = defineEmits(['closed'])

const { messages, connected, connect, send, close } = useTicketSocket()
const ticket = ref(null)
const draft = ref('')
const listEl = ref()

async function init(id) {
  // 先拉详情 + 历史,再建 WS(实时增量在历史之后)
  try {
    const { data } = await ticketApi.detail(id)
    ticket.value = data.ticket || data
    const history = data.messages || data.history || []
    messages.value = history.map((h) => ({
      type: h.senderRole === 'SYSTEM' ? 'SYSTEM' : 'CHAT',
      senderRole: h.senderRole,
      senderId: h.senderId,
      senderName: h.senderName,
      content: h.content,
      timestamp: h.createTime ? new Date(h.createTime).getTime() : Date.now()
    }))
    scrollToBottom()
  } catch {
    /* 拦截器已提示 */
  }
  if (ticket.value?.status !== 'CLOSED') {
    connect(id, { onMessage: scrollToBottom })
  }
}

function onSend() {
  if (send(draft.value)) {
    draft.value = ''
  }
}

async function onClose() {
  await ticketApi.close(props.ticketId)
  ElMessage.success('会话已结束')
  if (ticket.value) ticket.value.status = 'CLOSED'
  close()
  emit('closed', props.ticketId)
}

function scrollToBottom() {
  nextTick(() => {
    if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight
  })
}
function rowClass(m) {
  if (m.senderRole === 'SYSTEM') return 'center'
  return m.senderRole === 'AGENT' ? 'right' : 'left'
}
function roleText(r) {
  return { VISITOR: '访客', AGENT: '坐席', SYSTEM: '系统' }[r] || r
}
function reasonText(r) {
  return { USER_REQUEST: '用户主动', BOT_FAILED: '机器人异常', NOT_FOUND: '未找到答案' }[r] || r
}
function statusText(s) {
  return { WAITING: '待接入', ASSIGNED: '会话中', CLOSED: '已结束' }[s] || s
}
function statusTag(s) {
  return { WAITING: 'warning', ASSIGNED: 'success', CLOSED: 'info' }[s] || 'info'
}
function fmtTime(ts) {
  if (!ts) return ''
  return new Date(ts).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

watch(() => props.ticketId, (id) => { if (id) init(id) }, { immediate: true })
onUnmounted(close)
</script>

<style scoped>
.session {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.session-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-bottom: 10px;
  border-bottom: 1px solid #ebeef5;
}
.ticket-id {
  margin: 0 8px;
  font-weight: 600;
}
.context-bar {
  background: #fff7e6;
  border: 1px solid #ffe7ba;
  border-radius: 6px;
  padding: 6px 10px;
  margin: 10px 0;
  font-size: 13px;
}
.context-bar .reason {
  margin-left: 8px;
}
.msg-list {
  flex: 1;
  overflow-y: auto;
  padding: 12px 4px;
}
.msg-row {
  display: flex;
  margin-bottom: 12px;
}
.msg-row.right {
  justify-content: flex-end;
}
.msg-row.left {
  justify-content: flex-start;
}
.msg-row.center {
  justify-content: center;
}
.bubble {
  max-width: 70%;
  background: #f4f4f5;
  border-radius: 8px;
  padding: 8px 12px;
}
.msg-row.right .bubble {
  background: #ecf5ff;
}
.sender {
  font-size: 12px;
  color: #909399;
  margin-bottom: 2px;
}
.content {
  white-space: pre-wrap;
  word-break: break-word;
}
.time {
  font-size: 11px;
  color: #c0c4cc;
  text-align: right;
  margin-top: 2px;
}
.sys {
  font-size: 12px;
  color: #909399;
  background: #f0f0f0;
  padding: 3px 10px;
  border-radius: 10px;
}
.empty {
  text-align: center;
  padding: 30px 0;
}
.input-bar {
  display: flex;
  gap: 8px;
  align-items: flex-end;
  padding-top: 10px;
  border-top: 1px solid #ebeef5;
}
</style>
