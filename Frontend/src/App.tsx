import { useEffect, useState } from 'react'
import './App.scss'
import { SidePanel } from './components/sidepanel/sidePanel'
import { UploadView } from './components/Main/UploadView'
import { ChatView } from './components/Main/ChatView'
import { deleteDocument, getDocuments } from './api/documents'
import type { ChatMessage, DocumentRecord } from './types'

function App() {
  const [docs, setDocs] = useState<DocumentRecord[]>([])
  const [listError, setListError] = useState('')
  const [openId, setOpenId] = useState<string | null>(null)
  const [messages, setMessages] = useState<ChatMessage[]>([])

  async function refreshDocs() {
    try {
      setDocs(await getDocuments())
      setListError('')
    } catch (e) {
      setListError((e as Error).message)
    }
  }

  useEffect(() => {
    refreshDocs()
  }, [])

  async function handleUploaded(doc: DocumentRecord) {
    await refreshDocs()
    setOpenId(doc.id)
  }

  async function handleDelete(id: string) {
    try {
      await deleteDocument(id)
    } catch (e) {
      setListError((e as Error).message)
      return
    }
    setMessages((prev) => prev.filter((m) => m.docId !== id))
    if (openId === id) setOpenId(null)
    refreshDocs()
  }

  const openDoc = docs.find((d) => d.id === openId)

  return (
    <div className="app">
      <SidePanel
        docs={docs}
        error={listError}
        openId={openId}
        onOpen={setOpenId}
        onDelete={handleDelete}
      />
      {openDoc ? (
        <ChatView
          doc={openDoc}
          messages={messages.filter((m) => m.docId === openDoc.id)}
          setMessages={setMessages}
        />
      ) : (
        <UploadView onUploaded={handleUploaded} />
      )}
    </div>
  )
}

export default App
