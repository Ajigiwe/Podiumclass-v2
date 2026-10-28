import axios, { type AxiosInstance, type AxiosResponse, type AxiosError } from 'axios';

export interface DocumentResponse<T> {
  document: T;
}

export interface DocumentsResponse<T> {
  documents: T[];
  pagination?: {
    count: number;
    page: number;
    totalPages: number;
    next: string | null;
    previous: string | null;
  };
  meta?: Record<string, any>;
}

export interface DocumentError {
  code: string;
  message: string;
  details?: any;
  status?: number;
}

// The backend listens on 8000 (see docker-compose, MIGRATION_VUE_DJANGO.md, and
const resolveApiBaseUrl = (): string => {
  if (import.meta.env.VITE_API_URL) return import.meta.env.VITE_API_URL;
  if (typeof window !== 'undefined' && window.location?.hostname) {
    const host = window.location.hostname;
    if (host && host !== 'localhost' && host !== '127.0.0.1') {
      return `http://${host}:8000`;
    }
  }
  return 'http://localhost:8000';
};

const API_BASE_URL = resolveApiBaseUrl();

export const apiClient: AxiosInstance = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
  timeout: 15000,
});

// Request interceptor: attach the user's JWT.
//
// The server-to-server attendance key is deliberately NOT sent from the
// browser: embedding it in the frontend bundle would hand it to every visitor.
// API endpoints authenticate with the JWT issued at sign-in; the external
// Laravel service supplies its own key server-to-server.
apiClient.interceptors.request.use((config) => {
  const token = localStorage.getItem('podium_token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// Response interceptor: extract document/documents and standardize errors
apiClient.interceptors.response.use(
  (response: AxiosResponse) => {
    // If response body contains document or documents, return the full data wrapper
    return response;
  },
  (error: AxiosError<{ error?: DocumentError }>) => {
    const errorData = error.response?.data?.error;
    const standardizedError: DocumentError = {
      code: errorData?.code || 'UNKNOWN_ERROR',
      message: errorData?.message || error.message || 'An unexpected error occurred.',
      details: errorData?.details,
      status: error.response?.status,
    };
    return Promise.reject(standardizedError);
  }
);

// Convenience methods that automatically extract the Document Object
export const api = {
  /**
   * Fetch a single entity unwrapped from { document: T }
   */
  async getDocument<T>(url: string, params?: any): Promise<T> {
    const res = await apiClient.get<DocumentResponse<T>>(url, { params });
    if (res.data && 'document' in res.data) {
      return res.data.document;
    }
    return res.data as unknown as T;
  },

  /**
   * Fetch a collection unwrapped from { documents: T[], pagination: { ... } }
   */
  async getDocuments<T>(url: string, params?: any): Promise<DocumentsResponse<T>> {
    const res = await apiClient.get<DocumentsResponse<T>>(url, { params });
    return res.data;
  },

  /**
   * Post mutation unwrapped from { document: T }
   */
  async postDocument<T>(url: string, data?: any): Promise<T> {
    const res = await apiClient.post<DocumentResponse<T>>(url, data);
    if (res.data && 'document' in res.data) {
      return res.data.document;
    }
    return res.data as unknown as T;
  },

  /**
   * Post a multipart upload unwrapped from { document: T }.
   *
   * FormData must not inherit the client's global JSON content type -- axios
   * then sets the correct multipart boundary itself. Uploads get a much longer
   * timeout than API calls: a hundred megabytes over classroom wifi is slow.
   */
  async postUploadDocument<T>(url: string, formData: FormData, timeoutMs = 300000): Promise<T> {
    const res = await apiClient.post<DocumentResponse<T>>(url, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: timeoutMs,
    });
    if (res.data && 'document' in res.data) {
      return res.data.document;
    }
    return res.data as unknown as T;
  },

  /**
   * Put mutation unwrapped from { document: T }
   */
  async putDocument<T>(url: string, data?: any): Promise<T> {
    const res = await apiClient.put<DocumentResponse<T>>(url, data);
    if (res.data && 'document' in res.data) {
      return res.data.document;
    }
    return res.data as unknown as T;
  },

  /**
   * Patch mutation unwrapped from { document: T }
   */
  async patchDocument<T>(url: string, data?: any): Promise<T> {
    const res = await apiClient.patch<DocumentResponse<T>>(url, data);
    if (res.data && 'document' in res.data) {
      return res.data.document;
    }
    return res.data as unknown as T;
  },

  /**
   * Delete unwrapped from { document: T } (the backend answers { deleted: true }).
   */
  async deleteDocument<T = any>(url: string): Promise<T> {
    const res = await apiClient.delete<DocumentResponse<T>>(url);
    if (res.data && 'document' in res.data) {
      return res.data.document;
    }
    return res.data as unknown as T;
  },
};
