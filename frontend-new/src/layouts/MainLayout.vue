<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="logo">
        <el-icon><ChatDotRound /></el-icon>
        <span>AI 智能客服</span>
      </div>
      <el-menu :default-active="$route.path" router class="menu" background-color="#1f2937"
        text-color="#cbd5e1" active-text-color="#fff">
        <el-menu-item index="/chat">
          <el-icon><Comment /></el-icon><span>智能问答</span>
        </el-menu-item>
        <el-menu-item index="/kb">
          <el-icon><Collection /></el-icon><span>知识库管理</span>
        </el-menu-item>
        <el-menu-item index="/agent">
          <el-icon><Headset /></el-icon><span>坐席工作台</span>
        </el-menu-item>
        <el-menu-item index="/dashboard" v-if="auth.isAdmin">
          <el-icon><DataAnalysis /></el-icon><span>统计看板</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="header-title">{{ $route.meta.title || '' }}</div>
        <div class="header-right">
          <el-tag size="small" type="info" effect="plain">租户 #{{ auth.user?.tenantId }}</el-tag>
          <el-tag size="small" :type="auth.isAdmin ? 'danger' : 'success'" effect="plain">
            {{ auth.user?.role }}
          </el-tag>
          <span class="user-name">{{ auth.displayName }}</span>
          <el-button size="small" text @click="onLogout">
            <el-icon><SwitchButton /></el-icon>退出
          </el-button>
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/store/auth'
import { authApi } from '@/api'

const auth = useAuthStore()
const router = useRouter()

async function onLogout() {
  try {
    await authApi.logout()
  } catch {
    /* 即使后端调用失败也强制本地登出 */
  }
  auth.clear()
  ElMessage.success('已退出登录')
  router.push({ name: 'login' })
}
</script>

<style scoped>
.layout {
  height: 100%;
}
.aside {
  background: #1f2937;
  display: flex;
  flex-direction: column;
}
.logo {
  height: 60px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 20px;
  color: #fff;
  font-size: 17px;
  font-weight: 600;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}
.menu {
  border-right: none;
  flex: 1;
}
.header {
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #ebeef5;
}
.header-title {
  font-size: 16px;
  font-weight: 600;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}
.user-name {
  font-weight: 500;
}
.main {
  padding: 20px;
  overflow-y: auto;
}
</style>
