import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useNavigate, useSearchParams, type NavigateOptions } from 'react-router-dom'

type SearchSetter = ReturnType<typeof useSearchParams>[1]

type WorkspaceSearchContextValue = {
  search: URLSearchParams
  setSearch: SearchSetter
}

const WorkspaceSearchContext = createContext<WorkspaceSearchContextValue | null>(null)

function parseLocation(location: string) {
  const queryIndex = location.indexOf('?')
  return new URLSearchParams(queryIndex >= 0 ? location.slice(queryIndex + 1) : '')
}

export function WorkspaceTabSearchProvider({
  tabId,
  location,
  activeTabRef,
  children,
}: {
  tabId: string
  location: string
  activeTabRef: { current: string | undefined }
  children: ReactNode
}) {
  const navigate = useNavigate()
  const pathname = location.split('?')[0]
  const [search, setSearchState] = useState(() => parseLocation(location))
  const searchRef = useRef(search)

  useEffect(() => {
    const next = parseLocation(location)
    searchRef.current = next
    setSearchState(next)
  }, [location])

  const setSearch = useCallback<SearchSetter>((nextInit, options?: NavigateOptions) => {
    const next = typeof nextInit === 'function'
      ? nextInit(new URLSearchParams(searchRef.current))
      : nextInit
    const nextSearch = new URLSearchParams(next as string | string[][] | Record<string, string> | URLSearchParams)
    searchRef.current = nextSearch
    setSearchState(nextSearch)
    if (activeTabRef.current === tabId) {
      const query = nextSearch.toString()
      navigate(`${pathname}${query ? `?${query}` : ''}`, options)
    }
  }, [activeTabRef, navigate, pathname, tabId])

  const value = useMemo(() => ({ search, setSearch }), [search, setSearch])
  return <WorkspaceSearchContext.Provider value={value}>{children}</WorkspaceSearchContext.Provider>
}

export function useWorkspaceSearchParams() {
  const workspace = useContext(WorkspaceSearchContext)
  const router = useSearchParams()
  return workspace ? [workspace.search, workspace.setSearch] as const : router
}
