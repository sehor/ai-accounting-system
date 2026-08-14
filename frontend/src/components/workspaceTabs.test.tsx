import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { useWorkspaceTabs, WorkspaceTabsProvider } from './workspaceTabs'

function CloseButton() {
  const { closeTab } = useWorkspaceTabs()
  return <button onClick={() => closeTab('voucher-1', { discardChanges: true })}>关闭凭证标签</button>
}

describe('WorkspaceTabsProvider', () => {
  it('exposes the typed close-tab API to a voucher page', () => {
    const closeTab = vi.fn()
    render(<WorkspaceTabsProvider value={{ closeTab }}><CloseButton /></WorkspaceTabsProvider>)

    fireEvent.click(screen.getByRole('button', { name: '关闭凭证标签' }))

    expect(closeTab).toHaveBeenCalledWith('voucher-1', { discardChanges: true })
  })
})
