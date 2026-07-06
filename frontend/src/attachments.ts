export type ChatAttachment = {
  fileName: string
  contentType: string
  size: number
  content: string
}

export const ALLOWED_ATTACHMENT_TYPES = [
  'text/plain',
  'text/markdown',
  'text/csv',
  'application/json',
]

export const MAX_ATTACHMENT_SIZE = 1024 * 1024
export const MAX_ATTACHMENT_COUNT = 5

const ALLOWED_ATTACHMENT_EXTENSIONS = ['.txt', '.md', '.markdown', '.csv', '.json']

export function isAllowedAttachmentType(file: File): boolean {
  if (ALLOWED_ATTACHMENT_TYPES.includes(file.type)) {
    return true
  }
  const fileName = file.name.toLowerCase()
  return ALLOWED_ATTACHMENT_EXTENSIONS.some((extension) => fileName.endsWith(extension))
}

export function formatAttachmentSize(size: number): string {
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

export function readAttachment(file: File): Promise<ChatAttachment> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => {
      resolve({
        fileName: file.name,
        contentType: file.type || 'text/plain',
        size: file.size,
        content: String(reader.result ?? ''),
      })
    }
    reader.onerror = () => reject(reader.error ?? new Error('Failed to read attachment'))
    reader.readAsText(file)
  })
}
