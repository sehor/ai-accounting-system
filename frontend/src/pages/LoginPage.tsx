import { Alert, Button, Card, Form, Input, Space, Typography } from 'antd'
import { LoginOutlined } from '@ant-design/icons'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { isOidcConfigured, startOidcLogin } from '../auth/session'
import { useAuth } from '../auth/AuthProvider'

export function LoginPage() {
  const navigate = useNavigate(); const { signIn } = useAuth(); const [error, setError] = useState<string>()
  const localEnabled = import.meta.env.DEV && import.meta.env.VITE_LOCAL_AUTH_ENABLED === 'true'
  const submitLocal = ({ userId }: { userId: string }) => { signIn({ localUserId: userId.trim() }); navigate('/ledgers', { replace: true }) }
  const loginOidc = async () => { try { await startOidcLogin() } catch { setError('无法打开身份认证服务，请检查 OIDC 配置。') } }
  return <main style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', padding: 24 }}><Card style={{ width: '100%', maxWidth: 440 }}><Space direction="vertical" size={20} style={{ width: '100%' }}><div><Typography.Title level={1} style={{ color: '#153b5b', marginBottom: 4 }}>AI 财务系统</Typography.Title><Typography.Text>登录财务工作台</Typography.Text></div>{error && <Alert role="alert" type="error" message={error} />}{isOidcConfigured() && <Button type="primary" block icon={<LoginOutlined />} onClick={loginOidc}>使用企业账号登录</Button>}{localEnabled && <><Typography.Title level={2} style={{ marginBottom: 0 }}>本地开发登录</Typography.Title><Form layout="vertical" onFinish={submitLocal}><Form.Item name="userId" label="用户 UUID" rules={[{ required: true, message: '请输入用户 UUID' }, { pattern: /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i, message: '请输入有效 UUID' }]}><Input placeholder="00000000-0000-0000-0000-000000000001" autoComplete="off" /></Form.Item><Button htmlType="submit" block>进入本地工作台</Button></Form></>}{!isOidcConfigured() && !localEnabled && <Alert type="warning" message="未配置登录方式" description="请配置 OIDC，或仅在本地开发环境开启本地登录。" />}<Typography.Text type="secondary" style={{ fontSize: 12 }}>生产环境必须使用 OIDC；本地登录只用于联调。</Typography.Text><Link to="/ledgers">查看前端路由</Link></Space></Card></main>
}
