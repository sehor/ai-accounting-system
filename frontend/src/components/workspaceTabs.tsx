import { createContext, useContext, type ReactNode } from 'react'

export type WorkspaceTabsApi = {
  closeTab: (tabId: string, options?: { discardChanges?: boolean }) => void
}

const WorkspaceTabsContext = createContext<WorkspaceTabsApi>({ closeTab: () => {} })

export function WorkspaceTabsProvider({ children, value }: { children: ReactNode; value: WorkspaceTabsApi }) {
  return <WorkspaceTabsContext.Provider value={value}>{children}</WorkspaceTabsContext.Provider>
}

export function useWorkspaceTabs(): WorkspaceTabsApi {
  return useContext(WorkspaceTabsContext)
}
