<template>
  <div class="widget-stage">
    <div class="widget">
      <!-- 头部 -->
      <div class="w-head">
        <div class="w-title">
          <el-icon :size="20"><Service /></el-icon>
          <div>
            <div class="w-name">在线客服</div>
            <div class="w-sub">{{ visitor.tenantName || '智能助手' }}</div>
          </div>
        </div>
        <el-tag size="small" effect="dark" type="success" v-if="visitor.nickName">{{ visitor.nickName }}</el-tag>
      </div>

      <!-- 加载/错误态 -->
      <div v-if="booting" class="w-center muted">
        <el-icon class="spin"><Loading /></el-icon> 正在接入客服…
      </div>
      <div v-else-if="bootError" class="w-center">
        <el-icon :size="32" color="#f56c6c"><WarningFilled /></el-icon>
        <p class="muted">{{ bootError }}</p>
        <el-button size="small" @click="boot">重试</el-button>
      </div>

      <!-- 会话区 -->
      <template v-else>
        <div class="w-msgs" ref="listEl">
          <div class="w-welcome" v-if="!messages.length">
            <el-icon :size="34" color="#409eff"><ChatDotRound /></el-icon>
            <p>您好!我是 AI 客服助手,有什么可以帮您?</p>
            <p class="muted tip">回答不满意时,可点右上角「转人工」。</p>
          </div>

          <div v-for="(m, i) in messages" :key="i" class="w-msg" :class="m.role">
            <div class="w-avatar">
              <el-icon v-if="m.role === 'user'"><User /></el-icon>
              <el-icon v-else><Service /></el-icon>
            </div>
            <div class="w-bubble-wrap">
              <div class="w-bubble">
                <span v-if="m.content">{{ m.content }}</span>
                <span v-else-if="m.streaming" class="muted">思考中…</span>
                <span class="cursor" v-if="m.streaming">▋</span>
              </div>
              <div v-if="m.role === 'assistant' && m.degraded" class="w-degraded">
                <el-tag size="small" type="warning" effect="plain">智能助手繁忙</el-tag>
                <el-button link type="primary" size="small" @click="onTransfer">转人工客服 →</el-button>
              </div>
            </div>
          </div>
        </div>

        <div class="w-input">
          <el-input v-model="draft" type="textarea" :rows="2" resize="none"
            placeholder="输入问题,Enter 发送 / Shift+Enter 换行" :disabled="sending"
            @keydown.enter.exact.prevent="onSend" />
          <div class="w-actions">
            <el-button size="small" :loading="transferring" @click="onTransfer">转人工</el-button>
            <el-button size="small" type="primary" :loading="sending" @click="onSend">发送</el-button>
          </div>
        </div>
      </template>
    </div>

    <!-- 转人工后:实时会话抽屉 -->
    <el-drawer v-model="humanVisible" title="人工坐席会话" size="480px" :destroy-on-close="true">
      <RealtimeSession v-if="ticketId" :ticket-id="ticketId" self-role="VISITOR" @closed="humanVisible = false" />
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Service, ChatDotRound, User, Loading, WarningFilled } from '@element-plus/icons-vue'
import { visitorApi, ticketApi } from '@/api'
import { streamChat } from '@/api/stream'
import { useVisitorStore } from '@/store/visitor'
import RealtimeSession from '@/components/RealtimeSession.vue'

const route = useRoute()
const visitor = useVisitorStore()
// 租户编码:挂件嵌入参数 ?tenant=acme(演示缺省 acme)
const tenantCode = route.query.tenant || 'acme'

const booting = ref(true)
const bootError = ref('')
const messages = reactive([])
const draft = ref('')
const sending = ref(false)
const conversationId = ref(null)
const listEl = ref()
let lastQuestion = ''

const transferring = ref(false)
const humanVisible = ref(false)
const ticketId = ref(null)

