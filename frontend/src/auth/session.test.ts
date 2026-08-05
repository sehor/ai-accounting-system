import { describe, expect, it } from 'vitest'
import { createLocalSession, isLocalAuthEnabled } from './session'

describe('local authentication', () => {
  it('is enabled by default during development', () => {
    expect(isLocalAuthEnabled()).toBe(true)
  })

  it('leaves local user identity assignment to the backend', () => {
    const first = createLocalSession(' Alice ')
    const second = createLocalSession('alice')

    expect(first).toEqual({ localUserName: 'Alice' })
    expect(second).toEqual({ localUserName: 'alice' })
  })
})
