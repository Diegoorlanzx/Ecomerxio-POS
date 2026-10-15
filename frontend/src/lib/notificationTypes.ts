export interface NotificationItem {
  id: number
  type: string
  typeLabel: string
  severity: 'CRITICA' | 'ALTA' | 'MEDIA' | 'INFO'
  title: string
  body?: string
  link?: string
  createdAt: string
  read: boolean
}

export interface Inbox {
  unread: number
  items: NotificationItem[]
}

export interface NotificationRule {
  type: string
  label: string
  description: string
  permission: string
  severity: string
  enabled: boolean
  threshold?: number | null
  thresholdLabel?: string
  email: boolean
  webhook: boolean
  whatsapp: boolean
}

export interface NotificationChannels {
  emailTo?: string
  webhookUrl?: string
  webhookSecretSet: boolean
  whatsappPhoneId?: string
  whatsappTokenSet: boolean
  whatsappTo?: string
  dailyReportHour: number
  lastScanAt?: string
  smtpConfigured: boolean
}

export interface DeliveryItem {
  id: number
  notificationId: number
  channel: 'EMAIL' | 'WEBHOOK' | 'WHATSAPP'
  status: 'ENVIADO' | 'FALLIDO' | 'SIN_CONFIGURAR'
  detail?: string
  createdAt: string
}

export interface BackupItem {
  id: number
  kind: 'MANUAL' | 'AUTOMATICA' | 'ANTES_RESTAURAR'
  schemaVersion: string
  sizeBytes: number
  checksum: string
  rowCounts: Record<string, number>
  createdBy?: string
  createdAt: string
}

export interface Branch {
  id: number
  name: string
  address?: string
  phone?: string
  main: boolean
  active: boolean
  registers: number
}

export const SEVERITY_DOT: Record<string, string> = {
  CRITICA: 'bg-danger',
  ALTA: 'bg-warning',
  MEDIA: 'bg-primary',
  INFO: 'bg-slate-400',
}

export const SEVERITY_ICON: Record<string, { icon: string; tone: string }> = {
  CRITICA: { icon: 'error', tone: 'bg-danger-soft text-danger-ink' },
  ALTA: { icon: 'warning', tone: 'bg-warning-soft text-warning-ink' },
  MEDIA: { icon: 'notifications', tone: 'bg-primary-tint text-primary' },
  INFO: { icon: 'insights', tone: 'bg-line-soft text-muted' },
}
