import { http } from '@/api/http'
import type {
  ConnectionInfo,
  ConnectionResponse,
  ConnectionTestResponse,
  CreateConnectionRequest,
} from '@/types/api'

const BASE = '/api/v1/migration/connections'

export function listConnections(): Promise<ConnectionResponse[]> {
  return http.get(BASE)
}

export function createConnection(body: CreateConnectionRequest): Promise<ConnectionResponse> {
  return http.post(BASE, body)
}

export function deleteConnection(id: number): Promise<void> {
  return http.delete(`${BASE}/${id}`)
}

export function testSavedConnection(id: number): Promise<ConnectionTestResponse> {
  return http.post(`${BASE}/${id}/test`)
}

export function testAdHocConnection(body: ConnectionInfo): Promise<ConnectionTestResponse> {
  return http.post(`${BASE}/test`, body)
}
