import { beforeEach, describe, expect, it } from 'vitest'
import { createLocalSession, isLocalAuthEnabled } from './session'

describe('local authentication', () => {
  beforeEach(() => localStorage.clear())

  it('is enabled by default during development', () => {
    expect(isLocalAuthEnabled()).toBe(true)
  })

  it('creates a stable browser-local identity from a username', () => {
    const first = createLocalSession(' Alice ')
    const second = createLocalSession('alice')

    expect(first).toMatchObject({ localUserName: 'Alice' })
    expect(first.localUserId).toMatch(/^[0-9a-f-]{36}$/)
    expect(second.localUserId).toBe(first.localUserId)
  })
})
