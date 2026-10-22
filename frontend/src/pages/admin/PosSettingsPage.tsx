import { useEffect, useState } from 'react'
import { Banner, Button, Card, Chip, Field, Icon, SelectField, Spinner, TextArea, Toggle, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { METHOD_ICONS, METHOD_LABELS, type RegisterDto, type StoreSettings } from '../../lib/posTypes'
import ReceiptDesigner from './ReceiptDesigner'

const ALL_METHODS = ['EFECTIVO', 'TRANSFERENCIA', 'NEQUI', 'DAVIPLATA', 'BREB', 'TARJETA', 'OTRO']

/** Impuestos, comprobante, medios de pago, límites de la caja y cajas de la tienda. */
export default function PosSettingsPage() {
  const { can } = useAuth()
  const toast = useToast()
  const editable = can('CONFIG_EDITAR')
  const [s, setS] = useState<StoreSettings | null>(null)
  const [registers, setRegisters] = useState<RegisterDto[]>([])
  const [newRegister, setNewRegister] = useState('')
  const [fields, setFields] = useState<Record<string, string>>({})
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  const loadRegisters = () => api<RegisterDto[]>('/registers').then(setRegisters).catch(() => {})
  useEffect(() => {
    api<StoreSettings>('/settings/pos').then(setS).catch((e) => setError(e.message))
    loadRegisters()
  }, [])

  if (!s) return error ? <Banner>{error}</Banner> : <Spinner />
  const methods = s.paymentMethods.split(',').filter(Boolean)

  function toggleMethod(m: string) {
    if (!s || m === 'EFECTIVO') return
    const next = methods.includes(m) ? methods.filter((x) => x !== m) : [...methods, m]
    // El fiado se activa en "Pagos digitales y fiado" y se conserva aquí.
    setS({ ...s, paymentMethods: [...ALL_METHODS.filter((x) => next.includes(x)), ...(methods.includes('FIADO') ? ['FIADO'] : [])].join(',') })
  }

  async function save() {
    if (!s) return
    setSaving(true)
    setFields({})
    setError('')
    try {
      const saved = await api<StoreSettings>('/settings/pos', {
        method: 'PUT',
        body: { ...s, paymentMethods: methods, maxDiscountPct: Number(s.maxDiscountPct), defaultTaxRate: Number(s.defaultTaxRate), openAccountsLimit: Number(s.openAccountsLimit) },
      })
      setS(saved)
      toast('Configuración guardada')
    } catch (e) {
      if (e instanceof ApiError) {
        setFields(e.fields)
        if (!Object.keys(e.fields).length) setError(e.message)
      }
    } finally {
      setSaving(false)
    }
  }

  async function saveRegister(id: number | null, name: string, active?: boolean) {
    try {
      if (id) await api(`/registers/${id}`, { method: 'PUT', body: { name, active } })
      else await api('/registers', { body: { name } })
      setNewRegister('')
      loadRegisters()
      toast('Caja guardada')
    } catch (e) {
      toast(e instanceof ApiError ? Object.values(e.fields)[0] ?? e.message : 'No se pudo guardar', 'danger')
    }
  }

  return (
    <div className="mx-auto max-w-3xl space-y-4">
      <div>
        <h2 className="text-[22px] font-semibold tracking-tight">Ajustes de caja y venta</h2>
        <p className="text-sm text-muted">Impuestos, comprobante, medios de pago y reglas del punto de venta.</p>
      </div>
      {!editable && <Banner tone="warning">Solo lectura: necesitas el permiso de configuración para cambiar esto.</Banner>}
      {error && <Banner>{error}</Banner>}
      <fieldset disabled={!editable} className="space-y-4">
        <Card className="p-4">
          <h3 className="mb-2 font-semibold">Impuestos</h3>
          <Toggle
            checked={s.pricesIncludeTax}
            onChange={(v) => setS({ ...s, pricesIncludeTax: v })}
            label="Los precios ya incluyen impuestos"
            hint="Lo normal en tiendas: el cliente paga el precio de la etiqueta y el impuesto se informa dentro."
          />
          <div className="mt-2 grid grid-cols-2 gap-3">
            <Field label="Impuesto por defecto" value={s.defaultTaxName} onChange={(e) => setS({ ...s, defaultTaxName: e.target.value })} error={fields.defaultTaxName} />
            <Field label="Tarifa por defecto %" inputMode="decimal" value={String(s.defaultTaxRate)} onChange={(e) => setS({ ...s, defaultTaxRate: e.target.value as unknown as number })} error={fields.defaultTaxRate} hint="Para productos nuevos" />
          </div>
        </Card>

        <Card className="p-4">
          <h3 className="mb-2 font-semibold">Medios de pago habilitados</h3>
          {fields.paymentMethods && <p className="mb-2 text-xs font-medium text-danger-ink">{fields.paymentMethods}</p>}
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
            {ALL_METHODS.map((m) => (
              <button
                key={m}
                type="button"
                onClick={() => toggleMethod(m)}
                className={`flex items-center gap-2 rounded-xl border p-3 text-left text-sm font-semibold ${methods.includes(m) ? 'border-primary bg-primary-tint text-primary' : 'border-line text-muted'}`}
              >
                <Icon name={METHOD_ICONS[m] ?? 'payments'} className="text-[20px]" />
                {METHOD_LABELS[m]}
              </button>
            ))}
          </div>
          <p className="mt-2 text-xs text-muted">Nequi, Daviplata y Bre-B se registran con su referencia y quedan por verificar hasta confirmar que el dinero llegó. El fiado se activa abajo.</p>
        </Card>

        <Card className="p-4">
          <h3 className="mb-2 font-semibold">Reglas de la caja</h3>
          <div className="grid gap-3 sm:grid-cols-3">
            <Field
              label="Descuento máximo sin autorización %"
              inputMode="decimal"
              value={String(s.maxDiscountPct)}
              onChange={(e) => setS({ ...s, maxDiscountPct: e.target.value as unknown as number })}
              error={fields.maxDiscountPct}
            />
            <Field
              label="Cuentas abiertas al tiempo"
              inputMode="numeric"
              value={String(s.openAccountsLimit)}
              onChange={(e) => setS({ ...s, openAccountsLimit: Number(e.target.value.replace(/\D/g, '')) })}
              error={fields.openAccountsLimit}
            />
            <SelectField label="Método de costo" value={s.costMethod} onChange={(e) => setS({ ...s, costMethod: e.target.value as 'PROMEDIO' | 'ULTIMO' })}>
              <option value="PROMEDIO">Costo promedio ponderado</option>
              <option value="ULTIMO">Último costo de compra</option>
            </SelectField>
          </div>
          <div className="mt-2 divide-y divide-line-soft">
            <Toggle checked={s.requireOpenShift} onChange={(v) => setS({ ...s, requireOpenShift: v })} label="Exigir turno abierto para vender" hint="Recomendado: así cada venta queda en el arqueo de un cajero." />
            <Toggle checked={s.allowNegativeStock} onChange={(v) => setS({ ...s, allowNegativeStock: v })} label="Permitir vender sin stock" hint="Si está apagado, la caja no deja vender lo que el sistema dice que no hay." />
          </div>
          <Field
            className="mt-2"
            label="Denominaciones para el arqueo"
            value={s.cashDenominations}
            onChange={(e) => setS({ ...s, cashDenominations: e.target.value })}
            error={fields.cashDenominations}
            hint="Billetes y monedas separados por coma"
          />
        </Card>

        <Card className="p-4">
          <h3 className="mb-2 font-semibold">Comprobante de venta</h3>
          <div className="grid gap-3 sm:grid-cols-2">
            <SelectField label="Ancho del papel" value={String(s.receiptWidthMm)} onChange={(e) => setS({ ...s, receiptWidthMm: Number(e.target.value) })}>
              <option value="80">80 mm (tiquetera estándar)</option>
              <option value="58">58 mm (tiquetera pequeña)</option>
            </SelectField>
            <div className="flex items-end">
              <Toggle checked={s.receiptShowTax} onChange={(v) => setS({ ...s, receiptShowTax: v })} label="Mostrar impuestos" />
            </div>
            <TextArea label="Encabezado" value={s.receiptHeader ?? ''} onChange={(e) => setS({ ...s, receiptHeader: e.target.value })} error={fields.receiptHeader} placeholder="Ej: Régimen simple · Resolución…" />
            <TextArea label="Pie de página" value={s.receiptFooter ?? ''} onChange={(e) => setS({ ...s, receiptFooter: e.target.value })} error={fields.receiptFooter} />
          </div>
        </Card>
      </fieldset>
      {editable && (
        <div className="flex justify-end">
          <Button size="lg" icon="save" onClick={save} loading={saving}>
            Guardar ajustes
          </Button>
        </div>
      )}

      <ReceiptDesigner editable={editable} settings={s} onSaved={setS} />
      <InventoryParamsCard editable={editable} settings={s} onSaved={setS} />
      <FinanceParamsCard editable={editable} settings={s} onSaved={setS} />
      <AnalyticsParamsCard editable={editable} settings={s} onSaved={setS} />

      <Card className="p-4">
        <h3 className="mb-2 font-semibold">Cajas de la tienda</h3>
        <p className="mb-3 text-sm text-muted">Cada caja tiene su propio turno y arqueo. Varias cajas pueden estar abiertas a la vez.</p>
        <div className="divide-y divide-line-soft rounded-xl border border-line">
          {registers.map((r) => (
            <div key={r.id} className="flex items-center gap-3 px-3 py-2.5">
              <Icon name="point_of_sale" className="text-[20px] text-primary" />
              <span className="flex-1 text-sm font-semibold">{r.name}</span>
              {r.openShiftId ? <Chip tone="success">Abierta · {r.openShiftUser}</Chip> : !r.active && <Chip>Inactiva</Chip>}
              {editable && !r.openShiftId && (
                <Button size="sm" variant="ghost" onClick={() => saveRegister(r.id, r.name, !r.active)}>
                  {r.active ? 'Desactivar' : 'Activar'}
                </Button>
              )}
            </div>
          ))}
        </div>
        {editable && (
          <div className="mt-3 flex gap-2">
            <input
              value={newRegister}
              onChange={(e) => setNewRegister(e.target.value)}
              placeholder="Nombre de la nueva caja"
              className="h-11 flex-1 rounded-xl border border-line px-3 text-sm outline-none focus:border-primary"
            />
            <Button icon="add" onClick={() => saveRegister(null, newRegister)} disabled={!newRegister.trim()}>
              Agregar caja
            </Button>
          </div>
        )}
      </Card>
    </div>
  )
}

/** Parámetros de inventario y compras: análisis, alertas, ABC, entrega y presupuesto. */
function InventoryParamsCard({ editable, settings, onSaved }: { editable: boolean; settings: StoreSettings; onSaved: (s: StoreSettings) => void }) {
  const toast = useToast()
  const [p, setP] = useState({
    analysisDays: String(settings.analysisDays),
    noRotationDays: String(settings.noRotationDays),
    excessDays: String(settings.excessDays),
    expiryAlertDays: String(settings.expiryAlertDays),
    defaultLeadDays: String(settings.defaultLeadDays),
    reviewDays: String(settings.reviewDays),
    unusualDiffPct: String(settings.unusualDiffPct),
    unusualDiffValue: String(settings.unusualDiffValue),
    abcAPct: String(settings.abcAPct),
    abcBPct: String(settings.abcBPct),
    purchaseBudget: String(settings.purchaseBudget),
  })
  const [fields, setFields] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState(false)
  const set = (k: keyof typeof p) => (e: React.ChangeEvent<HTMLInputElement>) => setP({ ...p, [k]: e.target.value })

  async function save() {
    setSaving(true)
    setFields({})
    try {
      const body = Object.fromEntries(Object.entries(p).map(([k, v]) => [k, Number(String(v).replace(',', '.'))]))
      onSaved(await api<StoreSettings>('/settings/pos/inventory', { method: 'PUT', body }))
      toast('Parámetros de inventario guardados')
    } catch (e) {
      if (e instanceof ApiError) setFields(Object.keys(e.fields).length ? e.fields : { analysisDays: e.message })
    } finally {
      setSaving(false)
    }
  }

  return (
    <Card className="p-4">
      <h3 className="mb-1 font-semibold">Inventario inteligente y compras</h3>
      <p className="mb-3 text-sm text-muted">Con estos parámetros se calculan la rotación, el exceso, las alertas y las sugerencias de compra.</p>
      <fieldset disabled={!editable} className="grid gap-3 sm:grid-cols-3">
        <Field label="Días de análisis de ventas" inputMode="numeric" value={p.analysisDays} onChange={set('analysisDays')} error={fields.analysisDays} />
        <Field label="Sin rotación después de (días)" inputMode="numeric" value={p.noRotationDays} onChange={set('noRotationDays')} error={fields.noRotationDays} />
        <Field label="Exceso si alcanza más de (días)" inputMode="numeric" value={p.excessDays} onChange={set('excessDays')} error={fields.excessDays} />
        <Field label="Avisar vencimientos con (días)" inputMode="numeric" value={p.expiryAlertDays} onChange={set('expiryAlertDays')} error={fields.expiryAlertDays} />
        <Field label="Entrega por defecto (días)" inputMode="numeric" value={p.defaultLeadDays} onChange={set('defaultLeadDays')} error={fields.defaultLeadDays} hint="Si el proveedor no tiene uno" />
        <Field label="Cada cuánto se pide (días)" inputMode="numeric" value={p.reviewDays} onChange={set('reviewDays')} error={fields.reviewDays} />
        <Field label="Diferencia inusual (%)" inputMode="decimal" value={p.unusualDiffPct} onChange={set('unusualDiffPct')} error={fields.unusualDiffPct} />
        <Field label="Diferencia inusual (valor)" inputMode="numeric" value={p.unusualDiffValue} onChange={set('unusualDiffValue')} error={fields.unusualDiffValue} />
        <Field label="Presupuesto de compras al mes" inputMode="numeric" value={p.purchaseBudget} onChange={set('purchaseBudget')} error={fields.purchaseBudget} hint="0 = sin presupuesto" />
        <Field label="Clase A hasta el % de ventas" inputMode="decimal" value={p.abcAPct} onChange={set('abcAPct')} error={fields.abcAPct} />
        <Field label="Clase B hasta el % de ventas" inputMode="decimal" value={p.abcBPct} onChange={set('abcBPct')} error={fields.abcBPct} />
      </fieldset>
      {editable && (
        <div className="mt-3 flex justify-end">
          <Button icon="save" onClick={save} loading={saving}>
            Guardar parámetros
          </Button>
        </div>
      )}
    </Card>
  )
}

/** Verificación de pagos digitales y reglas del fiado. */
function FinanceParamsCard({ editable, settings, onSaved }: { editable: boolean; settings: StoreSettings; onSaved: (s: StoreSettings) => void }) {
  const toast = useToast()
  const [verify, setVerify] = useState(settings.verifyDigitalPayments)
  const [credit, setCredit] = useState(settings.creditEnabled && settings.paymentMethods.includes('FIADO'))
  const [limit, setLimit] = useState(String(settings.defaultCreditLimit))
  const [days, setDays] = useState(String(settings.defaultCreditDays))
  const [fields, setFields] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState(false)

  async function save() {
    setSaving(true)
    setFields({})
    try {
      onSaved(
        await api<StoreSettings>('/settings/pos/finance', {
          method: 'PUT',
          body: { verifyDigitalPayments: verify, creditEnabled: credit, defaultCreditLimit: Number(limit.replace(/\D/g, '') || 0), defaultCreditDays: Number(days || 0) },
        }),
      )
      toast('Configuración de pagos y fiado guardada')
    } catch (e) {
      if (e instanceof ApiError) setFields(Object.keys(e.fields).length ? e.fields : { defaultCreditLimit: e.message })
    } finally {
      setSaving(false)
    }
  }

  return (
    <Card className="p-4">
      <h3 className="mb-1 font-semibold">Pagos digitales y fiado</h3>
      <p className="mb-2 text-sm text-muted">Un pantallazo del cliente no es prueba de pago: los pagos digitales quedan por verificar hasta confirmar que el dinero llegó.</p>
      <fieldset disabled={!editable} className="divide-y divide-line-soft">
        <Toggle checked={verify} onChange={setVerify} label="Verificar pagos digitales" hint="Nequi, Daviplata, Bre-B y transferencias quedan pendientes hasta confirmarlos o conciliarlos con el extracto." />
        <Toggle checked={credit} onChange={setCredit} label="Vender fiado" hint="Permite cobrar a crédito a clientes registrados, con cupo y plazo." />
      </fieldset>
      {credit && (
        <fieldset disabled={!editable} className="mt-2 grid gap-3 sm:grid-cols-2">
          <Field label="Cupo por cliente" inputMode="numeric" value={limit ? Number(limit.replace(/\D/g, '')).toLocaleString('es-CO') : ''} onChange={(e) => setLimit(e.target.value.replace(/\D/g, ''))} error={fields.defaultCreditLimit} hint="Se puede cambiar para cada cliente" />
          <Field label="Plazo para pagar (días)" inputMode="numeric" value={days} onChange={(e) => setDays(e.target.value.replace(/\D/g, ''))} error={fields.defaultCreditDays} />
        </fieldset>
      )}
      {editable && (
        <div className="mt-3 flex justify-end">
          <Button icon="save" onClick={save} loading={saving}>
            Guardar
          </Button>
        </div>
      )}
    </Card>
  )
}

/** Parámetros de analítica y rentabilidad. */
function AnalyticsParamsCard({ editable, settings, onSaved }: { editable: boolean; settings: StoreSettings; onSaved: (s: StoreSettings) => void }) {
  const toast = useToast()
  const [p, setP] = useState({
    decliningPct: String(settings.decliningPct),
    lowMarginPct: String(settings.lowMarginPct),
    highMarginPct: String(settings.highMarginPct),
    rankingSize: String(settings.rankingSize),
  })
  const [allocate, setAllocate] = useState(settings.allocateExpenses)
  const [fields, setFields] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState(false)
  const set = (k: keyof typeof p) => (e: React.ChangeEvent<HTMLInputElement>) => setP({ ...p, [k]: e.target.value })

  async function save() {
    setSaving(true)
    setFields({})
    try {
      const body = { ...Object.fromEntries(Object.entries(p).map(([k, v]) => [k, Number(String(v).replace(',', '.'))])), allocateExpenses: allocate }
      onSaved(await api<StoreSettings>('/settings/pos/analytics', { method: 'PUT', body }))
      toast('Parámetros de analítica guardados')
    } catch (e) {
      if (e instanceof ApiError) setFields(Object.keys(e.fields).length ? e.fields : { decliningPct: e.message })
    } finally {
      setSaving(false)
    }
  }

  return (
    <Card className="p-4">
      <h3 className="mb-1 font-semibold">Analítica y rentabilidad</h3>
      <p className="mb-3 text-sm text-muted">Definen cuándo un producto “va en caída” y cómo se clasifican los márgenes.</p>
      <fieldset disabled={!editable} className="grid gap-3 sm:grid-cols-4">
        <Field label="Caída de ventas desde (%)" inputMode="decimal" value={p.decliningPct} onChange={set('decliningPct')} error={fields.decliningPct} />
        <Field label="Margen bajo por debajo de (%)" inputMode="decimal" value={p.lowMarginPct} onChange={set('lowMarginPct')} error={fields.lowMarginPct} />
        <Field label="Margen alto desde (%)" inputMode="decimal" value={p.highMarginPct} onChange={set('highMarginPct')} error={fields.highMarginPct} />
        <Field label="Productos en rankings" inputMode="numeric" value={p.rankingSize} onChange={set('rankingSize')} error={fields.rankingSize} />
      </fieldset>
      <fieldset disabled={!editable}>
        <Toggle checked={allocate} onChange={setAllocate} label="Repartir gastos entre productos" hint="Muestra la utilidad de cada producto después de asignarle una parte de los gastos operativos." />
      </fieldset>
      {editable && (
        <div className="mt-3 flex justify-end">
          <Button icon="save" onClick={save} loading={saving}>
            Guardar parámetros
          </Button>
        </div>
      )}
    </Card>
  )
}
