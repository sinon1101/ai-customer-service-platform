<template>
  <div>
    <div class="toolbar">
      <h2 class="page-title">知识库管理</h2>
      <el-button type="primary" :icon="Plus" @click="openCreate">新建知识库</el-button>
    </div>

    <el-table :data="list" v-loading="loading" border stripe>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column prop="description" label="描述" min-width="220" show-overflow-tooltip />
      <el-table-column prop="docCount" label="文档数" width="90" align="center" />
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
            {{ row.status === 1 ? '启用' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="240" align="center">
        <template #default="{ row }">
          <el-button size="small" type="primary" link :icon="Document" @click="openDocs(row)">文档</el-button>
          <el-button size="small" link :icon="Edit" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" link :icon="Delete" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新建 / 编辑 -->
    <el-dialog v-model="editVisible" :title="editForm.id ? '编辑知识库' : '新建知识库'" width="460px">
      <el-form :model="editForm" label-width="70px">
        <el-form-item label="名称">
          <el-input v-model="editForm.name" placeholder="知识库名称" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="editForm.description" type="textarea" :rows="3" placeholder="用途描述" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 文档抽屉:上传 + 列表 + 状态轮询 -->
    <el-drawer v-model="docsVisible" :title="`文档 — ${current?.name || ''}`" size="640px" @close="stopPolling">
      <el-form :model="docForm" label-width="72px" class="doc-upload">
        <el-form-item label="文档名">
          <el-input v-model="docForm.name" placeholder="如:售后政策" />
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="docForm.sourceType">
            <el-radio-button label="TEXT">纯文本</el-radio-button>
            <el-radio-button label="MD">Markdown</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="内容">
          <el-input v-model="docForm.content" type="textarea" :rows="6"
            placeholder="粘贴文档原文,上传后后台异步切片向量化" />
        </el-form-item>
        <el-button type="primary" :icon="Upload" :loading="uploading" @click="onUpload">上传并摄入</el-button>
        <span class="muted upload-hint">上传即返回,后台 RocketMQ → 切片 → 向量化,状态自动刷新</span>
      </el-form>

      <el-divider>已上传文档</el-divider>

      <el-table :data="docs" v-loading="docsLoading" size="small" border>
        <el-table-column prop="name" label="文档名" min-width="120" show-overflow-tooltip />
        <el-table-column prop="charCount" label="字符" width="70" align="center" />
        <el-table-column prop="chunkCount" label="切片" width="70" align="center" />
        <el-table-column label="状态" width="120" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small" effect="dark">
              <el-icon v-if="isProcessing(row.status)" class="spin"><Loading /></el-icon>
              {{ statusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="错误" min-width="100">
          <template #default="{ row }">
            <span class="muted" v-if="row.errorMsg">{{ row.errorMsg }}</span>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete, Document, Upload, Loading } from '@element-plus/icons-vue'
import { kbApi } from '@/api'

const list = ref([])
const loading = ref(false)

const editVisible = ref(false)
const saving = ref(false)
const editForm = reactive({ id: null, name: '', description: '' })

const docsVisible = ref(false)
const docsLoading = ref(false)
const current = ref(null)
const docs = ref([])
const uploading = ref(false)
const docForm = reactive({ name: '', sourceType: 'TEXT', content: '' })
let pollTimer = null

async function loadList() {
  loading.value = true
  try {
    const { data } = await kbApi.list()
    list.value = data || []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editForm.id = null
  editForm.name = ''
  editForm.description = ''
  editVisible.value = true
}
function openEdit(row) {
  editForm.id = row.id
  editForm.name = row.name
  editForm.description = row.description
  editVisible.value = true
}
async function onSave() {
  if (!editForm.name) {
    ElMessage.warning('请填写名称')
    return
  }
  saving.value = true
  try {
    const payload = { name: editForm.name, description: editForm.description }
    if (editForm.id) {
      await kbApi.update(editForm.id, payload)
    } else {
      await kbApi.create(payload)
    }
    ElMessage.success('保存成功')
    editVisible.value = false
    loadList()
  } catch {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}
async function onDelete(row) {
  await ElMessageBox.confirm(`确定删除知识库「${row.name}」?`, '提示', { type: 'warning' })
  await kbApi.remove(row.id)
  ElMessage.success('已删除')
  loadList()
}

// ───── 文档 ─────
function openDocs(row) {
  current.value = row
  docForm.name = ''
  docForm.content = ''
  docForm.sourceType = 'TEXT'
  docsVisible.value = true
  loadDocs()
}
async function loadDocs() {
  if (!current.value) return
  docsLoading.value = true
  try {
    const { data } = await kbApi.listDocs(current.value.id)
    docs.value = data || []
    schedulePolling()
  } finally {
    docsLoading.value = false
  }
}
async function onUpload() {
  if (!docForm.name || !docForm.content) {
    ElMessage.warning('请填写文档名和内容')
    return
  }
  uploading.value = true
  try {
    await kbApi.uploadDoc(current.value.id, { ...docForm })
    ElMessage.success('已提交,后台摄入中')
    docForm.name = ''
    docForm.content = ''
    loadDocs()
  } catch {
    /* 拦截器已提示 */
  } finally {
    uploading.value = false
  }
}

// 有 PENDING/PROCESSING 文档时每 2s 轮询一次进度
function schedulePolling() {
  stopPolling()
  if (docs.value.some((d) => isProcessing(d.status))) {
    pollTimer = setTimeout(loadDocs, 2000)
  }
}
function stopPolling() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

function isProcessing(s) {
  return s === 'PENDING' || s === 'PROCESSING'
}
function statusTag(s) {
  return { COMPLETED: 'success', FAILED: 'danger', PROCESSING: 'warning', PENDING: 'info' }[s] || 'info'
}
function statusText(s) {
  return { PENDING: '待处理', PROCESSING: '处理中', COMPLETED: '已完成', FAILED: '失败' }[s] || s
}

onMounted(loadList)
onUnmounted(stopPolling)
</script>

<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.toolbar .page-title {
  margin: 0;
}
.doc-upload .upload-hint {
  margin-left: 12px;
  font-size: 12px;
}
.spin {
  animation: rotate 1s linear infinite;
}
@keyframes rotate {
  to {
    transform: rotate(360deg);
  }
}
</style>
