import { Alert, Button, Card, Form, Input, Space, Typography } from 'antd'
import { LoginOutlined } from '@ant-design/icons'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ApiError, apiFetch } from '../api/client'
import type { User } from '../api/types'
import { createLocalSession, isLocalAuthEnabled, isOidcConfigured, startOidcLogin } from '../auth/session'
import { useAuth } from '../auth/AuthProvider'

export function LoginPage() {
  const navigate = useNavigate()
  const { signIn } = useAuth()
  const [error, setError] = useState<string>()
  const [submitting, setSubmitting] = useState(false)
  const localEnabled = isLocalAuthEnabled()

  const submitLocal = async ({ username }: { username: string }) => {
    setError(undefined)
    setSubmitting(true)
    const session = createLocalSession(username)
    try {
      const user = await apiFetch<User>('/me', session)
      signIn({ ...session, localUserId: user.id })
      navigate('/ledgers', { replace: true })
    } catch (cause) {
      setError(cause instanceof ApiError && cause.problem.code === 'UNKNOWN_LOCAL_USER'
        ? '用户不存在，请确认用户名。'
        : cause instanceof ApiError && cause.status === 401
        ? '后端未开启本地登录，请设置 LOCAL_USER_HEADER_ENABLED=true 并重启后端。'
        : '无法连接后端，请确认后端已在 8080 端口启动。')
    } finally {
      setSubmitting(false)
    }
  }

  const loginOidc = async () => { try { await startOidcLogin() } catch { setError('无法打开身份认证服务，请检查 OIDC 配置。') } }
  return <main style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', padding: 24 }}><Card style={{ width: '100%', maxWidth: 440 }}><Space direction="vertical" size={20} style={{ width: '100%' }}><div><Typography.Title level={1} style={{ color: '#153b5b', marginBottom: 4 }}>AI 财务系统</Typography.Title><Typography.Text>登录财务工作台</Typography.Text></div>{error && <Alert role="alert" type="error" message={error} />}{isOidcConfigured() && <Button type="primary" block icon={<LoginOutlined />} onClick={loginOidc}>使用企业账号登录</Button>}{localEnabled && <><Typography.Title level={2} style={{ marginBottom: 0 }}>本地开发登录</Typography.Title><Form layout="vertical" initialValues={{ username: 'admin' }} onFinish={submitLocal}><Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }, { pattern: /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/, message: '用户名只能包含字母、数字、点、下划线和短横线' }]}><Input autoComplete="username" maxLength={64} /></Form.Item><Button htmlType="submit" block loading={submitting}>进入本地工作台</Button></Form></>}{!isOidcConfigured() && !localEnabled && <Alert type="warning" message="未配置登录方式" description="请配置 OIDC，或仅在本地开发环境开启本地登录。" />}<Typography.Text type="secondary" style={{ fontSize: 12 }}>本地开发登录无需密码；生产环境必须使用 OIDC。</Typography.Text><Link to="/ledgers">查看前端路由</Link></Space></Card></main>
}
