import { useMemo, useState } from 'react'
import { Banner, Button, Card, Field, Icon, SelectField, TextArea, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import type { Receipt, SaleDto, StoreSettings } from '../../lib/posTypes'
import {
  FLAG_GROUPS,
  LOCKED_FOR_INVOICE,
  PRESETS,
  PROFILE_LABELS,
  lock,
  parseReceiptConfig,
  presetProfile,
  type ReceiptConfig,
  type ReceiptFlag,
  type ReceiptPreset,
  type ReceiptProfileName,
} from '../../lib/receiptConfig'
import { ReceiptView } from '../pos/Receipt'

/** Fase 16C: plantillas de un clic, casillas por grupo y vista previa en vivo, por tipo de comprobante. */
export default function ReceiptDesigner({ settings, editable, onSaved }: { settings: StoreSettings; editable: boolean; onSaved: (s: StoreSettings) => void }) {
  const toast = useToast()
  const { me } = useAuth()
  const [cfg, setCfg] = useState<ReceiptConfig>(() => parseReceiptConfig(settings.receiptConfig, settings.receiptShowTax))
  const [profile, setProfile] = useState<ReceiptProfileName>('INTERNO')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const p = cfg.profiles[profile]
  const sample = useMemo(() => sampleReceipt(settings, me?.tenant.name ?? 'Mi tienda', me?.tenant.currency ?? 'COP', profile), [settings, me, profile])

  const setProfileFlags = (next: ReceiptConfig['profiles'][ReceiptProfileName]) => setCfg({ ...cfg, profiles: { ...cfg.profiles, [profile]: lock(profile, next) } })

  function toggle(flag: ReceiptFlag) {
    setProfileFlags({ ...p, [flag]: !p[flag], preset: 'PERSONALIZADO' })
  }

  async function save() {
    setSaving(true)
    setError('')
    try {
      const saved = await api<StoreSettings>('/settings/pos/receipt', { method: 'PUT', body: { config: cfg } })
      onSaved(saved)
      toast('Diseño del recibo guardado')
    } catch (e) {
      setError(e instanceof ApiError ? Object.values(e.fields)[0] ?? e.message : 'No se pudo guardar')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Card className="p-4">
      <h3 className="font-semibold">Diseño del recibo</h3>
      <p className="mb-3 text-xs text-muted">Elige qué sale en cada tipo de comprobante. La vista previa se actualiza al instante.</p>
      <div className="mb-3 flex flex-wrap gap-1.5">
        {(Object.keys(PROFILE_LABELS) as ReceiptProfileName[]).map((n) => (
          <button
            key={n}
            type="button"
            onClick={() => setProfile(n)}
            className={`rounded-full px-3 py-1.5 text-xs font-semibold ${profile === n ? 'bg-primary text-white' : 'bg-canvas text-muted hover:text-ink'}`}
          >
            {PROFILE_LABELS[n].label}
          </button>
        ))}
      </div>
      <p className="mb-3 text-xs text-muted">{PROFILE_LABELS[profile].hint}</p>
      <div className="grid gap-4 lg:grid-cols-[1fr_auto]">
        <fieldset disabled={!editable} className="space-y-4">
          <div className="grid grid-cols-3 gap-2">
            {(Object.keys(PRESETS) as Exclude<ReceiptPreset, 'PERSONALIZADO'>[]).map((k) => (
              <button
                key={k}
                type="button"
                onClick={() => setProfileFlags(presetProfile(profile, k))}
                className={`rounded-xl border p-2.5 text-left ${p.preset === k ? 'border-primary bg-primary-tint' : 'border-line hover:bg-canvas'}`}
              >
                <span className="block text-sm font-semibold">{PRESETS[k].label}</span>
                <span className="block text-[11px] text-muted">{PRESETS[k].hint}</span>
              </button>
            ))}
          </div>
          {p.preset === 'PERSONALIZADO' && <p className="text-xs text-muted">Personalizado: cambiaste casillas de la plantilla.</p>}
          <div className="grid gap-3 sm:grid-cols-2">
            {FLAG_GROUPS.map((g) => (
              <div key={g.title}>
                <div className="mb-1 text-xs font-semibold tracking-wide text-muted uppercase">{g.title}</div>
                {g.flags.map(([flag, label]) => {
                  const locked = profile === 'FACTURA' && (LOCKED_FOR_INVOICE.includes(flag) || flag === 'groupAsVarios')
                  return (
                    <label key={flag} className={`flex items-center gap-2 py-0.5 text-sm ${locked ? 'text-muted' : ''}`}>
                      <input type="checkbox" checked={p[flag]} disabled={locked} onChange={() => toggle(flag)} className="accent-[var(--color-primary)]" />
                      {label}
                      {locked && <Icon name="lock" className="text-[14px]" />}
                    </label>
                  )
                })}
              </div>
            ))}
          </div>
          {profile === 'FACTURA' && (
            <p className="text-xs text-muted">
              <Icon name="lock" className="align-middle text-[14px]" /> Lo que exige la DIAN en la factura impresa (emisor, numeración, comprador, detalle, impuestos, CUFE y QR)
              no se puede quitar.
            </p>
          )}
          <div className="grid gap-3 sm:grid-cols-3">
            <SelectField label="Imprimir al cobrar" value={cfg.printMode} onChange={(e) => setCfg({ ...cfg, printMode: e.target.value as ReceiptConfig['printMode'] })}>
              <option value="SIEMPRE">Siempre</option>
              <option value="PREGUNTAR">Preguntar</option>
              <option value="NUNCA">Nunca (solo digital)</option>
            </SelectField>
            <SelectField label="Copias" value={String(cfg.copies)} onChange={(e) => setCfg({ ...cfg, copies: Number(e.target.value) })}>
              <option value="1">1</option>
              <option value="2">2</option>
              <option value="3">3</option>
            </SelectField>
            <SelectField label="Tamaño de letra" value={cfg.fontSize} onChange={(e) => setCfg({ ...cfg, fontSize: e.target.value as ReceiptConfig['fontSize'] })}>
              <option value="S">Pequeña</option>
              <option value="M">Normal</option>
              <option value="L">Grande</option>
            </SelectField>
          </div>
          <TextArea
            label="Política de cambios y devoluciones"
            value={cfg.returnPolicy ?? ''}
            onChange={(e) => setCfg({ ...cfg, returnPolicy: e.target.value || null })}
            placeholder="Ej: Cambios hasta 8 días con el recibo y el producto sin uso."
          />
          <Field label="Encabezado y pie" value="Se escriben arriba, en «Comprobante de venta»" disabled />
          {error && <Banner>{error}</Banner>}
          {editable && (
            <div className="flex justify-end">
              <Button icon="save" loading={saving} onClick={save}>
                Guardar diseño
              </Button>
            </div>
          )}
        </fieldset>
        <div className="rounded-xl bg-line-soft p-3">
          <div className="mb-2 text-center text-xs font-semibold text-muted">Vista previa</div>
          <ReceiptView r={sample} design={{ config: cfg, profile }} />
        </div>
      </div>
    </Card>
  )
}

/** Una venta de ejemplo para la vista previa, con lo propio de cada tipo de comprobante. */
function sampleReceipt(st: StoreSettings, storeName: string, currency: string, profile: ReceiptProfileName): Receipt {
  const lines = [
    { id: 1, presentationId: 1, productName: 'Arroz Diana 500 g', presentationName: 'Unidad', quantity: 2, unitPrice: 2800, gross: 5600, discount: 0 },
    { id: 2, presentationId: 2, productName: 'Gaseosa 1,5 L', presentationName: 'Unidad', quantity: 1, unitPrice: 4500, gross: 4500, discount: 500, promoName: 'Promo gaseosa' },
    { id: 3, presentationId: 3, productName: 'Huevos AA', presentationName: 'Cubeta x 30', quantity: 1, unitPrice: 16000, gross: 16000, discount: 0 },
  ]
  const fiado = profile === 'FIADO'
  const sale = {
    id: 0,
    number: 1234,
    status: profile === 'PRECUENTA' ? 'ABIERTA' : 'PAGADA',
    accountLabel: 'Mesa 4',
    userName: 'Laura (cajera)',
    customerName: profile === 'INTERNO' ? null : 'Ferretería El Tornillo SAS',
    customerDoc: profile === 'INTERNO' ? null : '900.123.456-7',
    subtotal: 26100,
    discountTotal: 500,
    taxTotal: 719,
    total: 25600,
    changeAmount: fiado ? 0 : 4400,
    refundedTotal: 0,
    createdAt: new Date().toISOString(),
    completedAt: new Date().toISOString(),
    lines,
    payments: [fiado ? { method: 'FIADO', amount: 25600 } : { method: 'EFECTIVO', amount: 25600, received: 30000 }],
    electronicDocument:
      profile === 'FACTURA'
        ? {
            documentLabel: 'Factura electrónica de venta',
            fullNumber: 'FE 1024',
            status: 'ACEPTADO',
            cufe: 'ejemplo-cufe-0f3a9c',
            qrData: 'https://catalogo-vpfe.dian.gov.co/document/searchqr?documentkey=ejemplo',
            simulated: false,
            paymentForm: 'CONTADO',
          }
        : null,
  } as unknown as SaleDto
  return {
    sale,
    storeName,
    legalName: `${storeName} SAS`,
    taxId: '900.000.000-1',
    taxRegime: 'Responsable de IVA',
    address: 'Calle 10 # 5-20',
    city: 'Bogotá',
    phone: '300 123 4567',
    header: st.receiptHeader,
    footer: st.receiptFooter,
    widthMm: st.receiptWidthMm,
    showTax: st.receiptShowTax,
    currency,
    creditBalance: fiado ? 85600 : null,
  }
}
