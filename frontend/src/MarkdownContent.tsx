import type { ReactNode } from 'react'

type MarkdownContentProps = {
  content: string
  className?: string
}

const inlineToken = /(\[([^\]]+)\]\(([^\s)]+)\)|`([^`]+)`|\*\*([^*]+)\*\*|__([^_]+)__|\*([^*]+)\*|_([^_]+)_)/g

function safeLink(value: string) {
  return /^(https?:|mailto:)/i.test(value) ? value : undefined
}

function renderInline(value: string): ReactNode[] {
  const nodes: ReactNode[] = []
  let cursor = 0
  let match: RegExpExecArray | null

  while ((match = inlineToken.exec(value)) !== null) {
    if (match.index > cursor) {
      nodes.push(value.slice(cursor, match.index))
    }

    const key = `${match.index}-${match[0]}`
    if (match[2] && match[3]) {
      const href = safeLink(match[3])
      nodes.push(href ? <a href={href} key={key} rel="noreferrer" target="_blank">{match[2]}</a> : match[2])
    } else if (match[4]) {
      nodes.push(<code key={key}>{match[4]}</code>)
    } else if (match[5] || match[6]) {
      nodes.push(<strong key={key}>{match[5] || match[6]}</strong>)
    } else if (match[7] || match[8]) {
      nodes.push(<em key={key}>{match[7] || match[8]}</em>)
    }
    cursor = match.index + match[0].length
  }

  if (cursor < value.length) {
    nodes.push(value.slice(cursor))
  }
  return nodes
}

function isTableDivider(value: string) {
  const cells = value.trim().replace(/^\||\|$/g, '').split('|')
  return cells.length > 0 && cells.every((cell) => /^:?-{3,}:?$/.test(cell.trim()))
}

function tableCells(value: string) {
  return value.trim().replace(/^\||\|$/g, '').split('|').map((cell) => cell.trim())
}

/** Renders the common Markdown syntax used by uploaded knowledge-base documents without HTML injection. */
export function MarkdownContent({ content, className }: MarkdownContentProps) {
  const lines = content.replace(/\r\n?/g, '\n').split('\n')
  const blocks: ReactNode[] = []
  let index = 0
  let blockKey = 0

  const nextKey = () => `block-${blockKey++}`

  while (index < lines.length) {
    const line = lines[index]
    if (!line.trim()) {
      index += 1
      continue
    }

    if (line.startsWith('```')) {
      const language = line.slice(3).trim()
      const code: string[] = []
      index += 1
      while (index < lines.length && !lines[index].startsWith('```')) {
        code.push(lines[index])
        index += 1
      }
      if (index < lines.length) {
        index += 1
      }
      blocks.push(
        <pre key={nextKey()}>
          <code data-language={language || undefined}>{code.join('\n')}</code>
        </pre>,
      )
      continue
    }

    const heading = /^(#{1,6})\s+(.+)$/.exec(line)
    if (heading) {
      const level = heading[1].length
      const key = nextKey()
      const text = renderInline(heading[2])
      const headings = [
        <h1 key={key}>{text}</h1>,
        <h2 key={key}>{text}</h2>,
        <h3 key={key}>{text}</h3>,
        <h4 key={key}>{text}</h4>,
        <h5 key={key}>{text}</h5>,
        <h6 key={key}>{text}</h6>,
      ]
      blocks.push(headings[level - 1])
      index += 1
      continue
    }

    if (/^\s{0,3}([-*_])(?:\s*\1){2,}\s*$/.test(line)) {
      blocks.push(<hr key={nextKey()} />)
      index += 1
      continue
    }

    if (line.startsWith('>')) {
      const quote: string[] = []
      while (index < lines.length && lines[index].startsWith('>')) {
        quote.push(lines[index].replace(/^>\s?/, ''))
        index += 1
      }
      blocks.push(<blockquote key={nextKey()}>{renderInline(quote.join('\n'))}</blockquote>)
      continue
    }

    const list = /^\s*(?:([-*+])|(\d+)\.)\s+(.+)$/.exec(line)
    if (list) {
      const ordered = Boolean(list[2])
      const items: ReactNode[] = []
      while (index < lines.length) {
        const item = /^\s*(?:([-*+])|(\d+)\.)\s+(.+)$/.exec(lines[index])
        if (!item || Boolean(item[2]) !== ordered) {
          break
        }
        items.push(<li key={`${nextKey()}-item`}>{renderInline(item[3])}</li>)
        index += 1
      }
      blocks.push(ordered ? <ol key={nextKey()}>{items}</ol> : <ul key={nextKey()}>{items}</ul>)
      continue
    }

    if (index + 1 < lines.length && line.includes('|') && isTableDivider(lines[index + 1])) {
      const headers = tableCells(line)
      const rows: string[][] = []
      index += 2
      while (index < lines.length && lines[index].includes('|') && lines[index].trim()) {
        rows.push(tableCells(lines[index]))
        index += 1
      }
      blocks.push(
        <div className="markdown-table-wrap" key={nextKey()}>
          <table>
            <thead><tr>{headers.map((header, headerIndex) => <th key={headerIndex}>{renderInline(header)}</th>)}</tr></thead>
            <tbody>{rows.map((row, rowIndex) => <tr key={rowIndex}>{headers.map((_, cellIndex) => <td key={cellIndex}>{renderInline(row[cellIndex] ?? '')}</td>)}</tr>)}</tbody>
          </table>
        </div>,
      )
      continue
    }

    const paragraph: string[] = [line]
    index += 1
    while (index < lines.length && lines[index].trim() && !/^(#{1,6})\s+|^```|^>|^\s*(?:[-*+]|\d+\.)\s+/.test(lines[index])) {
      paragraph.push(lines[index])
      index += 1
    }
    blocks.push(<p key={nextKey()}>{renderInline(paragraph.join('\n'))}</p>)
  }

  return <div className={['markdown-content', className].filter(Boolean).join(' ')}>{blocks}</div>
}
