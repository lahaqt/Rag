import type { Message } from './App'

type FeedbackRating = 'up' | 'down'

type FeedbackState = Pick<Message, 'feedbackStatus' | 'feedbackRating'>

/**
 * Decide whether a feedback click should be ignored. A click is skipped when a
 * submission is already in flight, or when the recorded rating matches the
 * clicked one (no point re-submitting an identical rating). Switching to the
 * opposite rating, or retrying after an error, is always allowed.
 */
export function shouldSkipFeedback(state: FeedbackState, rating: FeedbackRating): boolean {
  if (state.feedbackStatus === 'submitting') {
    return true
  }
  if (state.feedbackStatus === 'submitted' && state.feedbackRating === rating) {
    return true
  }
  return false
}
