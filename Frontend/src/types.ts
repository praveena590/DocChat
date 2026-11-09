export interface DocumentRecord {
  id: string
  fileName: string
  fileSize: number
  chunkCount: number
  uploadedAt: string
  status: string
}

export interface Citation {
  chunkId: string
  fileName: string
  snippet: string
  score: number
}

export interface ChatMessage {
  id: string
  docId: string
  role: 'user' | 'assistant'
  text: string
  sources: Citation[]
  status: 'streaming' | 'done' | 'error'
}
