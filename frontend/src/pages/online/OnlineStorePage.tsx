import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import QrCode from '../../components/QrCode'
import { Banner, Button, Card, Chip, Field, Icon, SectionLabel, Spinner, Tabs, TextArea, Toggle, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { money } from '../../lib/format'
import { storeUrl, type DaySchedule, type OnlineConfig, type PublishItem, type Zone } from '../../lib/onlineTypes'

type ZoneRow = Omit<Zone, 'fee' | 'minOrder'> & { fee: number | string; minOrder?: number | string }

const DAYS = ['', 'Lunes', 'Martes', 'Miércoles', 'Jueves', 'Viernes', 'Sábado', 'Domingo']

/** Tienda en línea: configuración, productos publicados y QR para imprimir. */
export default function OnlineStorePage() {
  const { has, can, me } = useAuth()
  const [tab, setTab] = useState<'config' | 'productos' | 'qr'>('config')
  const [cfg, setCfg] = useState<OnlineConfig | null>(null)
  const [error, setError] = useState('')
  useEffect(() => {
    api<OnlineConfig>('/online-store').then(setCfg).catch((e) => setError(e.message))
  }, [])

  if (error && !cfg) return <Banner>{error}</Banner>
  if (!cfg) return <Spinner />
  const url = storeUrl(cfg.slug)

  return (
    <div className="mx-auto max-w-5xl space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        <div className="min-w-0 flex-1">
          <h1 className="text-2xl font-bold tracking-tight">Tienda en línea</h1>
          <p className="text-sm text-muted">Tu vitrina en internet: los clientes ven tus productos y piden desde el celular. El pedido llega a la caja.</p>
        </div>
        {cfg.enabled && (
          <a href={url} target="_blank" rel="noreferrer">
            <Button variant="secondary" icon="open_in_new">
              Ver mi tienda
            </Button>
          </a>
        )}
        <Link to="/pedidos">
          <Button icon="shopping_bag">Pedidos</Button>
        </Link>
      </div>
      {!has('TIENDA_ONLINE') && (
        <Banner tone="warning">
          El módulo “Tienda en línea” está apagado. Actívalo en{' '}
          <Link to="/negocio" className="font-semibold underline">
            Más → Tipo de negocio
          </Link>
          .
        </Banner>
      )}
      <div className="flex flex-wrap items-center gap-2 rounded-2xl bg-card px-4 py-3 shadow-tier1">
        <Chip tone={cfg.enabled ? 'success' : 'neutral'} icon={cfg.enabled ? 'public' : 'public_off'}>
          {cfg.enabled ? 'Publicada' : 'Sin publicar'}
        </Chip>
        {cfg.enabled && <Chip tone={cfg.openNow ? 'success' : 'warning'}>{cfg.statusText}</Chip>}
        <span className="text-sm text-muted">{cfg.publishedProducts} productos visibles</span>
        <span className="num ml-auto truncate font-mono text-sm text-primary">{url}</span>
        <Button
          size="sm"
          variant="ghost"
          icon="content_copy"
          onClick={() => navigator.clipboard?.writeText(url)}
          aria-label="Copiar el enlace"
        />
      </div>
      <Tabs
        value={tab}
        onChange={setTab}
        items={[
          { value: 'config', label: 'Configuración' },
          { value: 'productos', label: 'Productos' },
          { value: 'qr', label: 'Cartel con QR' },
        ]}
      />
      {tab === 'config' && <ConfigForm cfg={cfg} canEdit={can('CONFIG_EDITAR')} onSaved={setCfg} />}
      {tab === 'productos' && <ProductsTab currency={me?.tenant.currency} />}
      {tab === 'qr' && <QrPoster url={url} name={me?.tenant.name ?? ''} logo={me?.tenant.logo} mode={cfg.mode} whatsapp={cfg.whatsapp} />}
    </div>
  )
}

function ConfigForm({ cfg, canEdit, onSaved }: { cfg: OnlineConfig; canEdit: boolean; onSaved: (c: OnlineConfig) => void }) {
  const toast = useToast()
  const [f, setF] = useState({ ...cfg, minOrder: String(cfg.minOrder ?? 0), prepMinutes: String(cfg.prepMinutes) })
  const [schedule, setSchedule] = useState<DaySchedule[]>(
    cfg.schedule.length ? cfg.schedule : [1, 2, 3, 4, 5, 6, 7].map((d) => ({ day: d, open: '08:00', close: '20:00', closed: false })),
  )
  const [always, setAlways] = useState(cfg.schedule.length === 0)
  const [zones, setZones] = useState<ZoneRow[]>(cfg.zones)
  const [fields, setFields] = useState<Record<string, string>>({})
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const set = <K extends keyof typeof f>(k: K, v: (typeof f)[K]) => setF({ ...f, [k]: v })
  const togglePay = (m: string) => set('payMethods', f.payMethods.includes(m) ? f.payMethods.filter((x) => x !== m) : [...f.payMethods, m])

  async function save() {
    setSaving(true)
    setFields({})
    setError('')
    try {
      const saved = await api<OnlineConfig>('/online-store', {
        method: 'PUT',
        body: {
          enabled: f.enabled,
          slug: f.slug.trim().toLowerCase(),
          mode: f.mode,
          welcome: f.welcome,
          whatsapp: f.whatsapp,
          showStock: f.showStock,
          deliveryEnabled: f.deliveryEnabled,
          pickupEnabled: f.pickupEnabled,
          minOrder: Number(String(f.minOrder).replace(/\D/g, '') || 0),
          payMethods: f.payMethods,
          schedule: always ? [] : schedule,
          paused: f.paused,
          pausedMessage: f.pausedMessage,
          prepMinutes: Number(f.prepMinutes || 30),
          showBadge: f.showBadge,
          zones: zones
            .filter((z) => z.name.trim())
            .map((z) => ({ id: z.id, name: z.name, fee: Number(String(z.fee).replace(/\D/g, '') || 0), minOrder: Number(String(z.minOrder ?? 0).replace(/\D/g, '') || 0), active: true })),
        },
      })
      onSaved(saved)
      setZones(saved.zones)
      toast(saved.enabled ? 'Tienda en línea guardada y publicada' : 'Cambios guardados')
    } catch (e) {
      if (e instanceof ApiError) {
        setFields(e.fields)
        setError(Object.values(e.fields)[0] ?? e.message)
      }
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className={`space-y-5 ${canEdit ? '' : 'pointer-events-none opacity-70'}`}>
      {error && <Banner>{error}</Banner>}
      <Card className="divide-y divide-line-soft px-4">
        <Toggle checked={f.enabled} onChange={(v) => set('enabled', v)} label="Publicar la tienda en internet" hint="Apágala cuando quieras: el enlace muestra que no hay catálogo" />
        <Toggle checked={f.mode === 'PEDIDOS'} onChange={(v) => set('mode', v ? 'PEDIDOS' : 'CARTA')} label="Recibir pedidos" hint="Apagado = modo carta: solo se ven productos y precios" />
        <Toggle checked={f.paused} onChange={(v) => set('paused', v)} label="Pausar pedidos ahora" hint="Para cuando hay mucha gente o se acabó el domiciliario" />
        {f.paused && (
          <div className="pb-3">
            <Field label="Mensaje para los clientes" value={f.pausedMessage ?? ''} onChange={(e) => set('pausedMessage', e.target.value)} placeholder="Volvemos en 20 minutos" />
          </div>
        )}
      </Card>

      <section className="grid gap-3 sm:grid-cols-2">
        <Field label="Dirección de la tienda" value={f.slug} onChange={(e) => set('slug', e.target.value)} error={fields.slug} hint={`${window.location.host}/t/${f.slug}`} />
        <Field label="WhatsApp para pedidos" inputMode="tel" value={f.whatsapp ?? ''} onChange={(e) => set('whatsapp', e.target.value)} />
        <TextArea label="Mensaje de bienvenida" rows={2} className="sm:col-span-2" value={f.welcome ?? ''} onChange={(e) => set('welcome', e.target.value)} placeholder="Ej: Domicilios gratis en el barrio Prado por compras desde $30.000" />
      </section>

      {f.mode === 'PEDIDOS' && (
        <>
          <section>
            <SectionLabel>Entrega</SectionLabel>
            <Card className="divide-y divide-line-soft px-4">
              <Toggle checked={f.deliveryEnabled} onChange={(v) => set('deliveryEnabled', v)} label="Domicilio" />
              <Toggle checked={f.pickupEnabled} onChange={(v) => set('pickupEnabled', v)} label="Recoger en la tienda" />
            </Card>
            <div className="mt-3 grid gap-3 sm:grid-cols-3">
              <Field label="Pedido mínimo" inputMode="numeric" value={f.minOrder} onChange={(e) => set('minOrder', e.target.value)} />
              <Field label="Tiempo de preparación (min)" inputMode="numeric" value={f.prepMinutes} onChange={(e) => set('prepMinutes', e.target.value)} error={fields.prepMinutes} />
            </div>
            {f.deliveryEnabled && (
              <div className="mt-3 space-y-2">
                <div className="flex items-center justify-between">
                  <h3 className="text-sm font-semibold">Zonas y valor del domicilio</h3>
                  <Button size="sm" variant="soft" icon="add" onClick={() => setZones([...zones, { name: '', fee: 0, minOrder: 0, active: true }])}>
                    Zona
                  </Button>
                </div>
                {zones.length === 0 && <p className="text-xs text-muted">Sin zonas: el domicilio no tiene costo y el cliente solo escribe su dirección.</p>}
                {zones.map((z, i) => (
                  <div key={z.id ?? `n${i}`} className="grid grid-cols-[1fr_110px_110px_auto] items-end gap-2">
                    <Field label="Barrio o zona" value={z.name} onChange={(e) => setZones(zones.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)))} />
                    <Field label="Domicilio" inputMode="numeric" value={String(z.fee)} onChange={(e) => setZones(zones.map((x, j) => (j === i ? { ...x, fee: e.target.value } : x)))} />
                    <Field label="Mínimo" inputMode="numeric" value={String(z.minOrder ?? 0)} onChange={(e) => setZones(zones.map((x, j) => (j === i ? { ...x, minOrder: e.target.value } : x)))} />
                    <Button variant="ghost" icon="delete" aria-label={`Quitar ${z.name}`} onClick={() => setZones(zones.filter((_, j) => j !== i))} />
                  </div>
                ))}
              </div>
            )}
          </section>

          <section>
            <SectionLabel>Formas de pago</SectionLabel>
            <div className="flex flex-wrap gap-2">
              {Object.entries(cfg.payLabels).map(([code, label]) => (
                <button
                  key={code}
                  type="button"
                  aria-pressed={f.payMethods.includes(code)}
                  onClick={() => togglePay(code)}
                  className={`h-10 rounded-xl px-3.5 text-sm font-semibold ring-1 ${f.payMethods.includes(code) ? 'bg-primary text-white ring-primary' : 'bg-card ring-line'}`}
                >
                  {label}
                </button>
              ))}
            </div>
            {fields.payMethods && <p className="mt-1 text-xs text-danger-ink">{fields.payMethods}</p>}
            <p className="mt-2 text-xs text-muted">
              Nequi, Bre-B y transferencia muestran al cliente la cuenta y el QR que configuraste en{' '}
              <Link to="/pagos" className="underline">
                Pagos digitales
              </Link>
              ; el pago queda por verificar al cobrar el pedido.
            </p>
          </section>
        </>
      )}

      <section>
        <SectionLabel>Horario de atención</SectionLabel>
        <Card className="px-4">
          <Toggle checked={always} onChange={setAlways} label="Recibir pedidos a cualquier hora" />
        </Card>
        {!always && (
          <div className="mt-3 space-y-2">
            {schedule.map((d, i) => (
              <div key={d.day} className="flex flex-wrap items-center gap-2">
                <span className="w-24 text-sm font-semibold">{DAYS[d.day]}</span>
                <label className="flex items-center gap-1.5 text-xs">
                  <input type="checkbox" className="h-4 w-4 accent-primary" checked={!d.closed} onChange={(e) => setSchedule(schedule.map((x, j) => (j === i ? { ...x, closed: !e.target.checked } : x)))} />
                  Abre
                </label>
                <input type="time" aria-label={`${DAYS[d.day]} abre`} disabled={d.closed} value={d.open} onChange={(e) => setSchedule(schedule.map((x, j) => (j === i ? { ...x, open: e.target.value } : x)))} className="h-10 rounded-lg border border-line px-2 disabled:opacity-40" />
                <span className="text-xs text-muted">a</span>
                <input type="time" aria-label={`${DAYS[d.day]} cierra`} disabled={d.closed} value={d.close} onChange={(e) => setSchedule(schedule.map((x, j) => (j === i ? { ...x, close: e.target.value } : x)))} className="h-10 rounded-lg border border-line px-2 disabled:opacity-40" />
              </div>
            ))}
            {fields.schedule && <p className="text-xs text-danger-ink">{fields.schedule}</p>}
          </div>
        )}
      </section>

      <Card className="divide-y divide-line-soft px-4">
        <Toggle checked={f.showStock} onChange={(v) => set('showStock', v)} label="Mostrar cuántas unidades quedan" hint="Apagado: solo se ve “agotado” cuando no hay" />
        <Toggle checked={f.showBadge} onChange={(v) => set('showBadge', v)} label="Mostrar “Hecho con TiendaPOSS”" />
      </Card>

      {canEdit && (
        <div className="sticky bottom-20 flex justify-end lg:bottom-4">
          <Button size="lg" icon="save" onClick={save} loading={saving}>
            Guardar
          </Button>
        </div>
      )}
    </div>
  )
}

