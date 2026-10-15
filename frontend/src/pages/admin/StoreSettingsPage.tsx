import { useEffect, useRef, useState } from 'react'
import { StoreLogo } from '../../components/AppShell'
import { Banner, Button, Card, Field, Spinner, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import type { StoreDto } from '../../lib/types'

const TIMEZONES = ['America/Bogota', 'America/Mexico_City', 'America/Lima', 'America/Guayaquil', 'America/Caracas', 'America/Santiago', 'America/Argentina/Buenos_Aires', 'America/Panama', 'America/New_York', 'Europe/Madrid']
const CURRENCIES = [
  ['COP', 'Peso colombiano (COP)'],
  ['USD', 'Dólar (USD)'],
  ['MXN', 'Peso mexicano (MXN)'],
  ['PEN', 'Sol peruano (PEN)'],
  ['CLP', 'Peso chileno (CLP)'],
  ['ARS', 'Peso argentino (ARS)'],
  ['EUR', 'Euro (EUR)'],
]

export default function StoreSettingsPage() {
  const { can, refresh } = useAuth()
  const toast = useToast()
  const [store, setStore] = useState<StoreDto | null>(null)
  const [fields, setFields] = useState<Record<string, string>>({})
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const fileRef = useRef<HTMLInputElement>(null)
  const editable = can('CONFIG_EDITAR')

  useEffect(() => {
    api<StoreDto>('/store').then(setStore).catch((e) => setError(e.message))
  }, [])

  if (!store) return error ? <Banner>{error}</Banner> : <Spinner />

  const set = (k: keyof StoreDto) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => setStore({ ...store, [k]: e.target.value })

  function pickLogo(file: File) {
    if (file.size > 300 * 1024) {
      setFields({ ...fields, logo: 'la imagen es muy pesada (máx. 300 KB)' })
      return
    }
    const reader = new FileReader()
    reader.onload = () => setStore((s) => (s ? { ...s, logo: String(reader.result) } : s))
    reader.readAsDataURL(file)
  }

  async function save() {
    if (!store) return
    setSaving(true)
    setFields({})
    setError('')
    try {
      setStore(await api<StoreDto>('/store', { method: 'PUT', body: store }))
      await refresh()
      toast('Datos de la tienda guardados')
    } catch (e) {
      if (e instanceof ApiError) {
        setFields(e.fields)
        if (!Object.keys(e.fields).length) setError(e.message)
      }
    } finally {
      setSaving(false)
    }
  }

  const select = 'h-12 w-full rounded-xl border border-line bg-card px-3 text-[15px] outline-none focus:border-primary focus:ring-4 focus:ring-primary/15 disabled:bg-canvas'

  return (
    <div className="space-y-4">
      <div>
        <h2 className="text-[22px] font-semibold tracking-tight">Datos de la tienda</h2>
        <p className="text-sm text-muted">Aparecen en la app y, más adelante, en los comprobantes de venta.</p>
      </div>
      {!editable && <Banner tone="warning">Solo puedes ver estos datos. Para cambiarlos necesitas el permiso de configuración.</Banner>}
      {error && <Banner>{error}</Banner>}
      <fieldset disabled={!editable} className="space-y-4">
        <Card className="p-4">
          <h3 className="mb-3 font-semibold">Logo y nombre comercial</h3>
          <div className="flex flex-wrap items-center gap-4">
            <StoreLogo logo={store.logo} name={store.name} size={72} />
            <div className="flex gap-2">
              <Button variant="secondary" icon="upload" onClick={() => fileRef.current?.click()}>
                Subir logo
              </Button>
              {store.logo && (
                <Button variant="ghost" onClick={() => setStore({ ...store, logo: undefined })}>
                  Quitar
                </Button>
              )}
              <input ref={fileRef} type="file" accept="image/png,image/jpeg,image/webp,image/svg+xml" hidden onChange={(e) => e.target.files?.[0] && pickLogo(e.target.files[0])} />
            </div>
            {fields.logo && <p className="w-full text-xs font-medium text-danger-ink">{fields.logo}</p>}
          </div>
          <div className="mt-4 grid gap-3 sm:grid-cols-2">
            <Field label="Nombre comercial" value={store.name} onChange={set('name')} error={fields.name} />
            <Field label="Razón social" value={store.legalName ?? ''} onChange={set('legalName')} error={fields.legalName} />
            <Field label="NIT / documento" value={store.taxId ?? ''} onChange={set('taxId')} error={fields.taxId} />
            <Field label="Régimen tributario" placeholder="Ej: No responsable de IVA" value={store.taxRegime ?? ''} onChange={set('taxRegime')} error={fields.taxRegime} />
          </div>
        </Card>
        <Card className="p-4">
          <h3 className="mb-3 font-semibold">Dirección y contacto</h3>
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="Dirección" value={store.address ?? ''} onChange={set('address')} error={fields.address} />
            <Field label="Ciudad" value={store.city ?? ''} onChange={set('city')} error={fields.city} />
            <Field label="Teléfono" type="tel" value={store.phone ?? ''} onChange={set('phone')} error={fields.phone} />
            <Field label="WhatsApp" type="tel" value={store.whatsapp ?? ''} onChange={set('whatsapp')} error={fields.whatsapp} />
            <Field label="Correo" type="email" value={store.email ?? ''} onChange={set('email')} error={fields.email} className="sm:col-span-2" />
          </div>
        </Card>
        <Card className="p-4">
          <h3 className="mb-3 font-semibold">Moneda y zona horaria</h3>
          <div className="grid gap-3 sm:grid-cols-2">
            <div>
              <label className="mb-1.5 block text-xs font-semibold tracking-wide text-muted uppercase">Moneda</label>
              <select className={select} value={store.currency} onChange={set('currency')}>
                {CURRENCIES.map(([c, l]) => (
                  <option key={c} value={c}>
                    {l}
                  </option>
                ))}
              </select>
              {fields.currency && <p className="mt-1 text-xs font-medium text-danger-ink">{fields.currency}</p>}
            </div>
            <div>
              <label className="mb-1.5 block text-xs font-semibold tracking-wide text-muted uppercase">Zona horaria</label>
              <select className={select} value={store.timezone} onChange={set('timezone')}>
                {TIMEZONES.map((z) => (
                  <option key={z} value={z}>
                    {z.replace('America/', '').replace('Europe/', '').replace(/_/g, ' ')}
                  </option>
                ))}
              </select>
              {fields.timezone && <p className="mt-1 text-xs font-medium text-danger-ink">{fields.timezone}</p>}
            </div>
          </div>
        </Card>
      </fieldset>
      {editable && (
        <div className="sticky bottom-20 z-10 flex justify-end lg:bottom-4">
          <Button size="lg" icon="save" onClick={save} loading={saving} className="shadow-tier3">
            Guardar cambios
          </Button>
        </div>
      )}
    </div>
  )
}
