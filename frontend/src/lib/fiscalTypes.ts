export interface Option {
  code: string
  label: string
}

export interface FiscalCatalogs {
  idTypes: Option[]
  responsibilities: Option[]
  documentTypes: Option[]
  taxClasses: Option[]
}

export interface FiscalProfile {
  personType: 'NATURAL' | 'JURIDICA' | null
  idType: string
  idNumber: string | null
  dv: string | null
  expectedDv: string | null
  legalName: string | null
  /** Persona natural: nombres y apellidos por separado. */
  firstNames: string | null
  lastNames: string | null
  tradeName: string | null
  /** Matrícula mercantil, opcional. */
  registrationCode: string | null
  ivaResponsible: boolean | null
  responsibilities: string[]
  ciiu: string | null
  address: string | null
  municipalityCode: string | null
  municipalityName: string | null
  departmentName: string | null
  postalCode: string | null
  invoicingEmail: string | null
  phone: string | null
  registeredAsIssuer: boolean
  issuerRegisteredOn: string | null
  providerName: string | null
  providerAuthorized: boolean
  environment: 'PRUEBAS' | 'PRODUCCION'
}

export type ResolutionStatus = 'VIGENTE' | 'POR_VENCER' | 'POR_AGOTAR' | 'VENCIDA' | 'AGOTADA' | 'NO_INICIA' | 'INACTIVA'

export interface Resolution {
  id: number
  documentType: string
  documentLabel: string
  resolutionNumber: string
  resolutionDate: string | null
  prefix: string | null
  rangeFrom: number
  rangeTo: number
  nextNumber: number
  used: number
  remaining: number
  usedPct: number
  validFrom: string
  validTo: string
  daysLeft: number
  environment: 'PRUEBAS' | 'PRODUCCION'
  hasTechnicalKey: boolean
  active: boolean
  status: ResolutionStatus
}

export interface ProductTaxRow {
  id: number
  sku: string | null
  name: string
  category: string | null
  unit: string
  unitCode: string | null
  taxName: string
  taxRate: number
  taxClass: string | null
  effectiveClass: string | null
  pending: boolean
}

export interface ReadinessStep {
  key: string
  title: string
  status: 'OK' | 'PENDIENTE' | 'BLOQUEADO'
  detail: string
  action: string | null
}

export interface Readiness {
  ready: boolean
  done: number
  total: number
  steps: ReadinessStep[]
  environment: 'PRUEBAS' | 'PRODUCCION'
  invoicingEnabled: boolean
}

export type DocumentStatus = 'PENDIENTE' | 'EN_PROCESO' | 'DESCONOCIDO' | 'ACEPTADO' | 'RECHAZADO' | 'BLOQUEADO' | 'ANULADO'

export interface ElectronicDocumentDto {
  id: number
  documentType: 'POS_ELECTRONICO' | 'FACTURA' | 'NOTA_CREDITO'
  documentLabel: string
  fullNumber: string
  status: DocumentStatus
  environment: 'PRUEBAS' | 'PRODUCCION'
  provider: string
  simulated: boolean
  test: boolean
  saleId: number | null
  saleNumber: number | null
  returnId: number | null
  originalNumber: string | null
  customerName: string | null
  customerIdNumber: string | null
  total: number
  taxTotal: number
  issueDate: string
  cufe: string | null
  qrData: string | null
  attempts: number
  nextAttemptAt: string
  lastErrorCode: string | null
  lastErrorMessage: string | null
  reason: string | null
  acceptedAt: string | null
  createdAt: string
  /** Guía del error (Fase 13C): qué pasó, causa, qué hacer y dónde corregir. */
  help?: ErrorHelp | null
}

export interface ErrorHelp {
  title: string
  explanation: string
  cause: string
  recommendation: string
  fixTab: 'datos' | 'clientes' | 'productos' | 'resoluciones' | null
  action: 'RETRY' | 'CHECK' | 'CONFIG' | 'NOTE'
}

export interface BulkRetryResult {
  requested: number
  accepted: number
  stillWithProblems: number
  documents: ElectronicDocumentDto[]
}

export interface DocumentEvent {
  type: string
  statusAfter: string | null
  code: string | null
  message: string | null
  userName: string | null
  createdAt: string
}

export interface DocumentDetail {
  document: ElectronicDocumentDto
  events: DocumentEvent[]
  notes: ElectronicDocumentDto[]
}

export interface EInvoiceSummary {
  enabled: boolean
  environment: 'PRUEBAS' | 'PRODUCCION'
  provider: string
  simulated: boolean
  byStatus: Record<string, number>
}

export const DOC_STATUS: Record<DocumentStatus, { label: string; tone: 'success' | 'warning' | 'danger' | 'neutral' | 'primary' }> = {
  PENDIENTE: { label: 'Pendiente', tone: 'neutral' },
  EN_PROCESO: { label: 'En proceso', tone: 'primary' },
  DESCONOCIDO: { label: 'Por verificar', tone: 'warning' },
  ACEPTADO: { label: 'Aceptado', tone: 'success' },
  RECHAZADO: { label: 'Rechazado', tone: 'danger' },
  BLOQUEADO: { label: 'Bloqueado', tone: 'danger' },
  ANULADO: { label: 'Anulado', tone: 'neutral' },
}

export { MUNICIPALITIES } from './municipalities'
