<template>
  <div class="auth-wrap">
    <el-card class="auth-card">
      <div class="brand">
        <el-icon :size="30" color="#409eff"><OfficeBuilding /></el-icon>
        <h1>租户入驻</h1>
        <p class="muted">创建企业租户 + 首个管理员账号</p>
      </div>

      <el-form :model="form" :rules="rules" ref="formRef" label-position="top" size="large">
        <el-form-item label="企业名称" prop="tenantName">
          <el-input v-model="form.tenantName" placeholder="如:Acme 客服" />
        </el-form-item>
        <el-form-item label="租户编码(全局唯一)" prop="tenantCode">
          <el-input v-model="form.tenantCode" placeholder="如:acme(小写字母)" />
        </el-form-item>
        <el-form-item label="管理员登录名" prop="username">
          <el-input v-model="form.username" placeholder="全局唯一登录名" />
        </el-form-item>
        <el-form-item label="管理员昵称" prop="nickName">
          <el-input v-model="form.nickName" placeholder="显示名" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" show-password placeholder="登录密码" />
        </el-form-item>
        <el-button type="primary" class="submit" :loading="loading" @click="onSubmit">创建租户</el-button>
      </el-form>

      <div class="footer">
        <span class="muted">已有账号?</span>
        <el-link type="primary" @click="$router.push('/login')">返回登录</el-link>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { OfficeBuilding } from '@element-plus/icons-vue'
import { authApi } from '@/api'

const router = useRouter()
const formRef = ref()
const loading = ref(false)
const form = reactive({
  tenantName: '', tenantCode: '', username: '', nickName: '', password: ''
})
const rules = {
  tenantName: [{ required: true, message: '请输入企业名称', trigger: 'blur' }],
  tenantCode: [{ required: true, message: '请输入租户编码', trigger: 'blur' }],
  username: [{ required: true, message: '请输入管理员登录名', trigger: 'blur' }],
  nickName: [{ required: true, message: '请输入昵称', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function onSubmit() {
  await formRef.value.validate()
  loading.value = true
  try {
    await authApi.register({ ...form })
    ElMessage.success('入驻成功,请用管理员账号登录')
    router.push({ name: 'login' })
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
  padding: 20px 0;
  overflow-y: auto;
}
.auth-card {
  width: 420px;
}
.brand {
  text-align: center;
  margin-bottom: 12px;
}
.brand h1 {
  font-size: 20px;
  margin: 6px 0 2px;
}
.submit {
  width: 100%;
}
.footer {
  text-align: center;
  margin-top: 12px;
  display: flex;
  gap: 6px;
  justify-content: center;
}
</style>
