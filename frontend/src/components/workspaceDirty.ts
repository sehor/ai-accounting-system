const dirtyTabs = new Set<string>()

export function setWorkspaceTabDirty(tabId: string, dirty: boolean) {
  if (dirty) dirtyTabs.add(tabId)
  else dirtyTabs.delete(tabId)
}

export function isWorkspaceTabDirty(tabId: string) {
  return dirtyTabs.has(tabId)
}

export function clearWorkspaceTabDirty(tabId: string) {
  dirtyTabs.delete(tabId)
}
