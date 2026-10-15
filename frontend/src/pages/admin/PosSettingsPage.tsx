import { useEffect, useState } from 'react'
import { Banner, Button, Card, Chip, Field, Icon, SelectField, Spinner, TextArea, Toggle, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { METHOD_ICONS, METHOD_LABELS, type RegisterDto, type StoreSettings } from '../../lib/posTypes'

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
    setS({ ...s, paymentMethods: ALL_METHODS.filter((x) => next.includes(x)).join(',') })
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
          <p className="mt-2 text-xs text-muted">Nequi, Daviplata y Bre-B se registran con su referencia. La confirmación automática con el banco llega en la Fase 3.</p>
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
