import { http } from '@/api/http'

/**
 * 库表枚举：给「表级 / 库级迁移」的穿梭框提供左侧树的数据。
 *
 *   GET /api/v1/migration/connections/{id}/schemas                        -> string[]
 *   GET /api/v1/migration/connections/{id}/schemas/{schema}/tables        -> string[]
 *
 * 后端按方言枚举并排除系统 schema（MySQL 的 mysql/performance_schema/sys/information_schema，
 * PostgreSQL 的 pg_* 与 information_schema），前端不再过滤。
 */
const BASE = '/api/v1/migration/connections'

export async function listSchemas(connectionId: number): Promise<string[]> {
  return http.get<string[]>(`${BASE}/${connectionId}/schemas`)
}

export async function listTables(connectionId: number, schema: string): Promise<string[]> {
  return http.get<string[]>(`${BASE}/${connectionId}/schemas/${encodeURIComponent(schema)}/tables`)
}
