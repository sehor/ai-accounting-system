// @vitest-environment node

import { describe, expect, it } from 'vitest'
import config from './vite.config'

describe('Vite dev server', () => {
  it('listens on all local interfaces at the stable frontend port', () => {
    expect(config.server).toMatchObject({ host: '0.0.0.0', port: 5173, strictPort: true })
  })
})
