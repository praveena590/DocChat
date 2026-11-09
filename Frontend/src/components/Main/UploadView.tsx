import { useRef, useState } from 'react'
import { uploadDocument } from '../../api/documents'
import type { DocumentRecord } from '../../types'

const MAX_SIZE = 10 * 1024 * 1024
const EXTENSIONS = ['.pdf', '.txt', '.md']

export const UploadView = ({ onUploaded }: { onUploaded: (doc: DocumentRecord) => void }) => {
  const fileInput = useRef<HTMLInputElement>(null)
  const [busy, setBusy] = useState(false)
  const [percent, setPercent] = useState(0)
  const [name, setName] = useState('')
  const [error, setError] = useState('')

  async function pick(file: File | undefined) {
    if (!file) return
    setError('')

    if (!EXTENSIONS.some((ext) => file.name.toLowerCase().endsWith(ext))) {
      setError('Only PDF, TXT or Markdown files work here.')
      return
    }
    if (file.size > MAX_SIZE) {
      setError('That file is over 10 MB.')
      return
    }

    setName(file.name)
    setPercent(0)
    setBusy(true)
    try {
      onUploaded(await uploadDocument(file, setPercent))
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="upload">
      <h1>Add a document</h1>
      <p className="upload-hint">Upload a file, then ask questions. Answers include the passages they came from.</p>
      <div
        className="dropzone"
        onDragOver={(e) => e.preventDefault()}
        onDrop={(e) => {
          e.preventDefault()
          pick(e.dataTransfer.files[0])
        }}
      >
        <div className="dropzone-title">Drag a file here</div>
        <div className="dropzone-hint">PDF, TXT or MD, up to 10 MB</div>
        <input
          ref={fileInput}
          type="file"
          accept=".pdf,.txt,.md"
          className="dropzone-input"
          aria-label="Choose a file to upload"
          onChange={(e) => pick(e.target.files?.[0])}
        />
        <button
          className="dropzone-button"
          onClick={() => fileInput.current?.click()}
          disabled={busy}
        >
          Choose file
        </button>
      </div>

      {busy && (
        <div className="status" role="status">
          <div className="status-row">
            <span>{name}</span>
            <span>{percent}%</span>
          </div>
          <div className="bar">
            <div className="bar-fill" style={{ width: `${percent}%` }} />
          </div>
          <small>{percent < 100 ? 'Uploading' : 'Splitting the text and creating embeddings, this can take a moment'}</small>
        </div>
      )}

      {error && (
        <p className="problem" role="alert">
          {error}
        </p>
      )}
    </main>
  )
}
