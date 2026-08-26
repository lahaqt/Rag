import type { ReactNode } from 'react'

type NavigationItem = { id: string; label: string }

type ApplicationShellProps = {
  admin: boolean
  activePage: string
  navigation: NavigationItem[]
  userId: string
  canAdmin: boolean
  busy: boolean
  title: string
  status: string
  onNavigate: (id: string) => void
  onDismissStatus: () => void
  onSignOut: () => void
  children: ReactNode
}

export function ApplicationShell(props: ApplicationShellProps) {
  return <div className="app-shell">
    <a className="skip-link" href="#main-content">Skip to content</a>
    <aside className="sidebar">
      <a className="brand" href={props.admin ? '/admin' : '/app'}><span className="brand-mark">EA</span><span>Engineering<br />Authoring Coach</span></a>
      <nav aria-label="Primary navigation">{props.navigation.map(item => <button key={item.id} className={props.activePage === item.id ? 'nav-item active' : 'nav-item'} onClick={() => props.onNavigate(item.id)}>{item.label}</button>)}</nav>
      <div className="sidebar-footer"><span className="user-dot" />{props.userId}{props.canAdmin && !props.admin && <a href="/admin">Open administration</a>}{props.admin && <a href="/app">Student view</a>}<button className="sign-out" onClick={props.onSignOut}>Sign out</button></div>
    </aside>
    <main id="main-content" className="main-content">
      <header className="topbar"><div><p className="eyebrow">{props.admin ? 'Administration console' : 'Evidence-grounded learning'}</p><h1>{props.title}</h1></div><span className="status-pill">{props.busy ? 'Working…' : 'Ready'}</span></header>
      {props.status && <div className="toast" role="status">{props.status}<button aria-label="Dismiss status" onClick={props.onDismissStatus}>×</button></div>}
      {props.children}
    </main>
  </div>
}
