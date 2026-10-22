import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Banner, Button, Card, Chip, EmptyState, Field, Icon, Modal, SelectField, Spinner, TextArea, Toggle, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { money, pct, qty } from '../../lib/format'
import { MUNICIPALITIES } from '../../lib/municipalities'
import { WEEK_DAYS, type Supplier } from '../../lib/purchasingTypes'

export default function SuppliersPage() {
  const { can } = useAuth()
  const toast = useToast()
  const [list, setList] = useState<Supplier[] | null>(null)
  const [q, setQ] = useState('')
  const [editing, setEditing] = useState<Supplier | 'new' | null>(null)
  const [error, setError] = useState('')

  const load = useCallback(() => {
    api<Supplier[]>('/suppliers').then(setList).catch((e) => setError(e.message))
  }, [])
  useEffect(load, [load])

  const visible = (list ?? []).filter((s) => !q || s.name.toLowerCase().includes(q.toLowerCase()))
  const totalDebt = (list ?? []).reduce((s, x) => s + (x.metrics.balance ?? 0), 0)

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h2 className="text-[22px] font-semibold tracking-tight">Proveedores</h2>
          <p className="num text-sm text-muted">
            {list?.length ?? 0} proveedores{can('COSTOS_VER') || can('COMPRAS_VER') ? ` · le debes ${money(totalDebt)} en total` : ''}
          </p>
        </div>
        {can('PROVEEDORES_EDITAR') && (
          <Button icon="add_business" onClick={() => setEditing('new')}>
            Nuevo proveedor
          </Button>
        )}
      </div>
      <div className="relative">
        <Icon name="search" className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-[20px] text-muted" />
        <input
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder="Buscar proveedor"
          className="h-12 w-full rounded-xl border border-line bg-card pr-3 pl-10 text-base outline-none focus:border-primary"
        />
      </div>
      {error && <Banner>{error}</Banner>}
      {!list ? (
        <Spinner />
      ) : visible.length === 0 ? (
        <Card>
          <EmptyState icon="local_shipping" title="Sin proveedores" text="Registra a quién le compras para que el sistema sugiera los pedidos." />
        </Card>
      ) : (
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          {visible.map((s) => (
            <Link key={s.id} to={`/proveedores/${s.id}`} className={`rounded-2xl bg-card p-4 shadow-tier1 ring-1 ring-line transition hover:ring-primary ${s.active ? '' : 'opacity-60'}`}>
              <div className="flex items-start justify-between gap-2">
                <div className="min-w-0">
                  <div className="truncate font-semibold">{s.name}</div>
                  <div className="truncate text-xs text-muted">
                    {[s.contactName, s.whatsapp ?? s.phone].filter(Boolean).join(' · ') || 'Sin contacto'}
                  </div>
                </div>
                {!s.active && <Chip>Inactivo</Chip>}
              </div>
              <div className="num mt-3 grid grid-cols-3 gap-2 text-center">
                <Metric label="Entrega" value={`${qty(s.metrics.effectiveLeadDays)} d`} />
                <Metric label="A tiempo" value={s.metrics.onTimePct !== undefined && s.metrics.onTimePct !== null ? pct(s.metrics.onTimePct) : '—'} />
                <Metric label="Cumple" value={s.metrics.fillRatePct !== undefined && s.metrics.fillRatePct !== null ? pct(s.metrics.fillRatePct) : '—'} />
              </div>
              <div className="mt-3 flex items-center justify-between text-xs">
                <span className="text-muted">
                  {s.metrics.productCount} productos{s.orderDays ? ` · pide ${s.orderDays.toLowerCase()}` : ''}
                </span>
                {s.metrics.balance > 0 && <Chip tone="warning">Debes {money(s.metrics.balance)}</Chip>}
              </div>
            </Link>
          ))}
        </div>
      )}
      {editing && (
        <SupplierForm
          supplier={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null)
            toast('Proveedor guardado')
            load()
          }}
        />
      )}
    </div>
  )
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-xl bg-canvas px-2 py-2">
      <div className="text-[10px] font-bold tracking-wide text-muted uppercase">{label}</div>
      <div className="text-sm font-bold">{value}</div>
    </div>
  )
}