/** Qué productos se publican y a qué precio (vacío = el de mostrador). */
function ProductsTab({ currency }: { currency?: string }) {
  const { can } = useAuth()
  const toast = useToast()
  const [items, setItems] = useState<PublishItem[] | null>(null)
  const [q, setQ] = useState('')
  const [prices, setPrices] = useState<Record<number, string>>({})
  const [saving, setSaving] = useState(false)
  const load = () => api<PublishItem[]>('/online-store/products').then(setItems)
  useEffect(() => {
    load()
  }, [])
  const rows = useMemo(() => {
    const f = q.trim().toLowerCase()
    // Cada variante justo debajo de su producto principal.
    const byId = new Map((items ?? []).map((i) => [i.id, i]))
    const key = (i: PublishItem) => `${(i.parentId ? byId.get(i.parentId)?.name : i.name) ?? ''} ${i.parentId ? i.name : ''}`
    return (items ?? []).filter((i) => !f || i.name.toLowerCase().includes(f)).sort((a, b) => key(a).localeCompare(key(b)))
  }, [items, q])
  if (!items) return <Spinner />
  const canPrice = can('PRODUCTOS_PRECIOS')
  const canEdit = can('PRODUCTOS_EDITAR')

  async function send(body: unknown[], msg: string) {
    setSaving(true)
    try {
      await api('/online-store/products', { method: 'PUT', body })
      toast(msg)
      setPrices({})
      await load()
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'No se pudo guardar', 'danger')
    } finally {
      setSaving(false)
    }
  }

  const changedPrices = Object.entries(prices).map(([id, v]) => ({
    productId: Number(id),
    onlinePrice: v.trim() === '' ? undefined : Number(v.replace(/\D/g, '')),
    clearOnlinePrice: v.trim() === '',
  }))

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Buscar producto…" aria-label="Buscar producto" className="h-11 flex-1 rounded-xl border border-line bg-card px-3 outline-none focus:border-primary" />
        {canEdit && (
          <>
            <Button size="sm" variant="secondary" disabled={saving} onClick={() => send(rows.filter((r) => !r.parentId).map((r) => ({ productId: r.id, visible: true })), 'Productos publicados')}>
              Publicar todos
            </Button>
            <Button size="sm" variant="ghost" disabled={saving} onClick={() => send(rows.filter((r) => !r.parentId).map((r) => ({ productId: r.id, visible: false })), 'Productos ocultos')}>
              Ocultar todos
            </Button>
          </>
        )}
        {canPrice && changedPrices.length > 0 && (
          <Button size="sm" icon="save" loading={saving} onClick={() => send(changedPrices, 'Precios en línea guardados')}>
            Guardar {changedPrices.length} precios
          </Button>
        )}
      </div>
      <p className="text-xs text-muted">Los productos con serial (celulares, relojes) se venden en el mostrador y no aparecen en línea. Vacío en “Precio en línea” = mismo precio del mostrador.</p>
      <div className="overflow-x-auto rounded-xl border border-line bg-card">
        <table className="w-full min-w-[560px] text-sm">
          <thead className="bg-canvas text-left text-[11px] tracking-wide text-muted uppercase">
            <tr>
              <th className="px-3 py-2">Producto</th>
              <th className="px-2 py-2">Visible</th>
              <th className="px-2 py-2 text-right">Mostrador</th>
              <th className="px-2 py-2">Precio en línea</th>
              <th className="px-2 py-2 text-right">Disponible</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-line-soft">
            {rows.map((r) => (
              <tr key={r.id} className={r.visible && !r.trackSerials ? '' : 'opacity-55'}>
                <td className={`px-3 py-1.5 ${r.parentId ? 'pl-8 text-muted' : 'font-semibold'}`}>
                  {r.name}
                  {r.productType === 'COMBO' && <span className="ml-1 text-[10px] font-bold text-primary">COMBO</span>}
                  {r.trackSerials && <span className="ml-1 text-[10px] font-bold text-muted">SERIAL</span>}
                </td>
                <td className="px-2 py-1.5">
                  {!r.trackSerials && (
                    <input
                      type="checkbox"
                      className="h-4 w-4 accent-primary"
                      aria-label={`Visible ${r.name}`}
                      disabled={!canEdit || saving}
                      checked={r.visible}
                      onChange={(e) => send([{ productId: r.id, visible: e.target.checked }], e.target.checked ? `${r.name} visible en línea` : `${r.name} oculto en línea`)}
                    />
                  )}
                </td>
                <td className="num px-2 py-1.5 text-right">{r.productType === 'PADRE' ? '—' : money(r.price, currency)}</td>
                <td className="px-2 py-1.5">
                  {r.productType !== 'PADRE' && !r.trackSerials && (
                    <input
                      inputMode="numeric"
                      aria-label={`Precio en línea ${r.name}`}
                      disabled={!canPrice}
                      placeholder="igual"
                      value={prices[r.id] ?? (r.onlinePrice !== undefined && r.onlinePrice !== null ? String(r.onlinePrice) : '')}
                      onChange={(e) => setPrices({ ...prices, [r.id]: e.target.value })}
                      className="num h-9 w-28 rounded-lg border border-line px-2 outline-none focus:border-primary"
                    />
                  )}
                </td>
                <td className="num px-2 py-1.5 text-right">{r.productType === 'PADRE' || r.productType === 'COMBO' ? '—' : r.stock}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

/** Cartel para la vitrina, el mostrador o las mesas, con el QR de la tienda. */
function QrPoster({ url, name, logo, mode, whatsapp }: { url: string; name: string; logo?: string; mode: string; whatsapp?: string }) {
  const [copies, setCopies] = useState<'1' | '4'>('1')
  const big = copies === '1'
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2 print:hidden">
        <Tabs value={copies} onChange={setCopies} items={[{ value: '1', label: 'Cartel grande' }, { value: '4', label: '4 por hoja (mesas, volantes)' }]} />
        <Button icon="print" onClick={() => window.print()}>
          Imprimir
        </Button>
      </div>
      <p className="text-xs text-muted print:hidden">El QR también sale en el comprobante de cada venta cuando la tienda está publicada.</p>
      <div className={`grid gap-4 ${big ? '' : 'grid-cols-2'}`}>
        {Array.from({ length: big ? 1 : 4 }).map((_, i) => (
          <div key={i} className={`mx-auto flex w-full break-inside-avoid flex-col items-center rounded-3xl border-2 border-black bg-white text-center text-black ${big ? 'max-w-md p-8' : 'p-4'}`}>
            {logo && <img src={logo} alt="" className={big ? 'mb-3 h-16 object-contain' : 'mb-1 h-8 object-contain'} />}
            <div className={`font-extrabold ${big ? 'text-3xl' : 'text-base'}`}>{name}</div>
            <div className={`mt-1 font-semibold ${big ? 'text-lg' : 'text-xs'}`}>{mode === 'PEDIDOS' ? 'Pide desde tu celular' : 'Mira nuestra carta'}</div>
            <QrCode text={url} size={big ? 280 : 130} className="my-3" />
            <div className={`flex items-center gap-1 ${big ? 'text-base' : 'text-[10px]'}`}>
              <Icon name="photo_camera" className={big ? 'text-[20px]' : 'text-[12px]'} /> Escanea con la cámara
            </div>
            <div className={`mt-1 font-mono ${big ? 'text-sm' : 'text-[9px]'}`}>{url.replace(/^https?:\/\//, '')}</div>
            {whatsapp && big && <div className="mt-2 text-sm">WhatsApp {whatsapp}</div>}
          </div>
        ))}
      </div>
    </div>
  )
}
