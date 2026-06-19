<template>
  <div class="chat-page">
    <div class="chat-main">
      <div class="chat-toolbar">
        <div class="left">
          <span class="muted">知识库:</span>
          <el-select v-model="kbId" placeholder="全部知识库" clearable size="default" style="width: 200px">
            <el-option v-for="kb in kbs" :key="kb.id" :label="kb.name" :value="kb.id" />
          </el-select>
        </div>
        <div class="right">
          <el-button :icon="Delete" @click="resetConversation">新会话</el-button>
          <el-button type="warning" :icon="Service" :loading="transferring" @click="onTransfer">转人工</el-button>
        </div>
      </div>

      <div class="msg-area" ref="listEl">
        <div v-if="!messages.length" class="welcome">
          <el-icon :size="40" color="#409eff"><ChatDotRound /></el-icon>
          <p>你好,我是 AI 客服助手。基于知识库为你解答 —— 试试提问吧。</p>
          <p class="muted" v-if="conversationId">会话 ID:{{ conversationId }}</p>
        </div>

        <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
          <div class="avatar">
            <el-icon v-if="m.role === 'user'"><User /></el-icon>
            <el-icon v-else><Service /></el-icon>
          </div>
          <div class="bubble-wrap">
            <div class="bubble">
              <span v-if="m.content">{{ m.content }}</span>
              <span v-else-if="m.streaming" class="muted">思考中…</span>
              <span class="cursor" v-if="m.streaming">▋</span>
            </div>
            <div class="badges" v-if="m.role === 'assistant'">
              <el-tag v-if="m.cached" size="small" type="success" effect="plain">语义缓存命中</el-tag>
              <el-tag v-if="m.degraded" size="small" type="warning" effect="plain">降级兜底</el-tag>
              <el-popover v-if="m.sources && m.sources.length" placement="top" width="380" trigger="click">
                <template #reference>
                  <el-tag size="small" type="info" effect="plain" class="clickable">
                    引用 {{ m.sources.length }} 处
                  </el-tag>
                </template>
                <div v-for="(s, si) in m.sources" :key="si" class="source-item">
                  <div class="source-head">
                    <strong>{{ s.docName }}</strong>
                    <span class="muted">score {{ s.score?.toFixed(3) }}</span>
                  </div>
                  <div class="source-snippet muted">{{ s.snippet }}</div>
                </div>
              </el-popover>
            </div>
          </div>
        </div>
      </div>

      <div class="input-area">
        <el-input v-model="draft" type="textarea" :rows="3" resize="none"
          placeholder="输入问题,Enter 发送 / Shift+Enter 换行" :disabled="sending"
          @keydown.enter.exact.prevent="onSend" />
        <el-button type="primary" :loading="sending" @click="onSend">发送</el-button>
      </div>
    </div>

    <!-- 转人工后:访客侧实时会话抽屉 -->
    <el-drawer v-model="humanVisible" title="人工坐席会话" size="540px" :destroy-on-close="true">
      <RealtimeSession v-if="ticketId" :ticket-id="ticketId" self-role="VISITOR" @closed="humanVisible = false" />
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import { Delete, Service, ChatDotRound, User } from '@element-plus/icons-vue'
import { kbApi, ticketApi } from '@/api'
import { streamChat } from '@/api/stream'
import RealtimeSession from '@/components/RealtimeSession.vue'

const kbs = ref([])
const kbId = ref(null)
const messages = reactive([])
const draft = ref('')
const sending = ref(false)
const conversationId = ref(null)
const listEl = ref()
let lastQuestion = ''

const transferring = ref(false)
const humanVisible = ref(false)
const ticketId = ref(null)

async function loadKbs() {
  try {
    const { data } = await kbApi.list()
    kbs.value = data || []
  } catch {
    /* ignore */
  }
}

function onSend() {
  const text = draft.value.trim()
  if (!text || sending.value) return
  draft.value = ''
  lastQuestion = text
  messages.push({ role: 'user', content: text })
  const assistant = reactive({
    role: 'assistant', content: '', sources: [], cached: false, degraded: false, streaming: true
  })
  messages.push(assistant)
  scrollToBottom()
  sending.value = true

  streamChat(
    { message: text, kbId: kbId.value || null, conversationId: conversationId.value },
    {
      onMeta: (meta) => {
        if (meta.conversationId) conversationId.value = meta.conversationId
        assistant.sources = meta.sources || []
        assistant.cached = !!meta.cached
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
      kbId: kbId.value || null,
      reason: 'USER_REQUEST',
      lastQuestion: lastQuestion || (messages.findLast?.((m) => m.role === 'user')?.content) || ''
    })
    ticketId.value = ticket.id
    humanVisible.value = true
    ElMessage.success(`已转人工,工单 #${ticket.id},等待坐席接入`)
  } catch {
    /* 拦截器已提示 */
  } finally {
    transferring.value = false
  }
}

function resetConversation() {
  messages.splice(0, messages.length)
  conversationId.value = null
  lastQuestion = ''
}

function scrollToBottom() {
  nextTick(() => {
    if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight
  })
}

onMounted(loadKbs)
</script>

<style scoped>
.chat-page {
  height: calc(100vh - 100px);
}
.chat-main {
  background: #fff;
  border-radius: 8px;
  height: 100%;
  display: flex;
  flex-direction: column;
  border: 1px solid #ebeef5;
}
.chat-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid #ebeef5;
}
.msg-area {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}
.welcome {
  text-align: center;
  margin-top: 60px;
  color: #606266;
}
.msg {
  display: flex;
  gap: 10px;
  margin-bottom: 18px;
}
.msg.user {
  flex-direction: row-reverse;
}
.avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: #fff;
}
.msg.user .avatar {
  background: #409eff;
}
.msg.assistant .avatar {
  background: #67c23a;
}
.bubble-wrap {
  max-width: 72%;
}
.bubble {
  padding: 10px 14px;
  border-radius: 8px;
  background: #f4f4f5;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.6;
}
.msg.user .bubble {
  background: #ecf5ff;
}
.cursor {
  animation: blink 1s step-start infinite;
}
@keyframes blink {
  50% {
    opacity: 0;
  }
}
.badges {
  margin-top: 6px;
  display: flex;
  gap: 6px;
}
.clickable {
  cursor: pointer;
}
.source-item {
  margin-bottom: 10px;
}
.source-head {
  display: flex;
  justify-content: space-between;
}
.source-snippet {
  font-size: 12px;
  margin-top: 2px;
}
.input-area {
  display: flex;
  gap: 10px;
  align-items: flex-end;
  padding: 14px 16px;
  border-top: 1px solid #ebeef5;
}
</style>
