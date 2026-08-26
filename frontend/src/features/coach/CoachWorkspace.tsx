import { useCallback, useEffect, useMemo, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiClient } from '../../api/client'
import { watchReviewRunEvents } from '../../api/reviewEvents'
import { ApplicationShell } from '../shared/ApplicationShell'
import { AdminContent } from '../admin/AdminDashboard'
import { RevisionHistory } from '../student/RevisionHistory'
export type Outcome = { id: string; code: string; description: string; displayOrder: number; active: boolean }
export type Material = { id: string; fileName: string; contentType: string; size: number; status: string; chunkCount: number; errorMessage: string; uploadedAt?: string }
export type Course = { id: string; code: string; name: string; description: string; published: boolean; materialCount: number; outcomeCount: number }
export type CourseDetail = Course & { archived?: boolean; outcomes: Outcome[]; materials: Material[] }
type Project = { id: string; courseId: string; title: string; description: string; learningOutcomeIds: string[]; createdAt: string; updatedAt: string }
type ArtifactType = 'TECHNICAL_INTERPRETATION' | 'SUPPLEMENTARY_MATERIAL' | 'MULTIPLE_CHOICE_QUESTION'
type Artifact = { id: string; projectId: string; type: ArtifactType; title: string; draft: Record<string, unknown>; draftVersion: number; createdAt: string; updatedAt: string }
export type Revision = { id: string; artifactId: string; revisionNumber: number; title: string; draft: Record<string, unknown>; createdAt: string }
type Evidence = { index: number; documentName: string; materialId: string; excerpt: string; score: number }
type Dimension = { key: string; label: string; score: number | null; finding: string; evidenceRefs: number[]; reflectiveQuestions: string[]; revisionStrategies: string[] }
type ToolObservation = { serverId: string; toolName: string; success: boolean; content: string }
export type Review = { id: string; revisionId: string; status: 'COMPLETED' | 'INSUFFICIENT_EVIDENCE' | 'FAILED'; overallScore: number | null; dimensions: Dimension[]; evidence: Evidence[]; toolObservations: ToolObservation[]; summary: string; failureReason: string; createdAt: string }
type ReviewRun = { id: string; revisionId: string; status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'; currentPhase: string; failureReason: string; recoverable: boolean; review: Review | null }
type ArtifactOverview = { artifact: Artifact; firstRevision: Revision | null; latestRevision: Revision | null; firstReview: Review | null; latestReview: Review | null; revisionCount: number; scoreDelta: number | null }
type ProjectOverview = { project: Project; course: Course; artifacts: ArtifactOverview[]; metrics: { artifactCount: number; revisionCount: number; reviewedArtifactCount: number; averageScoreDelta: number | null } }
export type ModelProfile = { id: string; name: string; protocol: string; baseUrl: string; model: string; apiKeyConfigured: boolean; apiKeyHint: string; temperature: number; maxTokens: number; enabled: boolean; active: boolean }
export type McpServer = { id: string; name: string; transport: string; endpoint: string; command: string; enabled: boolean; readOnly: boolean; status: string; lastError: string; tools: Array<{ name: string }> }
export type AdminReviewRun = { id: string; revisionId: string; userId: string; status: string; currentPhase: string; attemptCount: number; recoverable: boolean; failureReason: string; reviewId: string; updatedAt: string }
export type AuditEvent = { id: number; adminUserId: string; action: string; targetType: string; targetId: string; result: string; createdAt: string }
type ProjectForm = { courseId: string; title: string; description: string; learningOutcomeIds: string[] }
export type CourseForm = { code: string; name: string; description: string; published: boolean }
export type OutcomeForm = { code: string; description: string }
export type ModelForm = { name: string; protocol: string; baseUrl: string; model: string; apiKey: string; temperature: number; maxTokens: number; enabled: boolean }
export type McpForm = { name: string; endpoint: string; enabled: boolean }

const defaultDraft = (type: ArtifactType): Record<string, unknown> => type === 'MULTIPLE_CHOICE_QUESTION'
  ? { stem: '', options: [{ key: 'A', text: '' }, { key: 'B', text: '' }, { key: 'C', text: '' }, { key: 'D', text: '' }], correctOptionKey: 'A', answerRationale: '', intendedDifficulty: 'MEDIUM' }
  : { body: '' }

const typeLabel: Record<ArtifactType, string> = {
  TECHNICAL_INTERPRETATION: 'Technical interpretation',
  SUPPLEMENTARY_MATERIAL: 'Supplementary learning material',
  MULTIPLE_CHOICE_QUESTION: 'Multiple-choice question',
}

type CoachWorkspaceProps = { adminRoute: boolean; accessToken: string; userId: string; roles: string[]; onUnauthorized: () => void; onSignOut: () => void }

function CoachWorkspace({ adminRoute, accessToken, userId, roles, onUnauthorized, onSignOut }: CoachWorkspaceProps) {
  const client = useMemo(() => new ApiClient(accessToken, onUnauthorized), [accessToken, onUnauthorized])
  const api = useCallback(<T,>(path: string, options?: RequestInit) => client.request<T>(path, options), [client])
  const [courses, setCourses] = useState<Course[]>([])
  const [projects, setProjects] = useState<Project[]>([])
  const [activeProjectId, setActiveProjectId] = useState('')
  const [activeArtifact, setActiveArtifact] = useState<Artifact | null>(null)
  const [artifacts, setArtifacts] = useState<Artifact[]>([])
  const [revisions, setRevisions] = useState<Revision[]>([])
  const [reviews, setReviews] = useState<Review[]>([])
  const [projectOverview, setProjectOverview] = useState<ProjectOverview | null>(null)
  const [studentPage, setStudentPage] = useState<'projects' | 'course' | 'workspace' | 'history' | 'report'>('projects')
  const [adminPage, setAdminPage] = useState<'dashboard' | 'courses' | 'models' | 'mcp' | 'reviews' | 'audit'>('dashboard')
  const [selectedCourse, setSelectedCourse] = useState<CourseDetail | null>(null)
  const [status, setStatus] = useState('')
  const [busy, setBusy] = useState(false)
  const [draftDirty, setDraftDirty] = useState(false)
  const [recoverableRun, setRecoverableRun] = useState<ReviewRun | null>(null)
  const [newProject, setNewProject] = useState<ProjectForm>({ courseId: '', title: '', description: '', learningOutcomeIds: [] })
  const [newCourse, setNewCourse] = useState<CourseForm>({ code: '', name: '', description: '', published: true })
  const [newOutcome, setNewOutcome] = useState<OutcomeForm>({ code: '', description: '' })
  const [models, setModels] = useState<ModelProfile[]>([])
  const [newModel, setNewModel] = useState<ModelForm>({ name: '', protocol: 'OPENAI_COMPATIBLE', baseUrl: '', model: '', apiKey: '', temperature: 0.2, maxTokens: 1200, enabled: true })
  const [mcpServers, setMcpServers] = useState<McpServer[]>([])
  const [adminReviewRuns, setAdminReviewRuns] = useState<AdminReviewRun[]>([])
  const [reviewTrace, setReviewTrace] = useState('')
  const [auditEvents, setAuditEvents] = useState<AuditEvent[]>([])
  const [newMcp, setNewMcp] = useState<McpForm>({ name: '', endpoint: '', enabled: true })

  const activeProject = projects.find(project => project.id === activeProjectId) ?? null
  const activeCourse = selectedCourse ?? null
  const canAdmin = roles.includes('ADMIN')

  const loadCore = useCallback(async () => {
    const [courseRows, projectRows] = await Promise.all([api<Course[]>('/api/v1/courses'), api<Project[]>('/api/v1/projects')])
    setCourses(courseRows)
    setProjects(projectRows)
    setActiveProjectId(current => current || projectRows[0]?.id || '')
  }, [api])

  useEffect(() => {
    void (async () => {
      try {
        await loadCore()
      } catch (error) {
        setStatus(error instanceof Error ? `Unable to connect: ${error.message}` : 'Unable to connect to the service.')
      }
    })()
  }, [loadCore])

  useEffect(() => {
    if (!activeProjectId) return
    void (async () => {
      try {
        const rows = await api<Artifact[]>(`/api/v1/projects/${activeProjectId}/artifacts`)
        setArtifacts(rows)
        setActiveArtifact(current => rows.find(item => item.id === current?.id) ?? rows[0] ?? null)
      } catch (error) { setStatus(error instanceof Error ? error.message : 'Unable to load artifacts.') }
    })()
  }, [activeProjectId, api])

  useEffect(() => {
    if (!activeArtifact) {
      void Promise.resolve().then(() => { setRevisions([]); setReviews([]) })
      return
    }
    void (async () => {
      try {
        const rows = await api<Revision[]>(`/api/v1/artifacts/${activeArtifact.id}/revisions`)
        setRevisions(rows)
        const reviewRows = await Promise.all(rows.map(revision => api<Review[]>(`/api/v1/revisions/${revision.id}/reviews`)))
        setReviews(reviewRows.flat())
      } catch (error) { setStatus(error instanceof Error ? error.message : 'Unable to load revision history.') }
    })()
  }, [activeArtifact, api])

  useEffect(() => {
    if (studentPage !== 'report' || !activeProjectId) return
    void api<ProjectOverview>(`/api/v1/projects/${activeProjectId}/overview`)
      .then(setProjectOverview)
      .catch(error => setStatus(error instanceof Error ? error.message : 'Unable to load project report.'))
  }, [studentPage, activeProjectId, api])

  useEffect(() => {
    if (!draftDirty || !activeArtifact) return
    const timer = window.setTimeout(() => { void saveDraft() }, 800)
    return () => window.clearTimeout(timer)
    // saveDraft deliberately snapshots the current optimistic-lock version.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draftDirty, activeArtifact])

  useEffect(() => {
    if (!newProject.courseId) return
    void api<CourseDetail>(`/api/v1/courses/${newProject.courseId}`).then(course => {
      setSelectedCourse(course)
      setNewProject(current => ({ ...current, learningOutcomeIds: course.outcomes.filter(item => item.active).map(item => item.id) }))
    }).catch(error => setStatus(error instanceof Error ? error.message : 'Unable to load course.'))
  }, [newProject.courseId, api])

  const selectCourse = async (courseId: string, admin = false) => {
    try {
      const path = admin ? `/api/v1/admin/courses/${courseId}` : `/api/v1/courses/${courseId}`
      setSelectedCourse(await api<CourseDetail>(path))
    } catch (error) { setStatus(error instanceof Error ? error.message : 'Unable to load course.') }
  }

  async function saveDraft() {
    if (!activeArtifact || !draftDirty) return activeArtifact
    try {
      const saved = await api<Artifact>(`/api/v1/artifacts/${activeArtifact.id}/draft`, {
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
      const project = await api<Project>('/api/v1/projects', { method: 'POST', body: JSON.stringify(newProject) })
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
      const artifact = await api<Artifact>(`/api/v1/projects/${activeProject.id}/artifacts`, { method: 'POST', body: JSON.stringify({ type, title: typeLabel[type], draft: defaultDraft(type) }) })
      setArtifacts(items => [artifact, ...items])
      setActiveArtifact(artifact)
      setStudentPage('workspace')
      setStatus('Artifact created.')
    } catch (error) { setStatus(error instanceof Error ? error.message : 'Unable to create artifact.') } finally { setBusy(false) }
  }

  const requestCoaching = async () => {
    if (!activeArtifact) return
    let eventController: AbortController | undefined
    try {
      setBusy(true)
      const saved = await saveDraft()
      if (!saved) return
      const revision = await api<Revision>(`/api/v1/artifacts/${saved.id}/revisions`, { method: 'POST' })
      setRevisions(items => [...items, revision])
      let run = await api<ReviewRun>(`/api/v1/revisions/${revision.id}/review-runs`, {
        method: 'POST', headers: { 'Idempotency-Key': revision.id },
      })
      eventController = new AbortController()
      void watchReviewRunEvents(run.id, accessToken, eventController.signal, event => {
        const phase = String(event.data.phase ?? event.data.currentPhase ?? event.event)
        setStatus(`Coaching stage: ${phase.replaceAll('_', ' ')}.`)
      })
      const pollingDeadline = Date.now() + 180_000
      setStatus('Coaching review queued. You can leave this page without interrupting it.')
      while (run.status === 'QUEUED' || run.status === 'RUNNING') {
        if (Date.now() >= pollingDeadline) throw new Error('The review is still running. Reopen revision history to check its result.')
        await new Promise(resolve => window.setTimeout(resolve, 750))
        run = await api<ReviewRun>(`/api/v1/review-runs/${run.id}`)
        setStatus(`Coaching stage: ${run.currentPhase.replaceAll('_', ' ')}.`)
      }
      if (run.status !== 'COMPLETED' || !run.review) {
        setRecoverableRun(run.recoverable ? run : null)
        throw new Error(run.failureReason || 'The coaching review did not complete.')
      }
      const review = run.review
      setRecoverableRun(null)
      setReviews(items => [...items, review])
      setProjectOverview(null)
      setStatus(`Coaching review ${review.status.toLowerCase().replace('_', ' ')}.`)
    } catch (error) { setStatus(error instanceof Error ? error.message : 'Coaching review failed.') } finally { eventController?.abort(); setBusy(false) }
  }

  const retryFailedReview = async () => {
    if (!recoverableRun) return
    try {
      setBusy(true)
      let run = await api<ReviewRun>(`/api/v1/review-runs/${recoverableRun.id}/retry`, { method: 'POST' })
      const deadline = Date.now() + 180_000
      while (run.status === 'QUEUED' || run.status === 'RUNNING') {
        if (Date.now() >= deadline) throw new Error('The retry is still running. Check revision history shortly.')
        setStatus(`Retry stage: ${run.currentPhase.replaceAll('_', ' ')}.`)
        await new Promise(resolve => window.setTimeout(resolve, 750))
        run = await api<ReviewRun>(`/api/v1/review-runs/${run.id}`)
      }
      if (run.status !== 'COMPLETED' || !run.review) {
        setRecoverableRun(run.recoverable ? run : null)
        throw new Error(run.failureReason || 'The review retry failed.')
      }
      setReviews(items => [...items, run.review!])
      setRecoverableRun(null)
      setStatus('Coaching review completed after retry.')
    } catch (error) { setStatus(error instanceof Error ? error.message : 'Unable to retry review.') } finally { setBusy(false) }
  }

  const submitRating = async (review: Review, rating: number) => {
    try {
      await api<void>(`/api/v1/reviews/${review.id}/rating`, { method: 'POST', body: JSON.stringify({ pertinence: rating, actionability: rating, educationalValue: rating, comment: '' }) })
      setStatus('Thank you. Your review rating was saved.')
    } catch (error) { setStatus(error instanceof Error ? error.message : 'Unable to save rating.') }
  }

  const downloadReport = async () => {
    if (!activeProject) return
    try {
      const overview = await api<ProjectOverview>(`/api/v1/projects/${activeProject.id}/overview`)
      setProjectOverview(overview)
      const markdown = [`# ${overview.project.title}`, `Course: ${overview.course.name}`, `Total revisions: ${overview.metrics.revisionCount}`, `Average formative score change: ${formatDelta(overview.metrics.averageScoreDelta)}`, '', ...overview.artifacts.map(item => `## ${item.artifact.title}\nRevisions: ${item.revisionCount}\nFirst score: ${item.firstReview?.overallScore ?? 'Not reviewed'}\nLatest score: ${item.latestReview?.overallScore ?? 'Not reviewed'}\nScore change: ${formatDelta(item.scoreDelta)}\n\n${item.latestReview?.summary ?? 'No coaching review yet.'}`)].join('\n')
      const url = URL.createObjectURL(new Blob([markdown], { type: 'text/markdown' }))
      const anchor = document.createElement('a'); anchor.href = url; anchor.download = `${overview.project.title.replace(/[^a-z0-9]+/gi, '-').toLowerCase()}-report.md`; anchor.click(); URL.revokeObjectURL(url)
    } catch (error) { setStatus(error instanceof Error ? error.message : 'Unable to prepare report.') }
  }

  const loadAdminData = useCallback(async () => {
    try {
      const [courseRows, modelRows, mcpRows, runRows, auditRows] = await Promise.all([api<Course[]>('/api/v1/admin/courses'), api<ModelProfile[]>('/api/v1/admin/models'), api<McpServer[]>('/api/v1/admin/mcp/servers'), api<AdminReviewRun[]>('/api/v1/admin/review-runs'), api<AuditEvent[]>('/api/v1/admin/audit-events')])
      setCourses(courseRows); setModels(modelRows); setMcpServers(mcpRows); setAdminReviewRuns(runRows); setAuditEvents(auditRows)
    } catch (error) { setStatus(error instanceof Error ? error.message : 'Unable to load administration data.') }
  }, [api])

  useEffect(() => {
    if (adminRoute && canAdmin) void Promise.resolve().then(loadAdminData)
  }, [adminRoute, canAdmin, loadAdminData])

  const createCourse = async (event: FormEvent) => {
    event.preventDefault()
    try {
      setBusy(true)
      const course = await api<CourseDetail>('/api/v1/admin/courses', { method: 'POST', body: JSON.stringify(newCourse) })
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
      const outcomes = await api<Outcome[]>(`/api/v1/admin/courses/${selectedCourse.id}/learning-outcomes`, { method: 'PUT', body: JSON.stringify([...selectedCourse.outcomes.map(item => ({ code: item.code, description: item.description })), newOutcome]) })
      setSelectedCourse({ ...selectedCourse, outcomes })
      setNewOutcome({ code: '', description: '' })
      setStatus('Learning outcome added.')
    } catch (error) { setStatus(error instanceof Error ? error.message : 'Unable to update outcomes.') }
  }

  const uploadMaterial = async (file?: File) => {
    if (!file || !selectedCourse) return
    try {
      const body = new FormData(); body.append('file', file)
      const material = await api<Material>(`/api/v1/admin/courses/${selectedCourse.id}/materials`, {
        method: 'POST', body, headers: { 'Idempotency-Key': `${selectedCourse.id}:${file.name}:${file.size}:${file.lastModified}` },
      })
      setSelectedCourse({ ...selectedCourse, materials: [material, ...selectedCourse.materials] })
      setStatus('Course material uploaded and queued for indexing.')
    } catch (error) { setStatus(error instanceof Error ? error.message : 'Unable to upload material.') }
  }

  const createModel = async (event: FormEvent) => {
    event.preventDefault()
    try {
      const model = await api<ModelProfile>('/api/v1/admin/models', { method: 'POST', body: JSON.stringify(newModel) })
      setModels(items => [model, ...items]); setNewModel({ name: '', protocol: 'OPENAI_COMPATIBLE', baseUrl: '', model: '', apiKey: '', temperature: 0.2, maxTokens: 1200, enabled: true }); setStatus('Model profile saved. Test it before activation.')
    } catch (error) { setStatus(error instanceof Error ? error.message : 'Unable to save model profile.') }
  }

  const activateModel = async (model: ModelProfile) => {
    try { const updated = await api<ModelProfile>(`/api/v1/admin/models/${model.id}/activate`, { method: 'POST' }); setModels(items => items.map(item => ({ ...item, active: item.id === updated.id }))); setStatus(`${updated.name} is active for new requests.`) } catch (error) { setStatus(error instanceof Error ? error.message : 'Unable to activate model.') }
  }

  const createMcp = async (event: FormEvent) => {
    event.preventDefault()
    try {
      const server = await api<McpServer>('/api/v1/admin/mcp/servers', { method: 'POST', body: JSON.stringify({ id: '', name: newMcp.name, transport: 'streamable_http', endpoint: newMcp.endpoint, command: '', args: [], environment: {}, workingDirectory: '', bearerToken: '', enabled: newMcp.enabled }) })
      setMcpServers(items => [server, ...items]); setNewMcp({ name: '', endpoint: '', enabled: true }); setStatus('MCP server saved and validated.')
    } catch (error) { setStatus(error instanceof Error ? error.message : 'Unable to create MCP server.') }
  }

  const inspectReviewTrace = async (reviewId: string) => {
    try {
      const trace = await api<Record<string, unknown>>(`/api/v1/admin/reviews/${reviewId}/trace`)
      setReviewTrace(JSON.stringify(trace, null, 2))
    } catch (error) { setStatus(error instanceof Error ? error.message : 'Unable to load review trace.') }
  }

  const renderStudent = () => {
    if (studentPage === 'projects') return <ProjectsPage courses={courses} projects={projects} activeProjectId={activeProjectId} onProject={setActiveProjectId} form={newProject} setForm={setNewProject} course={activeCourse} onSubmit={createProject} busy={busy} />
    if (studentPage === 'course') return <CoursePage project={activeProject} course={activeCourse} onLoad={() => activeProject && void selectCourse(activeProject.courseId)} />
    if (studentPage === 'history') return <RevisionHistory revisions={revisions} reviews={reviews} />
    if (studentPage === 'report') return <ReportPage project={activeProject} overview={projectOverview} onDownload={downloadReport} />
    return <WorkspacePage project={activeProject} artifacts={artifacts} artifact={activeArtifact} busy={busy} review={reviews.at(-1)} onSelect={setActiveArtifact} onCreate={createArtifact} onDraft={updateDraft} onTitle={title => activeArtifact && (setActiveArtifact({ ...activeArtifact, title }), setDraftDirty(true))} onReview={requestCoaching} onRate={submitRating} />
  }

  const renderAdmin = () => {
    if (!canAdmin) return <section className="empty-state"><h1>Administrator access required</h1><p>Your signed identity is not listed in the configured administrator IDs.</p><a href="/app">Return to Authoring Coach</a></section>
    return <AdminContent page={adminPage} courses={courses} selected={selectedCourse} courseForm={newCourse} setCourseForm={setNewCourse}
      outcomeForm={newOutcome} setOutcomeForm={setNewOutcome} onCreateCourse={createCourse} onSelectCourse={id => void selectCourse(id, true)}
      onAddOutcome={addOutcome} onUpload={uploadMaterial} models={models} modelForm={newModel} setModelForm={setNewModel}
      onCreateModel={createModel} onActivateModel={activateModel} servers={mcpServers} mcpForm={newMcp} setMcpForm={setNewMcp}
      onCreateMcp={createMcp} runs={adminReviewRuns} trace={reviewTrace} onTrace={id => void inspectReviewTrace(id)} auditEvents={auditEvents} />
  }

  const navigation = (adminRoute
    ? [['dashboard', 'Dashboard'], ['courses', 'Courses'], ['models', 'Model settings'], ['mcp', 'MCP servers'], ['reviews', 'Review operations'], ['audit', 'Audit log']]
    : [['projects', 'My projects'], ['course', 'Course overview'], ['workspace', 'Authoring workspace'], ['history', 'Revision history'], ['report', 'Project report']]
  ).map(([id, label]) => ({ id, label }))
  return <ApplicationShell admin={adminRoute} activePage={adminRoute ? adminPage : studentPage} navigation={navigation}
    userId={userId} canAdmin={canAdmin} busy={busy} status={status}
    title={adminRoute ? 'System control plane' : activeProject?.title ?? 'Build stronger technical learning resources'}
    onNavigate={id => adminRoute ? setAdminPage(id as typeof adminPage) : setStudentPage(id as typeof studentPage)}
    onDismissStatus={() => setStatus('')} onSignOut={onSignOut}>
    {recoverableRun && !adminRoute && <div className="recovery-banner" role="alert"><div><strong>Review interrupted</strong><span>{recoverableRun.failureReason}</span></div><button className="secondary-button" disabled={busy} onClick={() => void retryFailedReview()}>Retry review</button></div>}
    {adminRoute ? renderAdmin() : renderStudent()}
  </ApplicationShell>
}

function ProjectsPage({ courses, projects, activeProjectId, onProject, form, setForm, course, onSubmit, busy }: { courses: Course[]; projects: Project[]; activeProjectId: string; onProject: (id: string) => void; form: ProjectForm; setForm: (form: ProjectForm) => void; course: CourseDetail | null; onSubmit: (event: FormEvent) => void; busy: boolean }) {
  return <div className="two-column"><section className="page-section"><p className="section-kicker">Start here</p><h2>Create a project</h2><p className="muted">Choose a published course. Its evidence base stays behind the scenes while you write.</p><form className="form-grid" onSubmit={onSubmit}><label>Course<select required value={form.courseId} onChange={event => setForm({ ...form, courseId: event.target.value })}><option value="">Select a course</option>{courses.map(course => <option key={course.id} value={course.id}>{course.code} · {course.name}</option>)}</select></label><label>Project title<input required value={form.title} onChange={event => setForm({ ...form, title: event.target.value })} placeholder="e.g. Fluid mechanics learning pack" /></label><label className="full">Purpose<textarea value={form.description} onChange={event => setForm({ ...form, description: event.target.value })} placeholder="What will this resource help a learner understand?" /></label>{course && <fieldset className="full"><legend>Learning outcomes</legend><div className="outcome-list">{course.outcomes.filter(item => item.active).map(outcome => <label className="check-row" key={outcome.id}><input type="checkbox" checked={form.learningOutcomeIds.includes(outcome.id)} onChange={event => setForm({ ...form, learningOutcomeIds: event.target.checked ? [...form.learningOutcomeIds, outcome.id] : form.learningOutcomeIds.filter(id => id !== outcome.id) })} /> <span><strong>{outcome.code}</strong> {outcome.description}</span></label>)}</div></fieldset>}<button className="primary-button full" disabled={busy}>Create project</button></form></section><section className="page-section"><p className="section-kicker">Your work</p><h2>Recent projects</h2><div className="project-list">{projects.length === 0 ? <div className="empty-inline">No projects yet. Create one to begin.</div> : projects.map(project => <button className={project.id === activeProjectId ? 'project-card selected' : 'project-card'} key={project.id} onClick={() => onProject(project.id)}><strong>{project.title}</strong><span>{project.description || 'No description'}</span><small>Updated {new Date(project.updatedAt).toLocaleDateString()}</small></button>)}</div></section></div>
}

function CoursePage({ project, course, onLoad }: { project: Project | null; course: CourseDetail | null; onLoad: () => void }) {
  // onLoad is intentionally keyed by the selected course rather than parent render identity.
  // eslint-disable-next-line react-hooks/exhaustive-deps
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

function formatDelta(value: number | null | undefined) { return value == null ? 'Not available' : `${value >= 0 ? '+' : ''}${value.toFixed(2)}` }
function ReportPage({ project, overview, onDownload }: { project: Project | null; overview: ProjectOverview | null; onDownload: () => void }) { return <section className="page-section"><p className="section-kicker">Project report</p><div className="report-heading"><div><h2>{project?.title ?? 'Select a project'}</h2><p className="muted">A consolidated view of revisions, evidence, and coaching outcomes.</p></div><button className="primary-button" disabled={!project} onClick={onDownload}>Download Markdown report</button></div><div className="metric-grid"><article><span>Artifacts</span><strong>{overview?.metrics.artifactCount ?? 0}</strong></article><article><span>Total revisions</span><strong>{overview?.metrics.revisionCount ?? 0}</strong></article><article><span>Average score change</span><strong>{formatDelta(overview?.metrics.averageScoreDelta)}</strong></article></div><div className="report-artifacts">{overview?.artifacts.map(item => <article className="dimension-card" key={item.artifact.id}><header><h4>{item.artifact.title}</h4><span>{item.revisionCount} revision{item.revisionCount === 1 ? '' : 's'}</span></header><p>{item.latestReview?.summary ?? 'No coaching review yet.'}</p><small>First score: {item.firstReview?.overallScore ?? '—'} · Latest score: {item.latestReview?.overallScore ?? '—'} · Change: {formatDelta(item.scoreDelta)}</small></article>)}</div><p className="muted">Scores are formative signals, not course grades. Use the revision timeline and cited evidence to demonstrate how your work evolved.</p></section> }
export default CoachWorkspace
