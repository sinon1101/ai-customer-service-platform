<template>
  <div class="auth-wrap">
    <el-card class="auth-card">
      <div class="brand">
        <el-icon :size="34" color="#409eff"><ChatDotRound /></el-icon>
        <h1>AI 智能客服平台</h1>
        <p class="muted">多租户 · 高并发治理 · RAG 问答</p>
      </div>

      <el-form :model="form" :rules="rules" ref="formRef" @submit.prevent="onSubmit" size="large">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="登录名" :prefix-icon="User" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="form.password" type="password" placeholder="密码" show-password
            :prefix-icon="Lock" @keyup.enter="onSubmit" />
        </el-form-item>
        <el-button type="primary" class="submit" :loading="loading" @click="onSubmit">登 录</el-button>
      </el-form>

      <div class="footer">
        <span class="muted">还没有企业账号?</span>
        <el-link type="primary" @click="$router.push('/register')">租户入驻</el-link>
      </div>

      <el-alert type="info" :closable="false" class="demo">
        <template #title>
          <div class="demo-title">演示账号(密码均为 123456)</div>
          <div class="demo-line" v-for="acc in demoAccounts" :key="acc.u">
            <code @click="fill(acc.u)">{{ acc.u }}</code>
            <span class="muted">{{ acc.desc }}</span>
          </div>
        </template>
      </el-alert>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { authApi } from '@/api'
import { useAuthStore } from '@/store/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const formRef = ref()
const loading = ref(false)
const form = reactive({ username: '', password: '' })
const rules = {
  username: [{ required: true, message: '请输入登录名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const demoAccounts = [
  { u: 'acme_admin', desc: 'Acme 管理员(可看看板)' },
  { u: 'acme_agent1', desc: 'Acme 坐席小A' },
  { u: 'acme_agent2', desc: 'Acme 坐席小B' },
  { u: 'globex_admin', desc: 'Globex 管理员(隔离演示)' }
]

function fill(username) {
  form.username = username
  form.password = '123456'
}

async function onSubmit() {
  await formRef.value.validate()
  loading.value = true
  try {
    const { data: token } = await authApi.login({ ...form })
    auth.setToken(token)
    // 拿登录态(含 tenantId / role),用于侧边栏与角色守卫
    const { data: user } = await authApi.me()
    auth.setUser(user)
    ElMessage.success(`欢迎回来,${user.nickName || user.username}`)
    router.push(route.query.redirect || '/chat')
  } catch {
    /* 错误已由拦截器弹出 */
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.auth-wrap {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1f2937 0%, #374151 60%, #4b5563 100%);
}
.auth-card {
  width: 400px;
  padding: 8px 12px;
}
.brand {
  text-align: center;
  margin-bottom: 20px;
}
.brand h1 {
  font-size: 22px;
  margin: 8px 0 4px;
}
.submit {
  width: 100%;
}
.footer {
  text-align: center;
  margin-top: 14px;
  display: flex;
  gap: 6px;
  justify-content: center;
}
.demo {
  margin-top: 18px;
}
.demo-title {
  font-weight: 600;
  margin-bottom: 6px;
}
.demo-line {
  display: flex;
  gap: 8px;
  align-items: center;
  line-height: 1.9;
}
.demo-line code {
  background: #eef2ff;
  padding: 0 6px;
  border-radius: 4px;
  cursor: pointer;
  color: #4338ca;
}
</style>
