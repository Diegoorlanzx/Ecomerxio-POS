export interface TenantInfo {
  id: number
  name: string
  logo?: string
  currency: string
  timezone: string
}

export interface Me {
  id: number
  fullName: string
  email: string
  phone?: string
  owner: boolean
  hasPin: boolean
  roles: string[]
  permissions: string[]
  tenant: TenantInfo
}

export interface LoginResponse {
  token: string
  expiresInMinutes: number
  me: Me
}

export interface RoleRef {
  id: number
  name: string
  systemCode?: string
}

export interface UserDto {
  id: number
  fullName: string
  email: string
  phone?: string
  active: boolean
  owner: boolean
  locked: boolean
  roles: RoleRef[]
  lastLoginAt?: string
  createdAt: string
}

export interface RoleDto {
  id: number
  name: string
  description?: string
  systemCode?: string
  editable: boolean
  deletable: boolean
  permissions: string[]
  userCount: number
}

export interface PermissionDto {
  code: string
  group: string
  label: string
}

export interface AuditDto {
  id: number
  userId?: number
  userName?: string
  action: string
  entityType?: string
  entityId?: number
  summary: string
  sensitive: boolean
  ip?: string
  createdAt: string
}

export interface AuditPage {
  items: AuditDto[]
  total: number
  page: number
  pages: number
}

export interface StoreDto {
  id: number
  name: string
  legalName?: string
  taxId?: string
  taxRegime?: string
  address?: string
  city?: string
  phone?: string
  whatsapp?: string
  email?: string
  logo?: string
  currency: string
  timezone: string
}
