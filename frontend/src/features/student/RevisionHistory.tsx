import type { Review, Revision } from '../coach/CoachWorkspace'

export function RevisionHistory({ revisions, reviews }: { revisions: Revision[]; reviews: Review[] }) {
  return <section className="page-section"><p className="section-kicker">Revision history</p><h2>Evidence of your development</h2>{revisions.length === 0 ? <p className="empty-inline">Request coaching to freeze the first revision.</p> : <ol className="timeline">{revisions.map(revision => { const review = reviews.filter(item => item.revisionId === revision.id).at(-1); return <li key={revision.id}><div><strong>Revision {revision.revisionNumber}</strong><span>{new Date(revision.createdAt).toLocaleString()}</span></div><p>{revision.title}</p>{review && <span className="status-badge">{review.status.replaceAll('_', ' ')} · {review.overallScore == null ? 'Evidence needed' : `${review.overallScore}/4`}</span>}</li> })}</ol>}</section>
}
