export interface TenantInfo {
  id: number
  name: string
  logo?: string
  currency: string
  timezone: string
  /** Plan de Ecomerxio POS (TIENDA, NEGOCIO o PRO) y fin de la prueba gratis (ISO), si está en prueba. */
  plan?: string
  planName?: string
  trialEndsAt?: string | null
  /** Hasta cuándo está pagada (ISO) y estado de la suscripción. */
  paidUntil?: string | null
  subscriptionState?: 'ACTIVA' | 'PRUEBA' | 'VENCIDA' | 'CORTESIA'
  /** Vencida: tras 5 días de gracia la cuenta queda en solo lectura (se puede consultar y pagar, no vender). */
  readOnlyFrom?: string | null
  readOnly?: boolean
  /** Topes del plan; sin valor = sin límite. */
  maxRegisters?: number | null
  maxUsers?: number | null
  /** Facturación electrónica activada por Ecomerxio para esta tienda (no viene en ningún plan). */
  einvoiceAddon?: boolean
}

/** Fase 16A: toda venta lleva documento (obligado) o solo cuando el cliente lo pide. */
export type InvoicingMode = 'OBLIGADO' | 'A_PEDIDO'

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
  /** Tipo de negocio y módulos activos (VARIANTES, COMBOS, SERIALES, BASCULA, LOTES, FIADO, CUENTAS_ABIERTAS, COMPRAS). */
  businessType?: string
  modules?: string[]
  /** Asistente con IA encendido en la plataforma (ASSISTANT_ENABLED en el servidor). */
  assistantEnabled?: boolean
  /** La tienda emite documentos electrónicos: la caja ofrece "el cliente pide factura". */
  invoicingEnabled?: boolean
  /** OBLIGADO: toda venta lleva documento. A_PEDIDO: solo cuando el cliente la pide (Fase 16A). */
  invoicingMode?: InvoicingMode | null
  /** Fase 16B: fecha de corte de la facturación y si las ventas anteriores se ocultan en informes. */
  fiscalStartAt?: string | null
  hidePreCutoff?: boolean
  /** Documentos legales de la versión vigente que este usuario aún no acepta (la app no deja seguir sin aceptarlos). */
  legalPending?: string[]
  legalVersion?: string
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
