import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import './App.css'

export type Message = { id: number; role: 'user' | 'assistant'; content: string; feedbackStatus?: 'submitting' | 'submitted' | 'error'; feedbackRating?: 'up' | 'down' }
export type Conversation = { id: string; title: string; summary: string; time: string; messages: Message[] }

type Session = { userId: string; roles: string[] }
type Outcome = { id: string; code: string; description: string; displayOrder: number; active: boolean }
type Material = { id: string; documentId: string; fileName: string; contentType: string; size: number; status: string; chunkCount: number; errorMessage: string; uploadedAt?: string }
type Course = { id: string; code: string; name: string; description: string; published: boolean; materialCount: number; outcomeCount: number }
type CourseDetail = Course & { archived?: boolean; outcomes: Outcome[]; materials: Material[] }
type Project = { id: string; courseId: string; title: string; description: string; learningOutcomeIds: string[]; createdAt: string; updatedAt: string }
type ArtifactType = 'TECHNICAL_INTERPRETATION' | 'SUPPLEMENTARY_MATERIAL' | 'MULTIPLE_CHOICE_QUESTION'
type Artifact = { id: string; projectId: string; type: ArtifactType; title: string; draft: Record<string, unknown>; draftVersion: number; createdAt: string; updatedAt: string }
type Revision = { id: string; artifactId: string; revisionNumber: number; title: string; draft: Record<string, unknown>; createdAt: string }
type Evidence = { index: number; documentName: string; excerpt: string; score: number }
type Dimension = { key: string; label: string; score: number | null; finding: string; evidenceRefs: number[]; reflectiveQuestions: string[]; revisionStrategies: string[] }
type ToolObservation = { serverId: string; toolName: string; success: boolean; content: string }
type Review = { id: string; revisionId: string; status: 'COMPLETED' | 'INSUFFICIENT_EVIDENCE' | 'FAILED'; overallScore: number | null; dimensions: Dimension[]; evidence: Evidence[]; toolObservations: ToolObservation[]; summary: string; failureReason: string; createdAt: string }
type ArtifactOverview = { artifact: Artifact; firstRevision: Revision | null; latestRevision: Revision | null; firstReview: Review | null; latestReview: Review | null; revisionCount: number; scoreDelta: number | null }
type ProjectOverview = { project: Project; course: Course; artifacts: ArtifactOverview[]; metrics: { artifactCount: number; revisionCount: number; reviewedArtifactCount: number; averageScoreDelta: number | null } }
type ModelProfile = { id: string; name: string; protocol: string; baseUrl: string; model: string; apiKeyConfigured: boolean; apiKeyHint: string; temperature: number; maxTokens: number; enabled: boolean; active: boolean }
type McpServer = { id: string; name: string; transport: string; endpoint: string; command: string; enabled: boolean; readOnly: boolean; status: string; lastError: string; tools: Array<{ name: string }> }
type ProjectForm = { courseId: string; title: string; description: string; learningOutcomeIds: string[] }
type CourseForm = { code: string; name: string; description: string; published: boolean }
type OutcomeForm = { code: string; description: string }
type ModelForm = { name: string; protocol: string; baseUrl: string; model: string; apiKey: string; temperature: number; maxTokens: number; enabled: boolean }
type McpForm = { name: string; endpoint: string; enabled: boolean }

