import type { DocumentRecord } from '../../types'

interface Props {
  docs: DocumentRecord[]
  error: string
  openId: string | null
  onOpen: (id: string | null) => void
  onDelete: (id: string) => void
}

export function SidePanel({ docs, error, openId, onOpen, onDelete }: Props) {
  return (
    <aside className="sidebar">
      <h2 className="sidebar-title">Doc Chat</h2>
      <button className="sidebar-new" onClick={() => onOpen(null)}>
        Upload upload
      </button>

      <h3 className="sidebar-heading">Documents</h3>
      {error && <p className="sidebar-error">{error}</p>}
      {!error && docs.length === 0 && <p className="sidebar-empty">Nothing uploaded yet.</p>}

      <ul className="doc-list">
        {docs.map((doc) => (
          <li key={doc.id} className={doc.id === openId ? 'doc is-open' : 'doc'}>
            <button className="doc-name" onClick={() => onOpen(doc.id)}>
              {doc.fileName}
              <small>{doc.chunkCount} chunks</small>
            </button>
            <button className="doc-remove" aria-label={`Delete ${doc.fileName}`} onClick={() => onDelete(doc.id)}>
              X
            </button>
          </li>
        ))}
      </ul>
    </aside>
  )
}