export function SupplierForm({ supplier, onClose, onSaved }: { supplier: Supplier | null; onClose: () => void; onSaved: (s: Supplier) => void }) {
  const [f, setF] = useState({
    name: supplier?.name ?? '',
    legalName: supplier?.legalName ?? '',
    taxId: supplier?.taxId ?? '',
    contactName: supplier?.contactName ?? '',
    phone: supplier?.phone ?? '',
    whatsapp: supplier?.whatsapp ?? '',
    email: supplier?.email ?? '',
    address: supplier?.address ?? '',
    city: supplier?.city ?? '',
    paymentTerms: supplier?.paymentTerms ?? '',
    creditDays: String(supplier?.creditDays ?? 0),
    leadTimeDays: supplier?.leadTimeDays !== undefined && supplier?.leadTimeDays !== null ? String(supplier.leadTimeDays) : '',
    notes: supplier?.notes ?? '',
    active: supplier?.active ?? true,
    invoicesElectronically: supplier?.invoicesElectronically ?? true,
    docType: supplier?.docType ?? 'NIT',
    dv: supplier?.dv ?? '',
    personType: supplier?.personType ?? '',
    municipalityCode: supplier?.municipalityCode ?? '',
    ivaResponsible: supplier?.ivaResponsible == null ? '' : supplier.ivaResponsible ? 'SI' : 'NO',
    incomeTaxDeclarant: supplier?.incomeTaxDeclarant ?? true,
    selfWithholder: supplier?.selfWithholder ?? false,
    simpleRegime: supplier?.simpleRegime ?? false,
    greatContributor: supplier?.greatContributor ?? false,
  })
  const municipality = MUNICIPALITIES.find(([c]) => c === f.municipalityCode)
  const [orderDays, setOrderDays] = useState<string[]>(supplier?.orderDays?.split(',') ?? [])
  const [deliveryDays, setDeliveryDays] = useState<string[]>(supplier?.deliveryDays?.split(',') ?? [])
  const [fields, setFields] = useState<Record<string, string>>({})
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => setF({ ...f, [k]: e.target.value })

  async function save() {
    setSaving(true)
    setFields({})
    setError('')
    const body = {
      ...f,
      creditDays: Number(f.creditDays || 0),
      leadTimeDays: f.leadTimeDays === '' ? null : Number(f.leadTimeDays),
      orderDays: orderDays.join(','),
      deliveryDays: deliveryDays.join(','),
      personType: f.personType || null,
      dv: f.docType === 'NIT' ? f.dv : '',
      ivaResponsible: f.ivaResponsible === '' ? null : f.ivaResponsible === 'SI',
    }
    try {
      const s = supplier ? await api<Supplier>(`/suppliers/${supplier.id}`, { method: 'PUT', body }) : await api<Supplier>('/suppliers', { body })
      onSaved(s)
    } catch (e) {
      if (e instanceof ApiError) {
        setFields(e.fields)
        if (!Object.keys(e.fields).length) setError(e.message)
      }
    } finally {
      setSaving(false)
    }
  }

  const dayPicker = (value: string[], setValue: (v: string[]) => void) => (
    <div className="flex flex-wrap gap-1.5">
      {WEEK_DAYS.map((d) => (
        <button
          key={d}
          type="button"
          onClick={() => setValue(value.includes(d) ? value.filter((x) => x !== d) : [...value, d])}
          className={`h-9 w-12 rounded-lg text-xs font-bold ${value.includes(d) ? 'bg-primary text-white' : 'bg-line-soft text-muted'}`}
        >
          {d.charAt(0) + d.slice(1).toLowerCase()}
        </button>
      ))}
    </div>
  )

  return (
    <Modal
      open
      wide
      title={supplier ? `Editar ${supplier.name}` : 'Nuevo proveedor'}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" className="flex-1" onClick={onClose}>
            Cancelar
          </Button>
          <Button className="flex-1" onClick={save} loading={saving}>
            Guardar
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        {error && <Banner>{error}</Banner>}
        <div className="grid gap-3 sm:grid-cols-3">
          <Field label="Nombre comercial" value={f.name} onChange={set('name')} error={fields.name} className="sm:col-span-2" />
          <Field label={f.docType === 'NIT' ? 'NIT' : 'Número de documento'} value={f.taxId} onChange={set('taxId')} error={fields.taxId} />
          <Field label="Razón social" value={f.legalName} onChange={set('legalName')} />
          <Field label="Contacto" value={f.contactName} onChange={set('contactName')} />
          <Field label="Teléfono" type="tel" value={f.phone} onChange={set('phone')} />
          <Field label="WhatsApp" type="tel" value={f.whatsapp} onChange={set('whatsapp')} error={fields.whatsapp} />
          <Field label="Correo" type="email" value={f.email} onChange={set('email')} error={fields.email} />
          <Field label="Ciudad" value={f.city} onChange={set('city')} />
          <Field label="Dirección" value={f.address} onChange={set('address')} className="sm:col-span-3" />
        </div>
        <div className="grid gap-3 sm:grid-cols-3">
          <Field label="Condiciones de pago" value={f.paymentTerms} onChange={set('paymentTerms')} placeholder="Ej: contado, 50% anticipo…" />
          <Field label="Días de crédito" inputMode="numeric" value={f.creditDays} onChange={set('creditDays')} error={fields.creditDays} />
          <Field
            label="Tiempo de entrega (días)"
            inputMode="numeric"
            value={f.leadTimeDays}
            onChange={set('leadTimeDays')}
            error={fields.leadTimeDays}
            hint="Vacío = el sistema lo mide con las entregas"
          />
        </div>
        <div className="grid gap-3 sm:grid-cols-2">
          <div>
            <div className="mb-1.5 text-xs font-semibold tracking-wide text-muted uppercase">Días de pedido</div>
            {dayPicker(orderDays, setOrderDays)}
            {fields.orderDays && <p className="mt-1 text-xs text-danger-ink">{fields.orderDays}</p>}
          </div>
          <div>
            <div className="mb-1.5 text-xs font-semibold tracking-wide text-muted uppercase">Días de entrega</div>
            {dayPicker(deliveryDays, setDeliveryDays)}
          </div>
        </div>
        <div className="space-y-3 rounded-xl border border-line p-3">
          <Toggle
            checked={f.invoicesElectronically}
            onChange={(v) => setF({ ...f, invoicesElectronically: v })}
            label="Me entrega factura electrónica"
            hint={
              f.invoicesElectronically
                ? 'Sus compras se soportan con la factura que te envía (puedes importar el XML en Compras).'
                : 'No está obligado a facturar (p. ej. un campesino o un independiente): al recibirle mercancía se emite el documento soporte con estos datos.'
            }
          />
          <div className="grid gap-3 sm:grid-cols-3">
            <SelectField label="Tipo de documento" value={f.docType} onChange={(e) => setF({ ...f, docType: e.target.value })} error={fields.docType}>
              <option value="NIT">NIT</option>
              <option value="CC">Cédula de ciudadanía</option>
              <option value="CE">Cédula de extranjería</option>
              <option value="PP">Pasaporte</option>
            </SelectField>
            {f.docType === 'NIT' && (
              <Field
                label="Dígito de verificación"
                inputMode="numeric"
                maxLength={1}
                value={f.dv}
                onChange={(e) => setF({ ...f, dv: e.target.value.replace(/\D/g, '') })}
                error={fields.dv}
              />
            )}
            <SelectField label="Tipo de persona" value={f.personType} onChange={(e) => setF({ ...f, personType: e.target.value })} error={fields.personType}>
              <option value="">Elige…</option>
              <option value="NATURAL">Persona natural</option>
              <option value="JURIDICA">Persona jurídica</option>
            </SelectField>
            <Field
              label="Municipio"
              list="municipios-dane-proveedores"
              placeholder="Nombre o código DANE"
              value={f.municipalityCode}
              onChange={(e) => setF({ ...f, municipalityCode: e.target.value.trim() })}
              error={fields.municipalityCode || (f.municipalityCode && !municipality ? 'Elige un municipio de la lista' : undefined)}
              hint={municipality ? `${municipality[1]} (${municipality[2]})` : undefined}
            />
            <datalist id="municipios-dane-proveedores">
              {MUNICIPALITIES.map(([code, n, d]) => (
                <option key={code} value={code}>{`${n} (${d})`}</option>
              ))}
            </datalist>
          </div>
          {!f.invoicesElectronically && <p className="text-xs text-muted">El documento soporte pide número de documento (arriba, en NIT), tipo de persona, dirección y municipio.</p>}
        </div>
        <details className="rounded-xl border border-line p-3">
          <summary className="cursor-pointer text-sm font-semibold">Datos tributarios (para proponer retenciones)</summary>
          <div className="mt-3 space-y-3">
            <SelectField label="¿Es responsable de IVA?" value={f.ivaResponsible} onChange={(e) => setF({ ...f, ivaResponsible: e.target.value })}>
              <option value="">No sé</option>
              <option value="SI">Sí</option>
              <option value="NO">No</option>
            </SelectField>
            <Toggle checked={f.incomeTaxDeclarant} onChange={(v) => setF({ ...f, incomeTaxDeclarant: v })} label="Declara renta (tarifa de declarante)" />
            <Toggle checked={f.selfWithholder} onChange={(v) => setF({ ...f, selfWithholder: v })} label="Es autorretenedor (no se le retiene en la fuente)" />
            <Toggle checked={f.simpleRegime} onChange={(v) => setF({ ...f, simpleRegime: v })} label="Está en el régimen simple (no se le retiene en la fuente)" />
            <Toggle checked={f.greatContributor} onChange={(v) => setF({ ...f, greatContributor: v })} label="Es gran contribuyente (no se le practica ReteIVA)" />
          </div>
        </details>
        <TextArea label="Notas" value={f.notes} onChange={set('notes')} rows={2} />
        {supplier && <Toggle checked={f.active} onChange={(v) => setF({ ...f, active: v })} label="Proveedor activo" />}
      </div>
    </Modal>
  )
}
