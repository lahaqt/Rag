import { describe, expect, it } from 'vitest'
import {
  ALLOWED_ATTACHMENT_TYPES,
  formatAttachmentSize,
  isAllowedAttachmentType,
  MAX_ATTACHMENT_SIZE,
} from './attachments'

describe('attachment utilities', () => {
  it('allows plain text, markdown, csv, and json MIME types', () => {
    ALLOWED_ATTACHMENT_TYPES.forEach((type) => {
      expect(isAllowedAttachmentType(new File([''], 'x', { type }))).toBe(true)
    })
  })

  it('allows common text file extensions when the browser omits a MIME type', () => {
    expect(isAllowedAttachmentType(new File([''], 'notes.md'))).toBe(true)
    expect(isAllowedAttachmentType(new File([''], 'data.json'))).toBe(true)
  })

  it('rejects unsupported types', () => {
    expect(isAllowedAttachmentType(new File([''], 'x.pdf', { type: 'application/pdf' }))).toBe(false)
    expect(isAllowedAttachmentType(new File([''], 'x.png', { type: 'image/png' }))).toBe(false)
  })

  it('formats sizes', () => {
    expect(formatAttachmentSize(512)).toBe('512 B')
    expect(formatAttachmentSize(1536)).toBe('1.5 KB')
    expect(formatAttachmentSize(2 * 1024 * 1024)).toBe('2.0 MB')
  })

  it('defines 1 MB max size', () => {
    expect(MAX_ATTACHMENT_SIZE).toBe(1024 * 1024)
  })
})
