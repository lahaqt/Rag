import { describe, expect, it } from 'vitest'
import { shouldSkipFeedback } from './feedback'

describe('shouldSkipFeedback', () => {
  it('allows the first submission when nothing has been sent yet', () => {
    expect(shouldSkipFeedback({}, 'up')).toBe(false)
  })

  it('skips while a submission is already in flight', () => {
    expect(shouldSkipFeedback({ feedbackStatus: 'submitting' }, 'up')).toBe(true)
  })

  it('skips re-submitting the same rating after it was recorded', () => {
    expect(shouldSkipFeedback({ feedbackStatus: 'submitted', feedbackRating: 'up' }, 'up')).toBe(true)
  })

  it('allows switching to the opposite rating after submission', () => {
    expect(shouldSkipFeedback({ feedbackStatus: 'submitted', feedbackRating: 'up' }, 'down')).toBe(false)
  })

  it('allows retrying the same rating after an error', () => {
    expect(shouldSkipFeedback({ feedbackStatus: 'error', feedbackRating: 'up' }, 'up')).toBe(false)
  })
})
