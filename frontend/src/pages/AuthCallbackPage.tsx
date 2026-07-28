import { Result, Spin } from 'antd'
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthProvider'
import { finishOidcLogin } from '../auth/session'

export function AuthCallbackPage() {
  const navigate = useNavigate()
  const { signIn } = useAuth()
  const [error, setError] = useState<string>()

  useEffect(() => {
    finishOidcLogin().then((session) => {
      signIn(session)
      navigate('/ledgers', { replace: true })
    }).catch(() => setError('登录回调失败，请重新登录。'))
  }, [navigate, signIn])

  return error ? <Result status="error" title={error} /> : <div style={{ minHeight: '100vh', display: 'grid', placeItems: 'center' }}><Spin tip="正在完成登录…" /></div>
}