async function boot() {
  booting.value = true
  bootError.value = ''
  try {
    // 已有访客会话(sessionStorage)则复用,保持同一访客身份;否则按租户领取
    if (!visitor.token) {
      const { data } = await visitorApi.session(tenantCode)
      visitor.setSession(data)
    }
  } catch {
    bootError.value = `无法接入客服(租户「${tenantCode}」不可用)`
  } finally {
    booting.value = false
  }
}

function onSend() {
  const text = draft.value.trim()
  if (!text || sending.value) return
  draft.value = ''
  lastQuestion = text
  messages.push({ role: 'user', content: text })
  const assistant = reactive({ role: 'assistant', content: '', degraded: false, streaming: true })
  messages.push(assistant)
  scrollToBottom()
  sending.value = true

  streamChat(
    { message: text, kbId: null, conversationId: conversationId.value },
    {
      onMeta: (meta) => {
        if (meta.conversationId) conversationId.value = meta.conversationId
        assistant.degraded = !!meta.degraded
      },
      onToken: (t) => {
        assistant.content += t
        scrollToBottom()
      },
      onDone: () => {
        assistant.streaming = false
        sending.value = false
        scrollToBottom()
      },
      onError: (msg) => {
        assistant.streaming = false
        assistant.content = assistant.content || `[出错] ${msg}`
        sending.value = false
      }
    }
  )
}

async function onTransfer() {
  transferring.value = true
  try {
    const { data: ticket } = await ticketApi.transfer({
      conversationId: conversationId.value,
      kbId: null,
      reason: 'USER_REQUEST',
      lastQuestion: lastQuestion || (messages.findLast?.((m) => m.role === 'user')?.content) || ''
    })
    ticketId.value = ticket.id
    humanVisible.value = true
    // 转人工幂等:同对话已有工单会直接返回它,故文案需按真实状态区分(可能坐席已接入)
    ElMessage.success(ticket.status === 'ASSIGNED'
      ? `工单 #${ticket.id} 坐席已接入,继续会话`
      : `已转人工,工单 #${ticket.id},等待坐席接入`)
  } catch {
    /* 拦截器已提示 */
  } finally {
    transferring.value = false
  }
}

function scrollToBottom() {
  nextTick(() => {
    if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight
  })
}

onMounted(boot)
</script>

<style scoped>
.widget-stage {
  height: 100vh;
  background: linear-gradient(135deg, #e8f0fe 0%, #f5f7fa 100%);
  display: flex;
  align-items: center;
  justify-content: center;
}
.widget {
  width: 420px;
  height: 86vh;
  max-height: 720px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.12);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.w-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 16px;
  background: #409eff;
  color: #fff;
}
.w-title {
  display: flex;
  align-items: center;
  gap: 10px;
}
.w-name {
  font-weight: 600;
  font-size: 15px;
}
.w-sub {
  font-size: 12px;
  opacity: 0.85;
}
.w-center {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
}
.spin {
  animation: rot 1s linear infinite;
}
@keyframes rot {
  to { transform: rotate(360deg); }
}
.w-msgs {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
}
.w-welcome {
  text-align: center;
  margin-top: 40px;
  color: #606266;
}
.w-welcome .tip {
  font-size: 12px;
}
.w-msg {
  display: flex;
  gap: 8px;
  margin-bottom: 14px;
}
.w-msg.user {
  flex-direction: row-reverse;
}
.w-avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: #fff;
}
.w-msg.user .w-avatar {
  background: #409eff;
}
.w-msg.assistant .w-avatar {
  background: #67c23a;
}
.w-bubble-wrap {
  max-width: 76%;
}
.w-bubble {
  padding: 9px 12px;
  border-radius: 8px;
  background: #f4f4f5;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.55;
  font-size: 14px;
}
.w-msg.user .w-bubble {
  background: #ecf5ff;
}
.cursor {
  animation: blink 1s step-start infinite;
}
@keyframes blink {
  50% { opacity: 0; }
}
.w-degraded {
  margin-top: 6px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.w-input {
  border-top: 1px solid #ebeef5;
  padding: 10px 12px;
}
.w-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}
.muted {
  color: #909399;
}
</style>
