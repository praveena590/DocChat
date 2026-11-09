import type { DocumentRecord } from '../types'
import { axiosInstance } from './http'

export async function getDocuments() {
  const { data } = await axiosInstance.get<DocumentRecord[]>('/documents')
  return data
}

export async function deleteDocument(id: string) {
  await axiosInstance.delete(`/documents/${id}`)
}

export async function uploadDocument(file: File, onProgress: (percent: number) => void) {
  const form = new FormData()
  form.append('file', file)

  const { data } = await axiosInstance.post<DocumentRecord>('/upload', form, {
    onUploadProgress: (e) => {
      if (e.total) onProgress(Math.round((e.loaded / e.total) * 100))
    },
  })
  return data
}
