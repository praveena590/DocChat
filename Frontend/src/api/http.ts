import axios, { type AxiosError } from 'axios'

export const BASE_URL: string = import.meta.env.VITE_API_URL ?? 'http://localhost:8080/api/docchat'

export const axiosInstance = axios.create({ baseURL: BASE_URL, timeout: 120_000 })

axiosInstance.interceptors.response.use(
  (res) => res,
  (err: AxiosError<{ error?: string }>) => {
    const message =
      err.response?.data?.error ??
      (err.response ? `Request failed (${err.response.status})` : 'Could not reach the server.')
    return Promise.reject(new Error(message))
  },
)