const api = async <T,>(path: string, options?: RequestInit): Promise<T> => {
  const response = await fetch(path, { headers: { 'Content-Type': 'application/json', ...(options?.headers ?? {}) }, ...options })
  if (!response.ok) {
    const text = await response.text()
    throw new Error(text || `${response.status} ${response.statusText}`)
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

const defaultDraft = (type: ArtifactType): Record<string, unknown> => type === 'MULTIPLE_CHOICE_QUESTION'
  ? { stem: '', options: [{ key: 'A', text: '' }, { key: 'B', text: '' }, { key: 'C', text: '' }, { key: 'D', text: '' }], correctOptionKey: 'A', answerRationale: '', intendedDifficulty: 'MEDIUM' }
  : { body: '' }

const typeLabel: Record<ArtifactType, string> = {
  TECHNICAL_INTERPRETATION: 'Technical interpretation',
  SUPPLEMENTARY_MATERIAL: 'Supplementary learning material',
  MULTIPLE_CHOICE_QUESTION: 'Multiple-choice question',
}

function App() {
  const adminRoute = window.location.pathname.startsWith('/admin')
  const [session, setSession] = useState<Session | null>(null)
  const [courses, setCourses] = useState<Course[]>([])
  const [projects, setProjects] = useState<Project[]>([])
  const [activeProjectId, setActiveProjectId] = useState('')
  const [activeArtifact, setActiveArtifact] = useState<Artifact | null>(null)
  const [artifacts, setArtifacts] = useState<Artifact[]>([])
  const [revisions, setRevisions] = useState<Revision[]>([])
  const [reviews, setReviews] = useState<Review[]>([])
  const [projectOverview, setProjectOverview] = useState<ProjectOverview | null>(null)
  const [studentPage, setStudentPage] = useState<'projects' | 'course' | 'workspace' | 'history' | 'report'>('projects')
  const [adminPage, setAdminPage] = useState<'dashboard' | 'courses' | 'models' | 'mcp' | 'audit'>('dashboard')
  const [selectedCourse, setSelectedCourse] = useState<CourseDetail | null>(null)
  const [status, setStatus] = useState('')
  const [busy, setBusy] = useState(false)
  const [draftDirty, setDraftDirty] = useState(false)
  const [newProject, setNewProject] = useState<ProjectForm>({ courseId: '', title: '', description: '', learningOutcomeIds: [] })
  const [newCourse, setNewCourse] = useState<CourseForm>({ code: '', name: '', description: '', published: true })
  const [newOutcome, setNewOutcome] = useState<OutcomeForm>({ code: '', description: '' })
  const [models, setModels] = useState<ModelProfile[]>([])
  const [newModel, setNewModel] = useState<ModelForm>({ name: '', protocol: 'OPENAI_COMPATIBLE', baseUrl: '', model: '', apiKey: '', temperature: 0.2, maxTokens: 1200, enabled: true })
  const [mcpServers, setMcpServers] = useState<McpServer[]>([])
  const [newMcp, setNewMcp] = useState<McpForm>({ name: '', endpoint: '', enabled: true })

  const activeProject = projects.find(project => project.id === activeProjectId) ?? null
  const activeCourse = selectedCourse ?? null
  const canAdmin = session?.roles.includes('ADMIN') ?? false

  const loadCore = async () => {
    const [courseRows, projectRows] = await Promise.all([api<Course[]>('/api/authoring/courses'), api<Project[]>('/api/authoring/projects')])
    setCourses(courseRows)
    setProjects(projectRows)
    setActiveProjectId(current => current || projectRows[0]?.id || '')
  }

  useEffect(() => {
    void (async () => {
      try {
        setSession(await api<Session>('/api/session'))
        await loadCore()
      } catch (error) {
        setStatus(error instanceof Error ? `Unable to connect: ${error.message}` : 'Unable to connect to the service.')
      }
    })()
  }, [])

  useEffect(() => {
    if (!activeProjectId) return
    void (async () => {
      try {
        const rows = await api<Artifact[]>(`/api/authoring/projects/${activeProjectId}/artifacts`)
        setArtifacts(rows)
        setActiveArtifact(current => rows.find(item => item.id === current?.id) ?? rows[0] ?? null)
      } catch (error) { setStatus(error instanceof Error ? error.message : 'Unable to load artifacts.') }
    })()
  }, [activeProjectId])

  useEffect(() => {
    if (!activeArtifact) {
      void Promise.resolve().then(() => { setRevisions([]); setReviews([]) })
      return
    }
    void (async () => {
      try {
        const rows = await api<Revision[]>(`/api/authoring/artifacts/${activeArtifact.id}/revisions`)
        setRevisions(rows)
        const reviewRows = await Promise.all(rows.map(revision => api<Review[]>(`/api/authoring/revisions/${revision.id}/reviews`)))
        setReviews(reviewRows.flat())
      } catch (error) { setStatus(error instanceof Error ? error.message : 'Unable to load revision history.') }
    })()
  }, [activeArtifact])

  useEffect(() => {
    if (studentPage !== 'report' || !activeProjectId) return
    void api<ProjectOverview>(`/api/authoring/projects/${activeProjectId}/overview`)
      .then(setProjectOverview)
      .catch(error => setStatus(error instanceof Error ? error.message : 'Unable to load project report.'))
  }, [studentPage, activeProjectId])

  useEffect(() => {
    if (!draftDirty || !activeArtifact) return
    const timer = window.setTimeout(() => { void saveDraft() }, 800)
    return () => window.clearTimeout(timer)
  }, [draftDirty, activeArtifact])

  useEffect(() => {
    if (!newProject.courseId) return
    void api<CourseDetail>(`/api/authoring/courses/${newProject.courseId}`).then(course => {
      setSelectedCourse(course)
      setNewProject(current => ({ ...current, learningOutcomeIds: course.outcomes.filter(item => item.active).map(item => item.id) }))
    }).catch(error => setStatus(error instanceof Error ? error.message : 'Unable to load course.'))
  }, [newProject.courseId])

  const selectCourse = async (courseId: string, admin = false) => {
    try {
      const path = admin ? `/api/admin/courses/${courseId}` : `/api/authoring/courses/${courseId}`
      setSelectedCourse(await api<CourseDetail>(path))
    } catch (error) { setStatus(error instanceof Error ? error.message : 'Unable to load course.') }
  }

  async function saveDraft() {
    if (!activeArtifact || !draftDirty) return activeArtifact
    try {
      const saved = await api<Artifact>(`/api/authoring/artifacts/${activeArtifact.id}/draft`, {
        method: 'PATCH', body: JSON.stringify({ baseVersion: activeArtifact.draftVersion, title: activeArtifact.title, draft: activeArtifact.draft }),
      })
      setActiveArtifact(saved)
      setArtifacts(items => items.map(item => item.id === saved.id ? saved : item))
      setDraftDirty(false)
      setStatus('Draft saved.')
      return saved
    } catch (error) {
      setStatus(error instanceof Error ? error.message : 'Draft save failed.')
      return undefined
    }
  }

  const updateDraft = (draft: Record<string, unknown>) => {
    if (!activeArtifact) return
    setActiveArtifact({ ...activeArtifact, draft })
    setDraftDirty(true)
  }

  const createProject = async (event: FormEvent) => {
    event.preventDefault()
    try {
      setBusy(true)
      const project = await api<Project>('/api/authoring/projects', { method: 'POST', body: JSON.stringify(newProject) })
      setProjects(items => [project, ...items])
      setActiveProjectId(project.id)
      setNewProject({ courseId: '', title: '', description: '', learningOutcomeIds: [] })
      setStatus('Project created. Start by adding an artifact.')
      setStudentPage('workspace')
    } catch (error) { setStatus(error instanceof Error ? error.message : 'Unable to create project.') } finally { setBusy(false) }
  }

  const createArtifact = async (type: ArtifactType) => {
    if (!activeProject) return
    try {
      setBusy(true)
      const artifact = await api<Artifact>(`/api/authoring/projects/${activeProject.id}/artifacts`, { method: 'POST', body: JSON.stringify({ type, title: typeLabel[type], draft: defaultDraft(type) }) })
      setArtifacts(items => [artifact, ...items])
      setActiveArtifact(artifact)
      setStudentPage('workspace')
      setStatus('Artifact created.')
    } catch (error) { setStatus(error instanceof Error ? error.message : 'Unable to create artifact.') } finally { setBusy(false) }
  }

  const requestCoaching = async () => {
    if (!activeArtifact) return
    try {
      setBusy(true)
      const saved = await saveDraft()
      if (!saved) return
      const revision = await api<Revision>(`/api/authoring/artifacts/${saved.id}/revisions`, { method: 'POST' })
      const review = await api<Review>(`/api/authoring/revisions/${revision.id}/reviews`, { method: 'POST' })
      setRevisions(items => [...items, revision])
      setReviews(items => [...items, review])
      setProjectOverview(null)
      setStatus(`Coaching review ${review.status.toLowerCase().replace('_', ' ')}.`)
    } catch (error) { setStatus(error instanceof Error ? error.message : 'Coaching review failed.') } finally { setBusy(false) }
  }

  const submitRating = async (review: Review, rating: number) => {
    try {
      await api<void>(`/api/authoring/reviews/${review.id}/rating`, { method: 'POST', body: JSON.stringify({ pertinence: rating, actionability: rating, educationalValue: rating, comment: '' }) })
      setStatus('Thank you. Your review rating was saved.')
    } catch (error) { setStatus(error instanceof Error ? error.message : 'Unable to save rating.') }
  }

  const downloadReport = async () => {
    if (!activeProject) return
    try {
      const overview = await api<ProjectOverview>(`/api/authoring/projects/${activeProject.id}/overview`)
      setProjectOverview(overview)
      const markdown = [`# ${overview.project.title}`, `Course: ${overview.course.name}`, `Total revisions: ${overview.metrics.revisionCount}`, `Average formative score change: ${formatDelta(overview.metrics.averageScoreDelta)}`, '', ...overview.artifacts.map(item => `## ${item.artifact.title}\nRevisions: ${item.revisionCount}\nFirst score: ${item.firstReview?.overallScore ?? 'Not reviewed'}\nLatest score: ${item.latestReview?.overallScore ?? 'Not reviewed'}\nScore change: ${formatDelta(item.scoreDelta)}\n\n${item.latestReview?.summary ?? 'No coaching review yet.'}`)].join('\n')
      const url = URL.createObjectURL(new Blob([markdown], { type: 'text/markdown' }))
      const anchor = document.createElement('a'); anchor.href = url; anchor.download = `${overview.project.title.replace(/[^a-z0-9]+/gi, '-').toLowerCase()}-report.md`; anchor.click(); URL.revokeObjectURL(url)
    } catch (error) { setStatus(error instanceof Error ? error.message : 'Unable to prepare report.') }
  }

  const loadAdminData = async () => {
    try {
      const [courseRows, modelRows, mcpRows] = await Promise.all([api<Course[]>('/api/admin/courses'), api<ModelProfile[]>('/api/admin/models'), api<McpServer[]>('/api/admin/mcp/servers')])
      setCourses(courseRows); setModels(modelRows); setMcpServers(mcpRows)
    } catch (error) { setStatus(error instanceof Error ? error.message : 'Unable to load administration data.') }
  }

  useEffect(() => {
    if (adminRoute && canAdmin) void Promise.resolve().then(loadAdminData)
  }, [adminRoute, canAdmin])

  const createCourse = async (event: FormEvent) => {
    event.preventDefault()
    try {
      setBusy(true)
      const course = await api<CourseDetail>('/api/admin/courses', { method: 'POST', body: JSON.stringify(newCourse) })
      setCourses(items => [...items, course])
      setSelectedCourse(course)
      setNewCourse({ code: '', name: '', description: '', published: true })
      setStatus('Course created. Add outcomes and course materials next.')
    } catch (error) { setStatus(error instanceof Error ? error.message : 'Unable to create course.') } finally { setBusy(false) }
  }

  const addOutcome = async (event: FormEvent) => {
    event.preventDefault()
    if (!selectedCourse) return
    try {
      const outcomes = await api<Outcome[]>(`/api/admin/courses/${selectedCourse.id}/learning-outcomes`, { method: 'PUT', body: JSON.stringify([...selectedCourse.outcomes.map(item => ({ code: item.code, description: item.description })), newOutcome]) })
      setSelectedCourse({ ...selectedCourse, outcomes })
      setNewOutcome({ code: '', description: '' })
      setStatus('Learning outcome added.')
    } catch (error) { setStatus(error instanceof Error ? error.message : 'Unable to update outcomes.') }
  }

  const uploadMaterial = async (file?: File) => {
    if (!file || !selectedCourse) return
    try {
      const body = new FormData(); body.append('file', file)
      const response = await fetch(`/api/admin/courses/${selectedCourse.id}/materials`, { method: 'POST', body })
      if (!response.ok) throw new Error(await response.text())
      const material = await response.json() as Material
      setSelectedCourse({ ...selectedCourse, materials: [material, ...selectedCourse.materials] })
      setStatus('Course material uploaded and queued for indexing.')
    } catch (error) { setStatus(error instanceof Error ? error.message : 'Unable to upload material.') }
  }

  const createModel = async (event: FormEvent) => {
    event.preventDefault()
    try {
      const model = await api<ModelProfile>('/api/admin/models', { method: 'POST', body: JSON.stringify(newModel) })
      setModels(items => [model, ...items]); setNewModel({ name: '', protocol: 'OPENAI_COMPATIBLE', baseUrl: '', model: '', apiKey: '', temperature: 0.2, maxTokens: 1200, enabled: true }); setStatus('Model profile saved. Test it before activation.')
    } catch (error) { setStatus(error instanceof Error ? error.message : 'Unable to save model profile.') }
  }

  const activateModel = async (model: ModelProfile) => {
    try { const updated = await api<ModelProfile>(`/api/admin/models/${model.id}/activate`, { method: 'POST' }); setModels(items => items.map(item => ({ ...item, active: item.id === updated.id }))); setStatus(`${updated.name} is active for new requests.`) } catch (error) { setStatus(error instanceof Error ? error.message : 'Unable to activate model.') }
  }

  const createMcp = async (event: FormEvent) => {
    event.preventDefault()
    try {
      const server = await api<McpServer>('/api/admin/mcp/servers', { method: 'POST', body: JSON.stringify({ id: '', name: newMcp.name, transport: 'streamable_http', endpoint: newMcp.endpoint, command: '', args: [], environment: {}, workingDirectory: '', bearerToken: '', enabled: newMcp.enabled }) })
      setMcpServers(items => [server, ...items]); setNewMcp({ name: '', endpoint: '', enabled: true }); setStatus('MCP server saved and validated.')
    } catch (error) { setStatus(error instanceof Error ? error.message : 'Unable to create MCP server.') }
  }

  const renderStudent = () => {
    if (studentPage === 'projects') return <ProjectsPage courses={courses} projects={projects} activeProjectId={activeProjectId} onProject={setActiveProjectId} form={newProject} setForm={setNewProject} course={activeCourse} onSubmit={createProject} busy={busy} />
    if (studentPage === 'course') return <CoursePage project={activeProject} course={activeCourse} onLoad={() => activeProject && void selectCourse(activeProject.courseId)} />
    if (studentPage === 'history') return <HistoryPage revisions={revisions} reviews={reviews} />
    if (studentPage === 'report') return <ReportPage project={activeProject} overview={projectOverview} onDownload={downloadReport} />
    return <WorkspacePage project={activeProject} artifacts={artifacts} artifact={activeArtifact} busy={busy} review={reviews.at(-1)} onSelect={setActiveArtifact} onCreate={createArtifact} onDraft={updateDraft} onTitle={title => activeArtifact && (setActiveArtifact({ ...activeArtifact, title }), setDraftDirty(true))} onReview={requestCoaching} onRate={submitRating} />
  }

  const renderAdmin = () => {
    if (!canAdmin) return <section className="empty-state"><h1>Administrator access required</h1><p>Your signed identity is not listed in the configured administrator IDs.</p><a href="/">Return to Authoring Coach</a></section>
    if (adminPage === 'courses') return <AdminCoursesPage courses={courses} selected={selectedCourse} form={newCourse} setForm={setNewCourse} outcome={newOutcome} setOutcome={setNewOutcome} onCreate={createCourse} onSelect={id => void selectCourse(id, true)} onAddOutcome={addOutcome} onUpload={uploadMaterial} />
    if (adminPage === 'models') return <ModelsPage models={models} form={newModel} setForm={setNewModel} onCreate={createModel} onActivate={activateModel} />
    if (adminPage === 'mcp') return <McpPage servers={mcpServers} form={newMcp} setForm={setNewMcp} onCreate={createMcp} />
    if (adminPage === 'audit') return <section className="page-section"><h1>Audit log</h1><p className="muted">Administrative events are recorded server-side without secrets. The first release displays operational activity through the course, model, and MCP status panels.</p></section>
    return <AdminDashboard courses={courses} models={models} servers={mcpServers} />
  }

  return <div className="app-shell">
    <a className="skip-link" href="#main-content">Skip to content</a>
    <aside className="sidebar">
      <a className="brand" href={adminRoute ? '/admin' : '/'}><span className="brand-mark">EA</span><span>Engineering<br />Authoring Coach</span></a>
      <nav aria-label="Primary navigation">
        {(adminRoute ? [['dashboard', 'Dashboard'], ['courses', 'Courses'], ['models', 'Model settings'], ['mcp', 'MCP servers'], ['audit', 'Audit log']] : [['projects', 'My projects'], ['course', 'Course overview'], ['workspace', 'Authoring workspace'], ['history', 'Revision history'], ['report', 'Project report']]).map(([id, label]) => <button key={id} className={(adminRoute ? adminPage : studentPage) === id ? 'nav-item active' : 'nav-item'} onClick={() => adminRoute ? setAdminPage(id as typeof adminPage) : setStudentPage(id as typeof studentPage)}>{label}</button>)}
      </nav>
      <div className="sidebar-footer"><span className="user-dot" />{session?.userId ?? 'Connecting…'}{canAdmin && !adminRoute && <a href="/admin">Open administration</a>}{adminRoute && <a href="/">Student view</a>}</div>
    </aside>
    <main id="main-content" className="main-content">
      <header className="topbar"><div><p className="eyebrow">{adminRoute ? 'Administration console' : 'Evidence-grounded learning'}</p><h1>{adminRoute ? 'System control plane' : activeProject?.title ?? 'Build stronger technical learning resources'}</h1></div><span className="status-pill">{busy ? 'Working…' : 'Ready'}</span></header>
      {status && <div className="toast" role="status">{status}<button aria-label="Dismiss status" onClick={() => setStatus('')}>×</button></div>}
      {adminRoute ? renderAdmin() : renderStudent()}
    </main>
  </div>
}

function ProjectsPage({ courses, projects, activeProjectId, onProject, form, setForm, course, onSubmit, busy }: { courses: Course[]; projects: Project[]; activeProjectId: string; onProject: (id: string) => void; form: ProjectForm; setForm: (form: ProjectForm) => void; course: CourseDetail | null; onSubmit: (event: FormEvent) => void; busy: boolean }) {
  return <div className="two-column"><section className="page-section"><p className="section-kicker">Start here</p><h2>Create a project</h2><p className="muted">Choose a published course. Its evidence base stays behind the scenes while you write.</p><form className="form-grid" onSubmit={onSubmit}><label>Course<select required value={form.courseId} onChange={event => setForm({ ...form, courseId: event.target.value })}><option value="">Select a course</option>{courses.map(course => <option key={course.id} value={course.id}>{course.code} · {course.name}</option>)}</select></label><label>Project title<input required value={form.title} onChange={event => setForm({ ...form, title: event.target.value })} placeholder="e.g. Fluid mechanics learning pack" /></label><label className="full">Purpose<textarea value={form.description} onChange={event => setForm({ ...form, description: event.target.value })} placeholder="What will this resource help a learner understand?" /></label>{course && <fieldset className="full"><legend>Learning outcomes</legend><div className="outcome-list">{course.outcomes.filter(item => item.active).map(outcome => <label className="check-row" key={outcome.id}><input type="checkbox" checked={form.learningOutcomeIds.includes(outcome.id)} onChange={event => setForm({ ...form, learningOutcomeIds: event.target.checked ? [...form.learningOutcomeIds, outcome.id] : form.learningOutcomeIds.filter(id => id !== outcome.id) })} /> <span><strong>{outcome.code}</strong> {outcome.description}</span></label>)}</div></fieldset>}<button className="primary-button full" disabled={busy}>Create project</button></form></section><section className="page-section"><p className="section-kicker">Your work</p><h2>Recent projects</h2><div className="project-list">{projects.length === 0 ? <div className="empty-inline">No projects yet. Create one to begin.</div> : projects.map(project => <button className={project.id === activeProjectId ? 'project-card selected' : 'project-card'} key={project.id} onClick={() => onProject(project.id)}><strong>{project.title}</strong><span>{project.description || 'No description'}</span><small>Updated {new Date(project.updatedAt).toLocaleDateString()}</small></button>)}</div></section></div>
}

function CoursePage({ project, course, onLoad }: { project: Project | null; course: CourseDetail | null; onLoad: () => void }) {
  useEffect(() => { onLoad() }, [project?.courseId])
  if (!project || !course) return <section className="empty-state"><h2>Select a project</h2><p>Choose a project to see its course evidence and learning outcomes.</p></section>
  return <section className="page-section"><p className="section-kicker">Course evidence</p><h2>{course.code} · {course.name}</h2><p className="lead">{course.description || 'This course provides the authoritative evidence used in coaching reviews.'}</p><div className="metric-grid"><article><span>Learning outcomes</span><strong>{course.outcomes.filter(item => item.active).length}</strong></article><article><span>Course materials</span><strong>{course.materials.length}</strong></article><article><span>Ready for review</span><strong>{course.materials.some(material => /ready|indexed|completed/i.test(material.status)) ? 'Yes' : 'Not yet'}</strong></article></div><div className="split-panels"><div><h3>Learning outcomes</h3>{course.outcomes.filter(item => item.active).map(outcome => <p className="outcome-item" key={outcome.id}><b>{outcome.code}</b>{outcome.description}</p>)}</div><div><h3>Course materials</h3>{course.materials.length ? course.materials.map(material => <div className="material-row" key={material.id}><div><strong>{material.fileName}</strong><span>{material.chunkCount} chunks</span></div><span className="status-badge">{material.status}</span></div>) : <p className="muted">Your administrator has not indexed course materials yet.</p>}</div></div></section>
}

function WorkspacePage({ project, artifacts, artifact, busy, review, onSelect, onCreate, onDraft, onTitle, onReview, onRate }: { project: Project | null; artifacts: Artifact[]; artifact: Artifact | null; busy: boolean; review?: Review; onSelect: (artifact: Artifact) => void; onCreate: (type: ArtifactType) => void; onDraft: (draft: Record<string, unknown>) => void; onTitle: (title: string) => void; onReview: () => void; onRate: (review: Review, rating: number) => void }) {
  if (!project) return <section className="empty-state"><h2>Select a project</h2><p>Create or select a project before opening the authoring workspace.</p></section>
  return <div className="workspace-layout"><aside className="artifact-panel"><div><p className="section-kicker">Artifacts</p><h2>{project.title}</h2></div><div className="button-stack">{(Object.keys(typeLabel) as ArtifactType[]).map(type => <button key={type} className="secondary-button" onClick={() => onCreate(type)}>{typeLabel[type]}</button>)}</div><div className="artifact-list">{artifacts.map(item => <button key={item.id} className={item.id === artifact?.id ? 'artifact-item selected' : 'artifact-item'} onClick={() => onSelect(item)}><strong>{item.title}</strong><span>{typeLabel[item.type]}</span></button>)}</div></aside>{!artifact ? <section className="empty-state"><h2>Create an artifact</h2><p>Choose a resource type at left. The coach will use your course materials as evidence during review.</p></section> : <section className="editor-panel"><div className="editor-header"><div><p className="section-kicker">{typeLabel[artifact.type]}</p><input className="title-input" aria-label="Artifact title" value={artifact.title} onChange={event => onTitle(event.target.value)} /></div><button className="primary-button" disabled={busy} onClick={onReview}>{busy ? 'Preparing review…' : 'Request coaching'}</button></div><ArtifactEditor artifact={artifact} onDraft={onDraft} /><ReviewPanel review={review} onRate={onRate} /></section>}</div>
}

function ArtifactEditor({ artifact, onDraft }: { artifact: Artifact; onDraft: (draft: Record<string, unknown>) => void }) {
  const draft = artifact.draft
  if (artifact.type !== 'MULTIPLE_CHOICE_QUESTION') return <label className="editor-label">Your draft<textarea className="draft-editor" value={String(draft.body ?? '')} onChange={event => onDraft({ ...draft, body: event.target.value })} placeholder="Explain the concept in your own words. The coach will ask questions rather than rewrite it for you." /></label>
  const options = Array.isArray(draft.options) ? draft.options as Array<{ key: string; text: string }> : []
  return <div className="mcq-editor"><label>Question stem<textarea value={String(draft.stem ?? '')} onChange={event => onDraft({ ...draft, stem: event.target.value })} placeholder="Write a single best-answer question." /></label><div className="option-grid">{options.map((option, index) => <label key={option.key}>Option {option.key}<input value={option.text} onChange={event => onDraft({ ...draft, options: options.map((current, position) => position === index ? { ...current, text: event.target.value } : current) })} /></label>)}</div><div className="form-grid compact"><label>Correct option<select value={String(draft.correctOptionKey ?? 'A')} onChange={event => onDraft({ ...draft, correctOptionKey: event.target.value })}>{options.map(option => <option key={option.key}>{option.key}</option>)}</select></label><label>Intended difficulty<select value={String(draft.intendedDifficulty ?? 'MEDIUM')} onChange={event => onDraft({ ...draft, intendedDifficulty: event.target.value })}><option>EASY</option><option>MEDIUM</option><option>HARD</option></select></label><label className="full">Answer rationale<textarea value={String(draft.answerRationale ?? '')} onChange={event => onDraft({ ...draft, answerRationale: event.target.value })} placeholder="Explain why the selected answer is correct." /></label></div></div>
}

function ReviewPanel({ review, onRate }: { review?: Review; onRate: (review: Review, rating: number) => void }) {
  if (!review) return <aside className="review-panel empty"><h3>Coaching review</h3><p>Request a review to receive evidence-grounded questions and revision strategies.</p></aside>
  return <aside className="review-panel"><div className="review-heading"><div><p className="section-kicker">Coaching review</p><h3>{review.status.replace('_', ' ')}</h3></div><strong className="score">{review.overallScore ?? '—'}<small>/ 4</small></strong></div><p>{review.summary}</p><div className="dimension-list">{review.dimensions.map(dimension => <article className="dimension-card" key={dimension.key}><header><h4>{dimension.label}</h4><span>{dimension.score ?? 'Evidence needed'}</span></header><p>{dimension.finding}</p>{dimension.reflectiveQuestions.length > 0 && <><b>Reflect</b><ul>{dimension.reflectiveQuestions.map(question => <li key={question}>{question}</li>)}</ul></>}{dimension.revisionStrategies.length > 0 && <><b>Try next</b><ul>{dimension.revisionStrategies.map(strategy => <li key={strategy}>{strategy}</li>)}</ul></>}</article>)}</div>{review.evidence.length > 0 && <div className="evidence-block"><h4>Course evidence</h4>{review.evidence.map(item => <details key={item.index}><summary>[{item.index}] {item.documentName}</summary><p>{item.excerpt}</p></details>)}</div>}<div className="rating-row"><span>Was this coaching useful?</span>{[1, 2, 3, 4, 5].map(rating => <button key={rating} aria-label={`Rate coaching ${rating} out of 5`} onClick={() => onRate(review, rating)}>{rating}</button>)}</div></aside>
}

function latestReviewFor(revisionId: string, reviews: Review[]) { return reviews.filter(review => review.revisionId === revisionId).at(-1) }
function formatDelta(value: number | null | undefined) { return value == null ? 'Not available' : `${value >= 0 ? '+' : ''}${value.toFixed(2)}` }
function HistoryPage({ revisions, reviews }: { revisions: Revision[]; reviews: Review[] }) { return <section className="page-section"><p className="section-kicker">Revision history</p><h2>Evidence of your development</h2>{revisions.length === 0 ? <p className="empty-inline">Request coaching to freeze the first revision.</p> : <ol className="timeline">{revisions.map(revision => { const review = latestReviewFor(revision.id, reviews); return <li key={revision.id}><div><strong>Revision {revision.revisionNumber}</strong><span>{new Date(revision.createdAt).toLocaleString()}</span></div><p>{revision.title}</p>{review && <span className="status-badge">{review.status.replaceAll('_', ' ')} · {review.overallScore == null ? 'Evidence needed' : `${review.overallScore}/4`}</span>}</li> })}</ol>}</section> }
function ReportPage({ project, overview, onDownload }: { project: Project | null; overview: ProjectOverview | null; onDownload: () => void }) { return <section className="page-section"><p className="section-kicker">Project report</p><div className="report-heading"><div><h2>{project?.title ?? 'Select a project'}</h2><p className="muted">A consolidated view of revisions, evidence, and coaching outcomes.</p></div><button className="primary-button" disabled={!project} onClick={onDownload}>Download Markdown report</button></div><div className="metric-grid"><article><span>Artifacts</span><strong>{overview?.metrics.artifactCount ?? 0}</strong></article><article><span>Total revisions</span><strong>{overview?.metrics.revisionCount ?? 0}</strong></article><article><span>Average score change</span><strong>{formatDelta(overview?.metrics.averageScoreDelta)}</strong></article></div><div className="report-artifacts">{overview?.artifacts.map(item => <article className="dimension-card" key={item.artifact.id}><header><h4>{item.artifact.title}</h4><span>{item.revisionCount} revision{item.revisionCount === 1 ? '' : 's'}</span></header><p>{item.latestReview?.summary ?? 'No coaching review yet.'}</p><small>First score: {item.firstReview?.overallScore ?? '—'} · Latest score: {item.latestReview?.overallScore ?? '—'} · Change: {formatDelta(item.scoreDelta)}</small></article>)}</div><p className="muted">Scores are formative signals, not course grades. Use the revision timeline and cited evidence to demonstrate how your work evolved.</p></section> }
function AdminDashboard({ courses, models, servers }: { courses: Course[]; models: ModelProfile[]; servers: McpServer[] }) { return <section className="page-section"><p className="section-kicker">Overview</p><h2>Administration dashboard</h2><div className="metric-grid"><article><span>Published courses</span><strong>{courses.filter(course => course.published).length}</strong></article><article><span>Active model</span><strong>{models.find(model => model.active)?.name ?? 'Environment default'}</strong></article><article><span>Online MCP servers</span><strong>{servers.filter(server => server.status === 'online').length}</strong></article></div><div className="split-panels"><div><h3>Knowledge base health</h3><p className="muted">Manage materials from Courses. Each course maps to one internal knowledge base.</p></div><div><h3>Security posture</h3><p className="muted">Runtime API keys and MCP secrets are encrypted server-side and never displayed in this console.</p></div></div></section> }
function AdminCoursesPage({ courses, selected, form, setForm, outcome, setOutcome, onCreate, onSelect, onAddOutcome, onUpload }: { courses: Course[]; selected: CourseDetail | null; form: CourseForm; setForm: (value: CourseForm) => void; outcome: OutcomeForm; setOutcome: (value: OutcomeForm) => void; onCreate: (event: FormEvent) => void; onSelect: (id: string) => void; onAddOutcome: (event: FormEvent) => void; onUpload: (file?: File) => void }) { return <div className="admin-grid"><section className="page-section"><h2>Create course</h2><form className="form-grid" onSubmit={onCreate}><label>Course code<input required value={form.code} onChange={event => setForm({ ...form, code: event.target.value })} placeholder="ENGR-210" /></label><label>Course name<input required value={form.name} onChange={event => setForm({ ...form, name: event.target.value })} placeholder="Engineering mechanics" /></label><label className="full">Description<textarea value={form.description} onChange={event => setForm({ ...form, description: event.target.value })} /></label><label className="check-row full"><input type="checkbox" checked={form.published} onChange={event => setForm({ ...form, published: event.target.checked })} /> Publish for students</label><button className="primary-button full">Create course</button></form><h3 className="subheading">Courses</h3><div className="project-list">{courses.map(course => <button key={course.id} className={course.id === selected?.id ? 'project-card selected' : 'project-card'} onClick={() => onSelect(course.id)}><strong>{course.code} · {course.name}</strong><span>{course.published ? 'Published' : 'Draft'} · {course.materialCount} materials</span></button>)}</div></section><section className="page-section">{!selected ? <div className="empty-inline">Select a course to manage learning outcomes and materials.</div> : <><p className="section-kicker">{selected.code}</p><h2>{selected.name}</h2><div className="admin-split"><div><h3>Learning outcomes</h3>{selected.outcomes.map(item => <p className="outcome-item" key={item.id}><b>{item.code}</b>{item.description}</p>)}<form className="compact-form" onSubmit={onAddOutcome}><input required value={outcome.code} onChange={event => setOutcome({ ...outcome, code: event.target.value })} placeholder="LO-1" /><input required value={outcome.description} onChange={event => setOutcome({ ...outcome, description: event.target.value })} placeholder="Outcome description" /><button className="secondary-button">Add outcome</button></form></div><div><h3>Course materials</h3><label className="upload-zone">Upload course material<input type="file" onChange={event => onUpload(event.target.files?.[0])} /></label>{selected.materials.map(material => <div className="material-row" key={material.id}><div><strong>{material.fileName}</strong><span>{material.chunkCount} chunks</span></div><span className="status-badge">{material.status}</span></div>)}</div></div></>}</section></div> }
function ModelsPage({ models, form, setForm, onCreate, onActivate }: { models: ModelProfile[]; form: ModelForm; setForm: (value: ModelForm) => void; onCreate: (event: FormEvent) => void; onActivate: (model: ModelProfile) => void }) { return <div className="admin-grid"><section className="page-section"><h2>New model profile</h2><form className="form-grid" onSubmit={onCreate}><label>Name<input required value={form.name} onChange={event => setForm({ ...form, name: event.target.value })} /></label><label>Protocol<select value={form.protocol} onChange={event => setForm({ ...form, protocol: event.target.value })}><option>OPENAI_COMPATIBLE</option><option>ANTHROPIC_COMPATIBLE</option></select></label><label className="full">Base URL<input required type="url" value={form.baseUrl} onChange={event => setForm({ ...form, baseUrl: event.target.value })} placeholder="https://api.example.com/v1" /></label><label>Model<input required value={form.model} onChange={event => setForm({ ...form, model: event.target.value })} /></label><label>API key<input required type="password" autoComplete="off" value={form.apiKey} onChange={event => setForm({ ...form, apiKey: event.target.value })} /></label><label>Temperature<input type="number" min="0" max="1.5" step="0.1" value={form.temperature} onChange={event => setForm({ ...form, temperature: Number(event.target.value) })} /></label><label>Max tokens<input type="number" min="128" max="8000" value={form.maxTokens} onChange={event => setForm({ ...form, maxTokens: Number(event.target.value) })} /></label><button className="primary-button full">Save profile</button></form></section><section className="page-section"><h2>Configured models</h2>{models.map(model => <article className="model-card" key={model.id}><div><strong>{model.name}</strong><span>{model.model} · {model.protocol}</span><small>{model.apiKeyHint}</small></div>{model.active ? <span className="status-badge">Active</span> : <button className="secondary-button" onClick={() => onActivate(model)}>Activate</button>}</article>)}{models.length === 0 && <p className="muted">The environment model remains active until a runtime profile is configured.</p>}</section></div> }
function McpPage({ servers, form, setForm, onCreate }: { servers: McpServer[]; form: McpForm; setForm: (value: McpForm) => void; onCreate: (event: FormEvent) => void }) { return <div className="admin-grid"><section className="page-section"><h2>Connect an MCP server</h2><p className="muted">Only approved runtime HTTP hosts can be registered. Secrets are never returned to the browser.</p><form className="form-grid" onSubmit={onCreate}><label>Display name<input required value={form.name} onChange={event => setForm({ ...form, name: event.target.value })} /></label><label>Streamable HTTP endpoint<input required type="url" value={form.endpoint} onChange={event => setForm({ ...form, endpoint: event.target.value })} placeholder="https://mcp.example.com/mcp" /></label><button className="primary-button full">Validate and connect</button></form></section><section className="page-section"><h2>Registered MCP servers</h2>{servers.map(server => <article className="model-card" key={server.id}><div><strong>{server.name}</strong><span>{server.transport} · {server.tools.length} tools</span><small>{server.endpoint || server.command}</small></div><span className={server.status === 'online' ? 'status-badge success' : 'status-badge'}>{server.status}</span></article>)}{servers.length === 0 && <p className="muted">No MCP servers are connected.</p>}</section></div> }

export default App
