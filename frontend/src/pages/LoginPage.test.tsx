import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiFetch } from '../api/client'
import { AuthProvider } from '../auth/AuthProvider'
import { LoginPage } from './LoginPage'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return { ...actual, apiFetch: vi.fn() }
})

describe('local login', () => {
  beforeAll(() => {
    window.matchMedia = vi.fn().mockImplementation(() => ({
      matches: false,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }))
  })

  beforeEach(() => {
    sessionStorage.clear()
    localStorage.clear()
    vi.mocked(apiFetch).mockReset()
  })

  afterEach(cleanup)

  it('shows the backend rejection without creating a session or leaving the login page', async () => {
    vi.mocked(apiFetch).mockRejectedValue(new ApiError(401, { code: 'UNAUTHENTICATED' }))
    render(<MemoryRouter><AuthProvider><LoginPage /></AuthProvider></MemoryRouter>)

    fireEvent.change(screen.getByLabelText('用户名'), { target: { value: 'alice' } })
    fireEvent.click(screen.getByRole('button', { name: '进入本地工作台' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('后端未开启本地登录')
    expect(sessionStorage.getItem('ai-accounting.session')).toBeNull()
  })

  it('creates the session only after the backend accepts the username', async () => {
    const canonicalUserId = '00000000-0000-4000-8000-000000000123'
    vi.mocked(apiFetch).mockResolvedValue({
      id: canonicalUserId,
      issuer: 'local',
      subject: 'admin',
      displayName: 'admin',
      email: null,
      status: 'ACTIVE',
    })
    render(<MemoryRouter><AuthProvider><LoginPage /></AuthProvider></MemoryRouter>)

    fireEvent.click(screen.getByRole('button', { name: '进入本地工作台' }))

    await waitFor(() => expect(sessionStorage.getItem('ai-accounting.session')).not.toBeNull())
    expect(apiFetch).toHaveBeenCalledWith('/me', expect.objectContaining({ localUserName: 'admin' }))
    expect(JSON.parse(sessionStorage.getItem('ai-accounting.session')!)).toMatchObject({
      localUserId: canonicalUserId,
      localUserName: 'admin',
    })
  })
})
