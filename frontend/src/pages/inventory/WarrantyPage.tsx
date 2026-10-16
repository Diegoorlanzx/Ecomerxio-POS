import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Banner, Card, Chip, EmptyState, Icon } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { dateTime } from '../../lib/format'
import type { SerialDto } from '../../lib/posTypes'

const STATUS: Record<string, { label: string; tone: 'success' | 'primary' | 'danger' }> = {
  EN_STOCK: { label: 'En stock', tone: 'success' },
  VENDIDO: { label: 'Vendido', tone: 'primary' },
  DEFECTUOSO: { label: 'Defectuoso', tone: 'danger' },
}

/** Garantías: buscar un serial / IMEI (o el cliente) y ver a quién se vendió y hasta cuándo cubre la garantía. */
export default function WarrantyPage() {
  const [q, setQ] = useState('')
  const [items, setItems] = useState<SerialDto[] | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    if (q.trim().length < 3) {
      setItems(null)
      return
    }
    const t = setTimeout(() => {
      api<SerialDto[]>(`/serials?q=${encodeURIComponent(q.trim())}`)
        .then((r) => {
          setItems(r)
          setError('')
        })
        .catch((e) => setError(e instanceof ApiError ? e.message : 'No se pudo buscar'))
    }, 250)
    return () => clearTimeout(t)
  }, [q])

  return (
    <div className="mx-auto max-w-3xl space-y-4">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Garantías y seriales</h1>
        <p className="text-sm text-muted">Escanea o escribe el serial / IMEI, o el nombre del cliente.</p>
      </div>
      <div className="relative">
        <Icon name="qr_code_scanner" className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-[22px] text-muted" />
        <input
          autoFocus
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder="Serial, IMEI o cliente…"
          aria-label="Buscar serial o cliente"
          className="h-14 w-full rounded-xl border border-line bg-card pr-3 pl-11 font-mono text-lg shadow-tier1 outline-none focus:border-primary focus:ring-4 focus:ring-primary/15"
        />
      </div>
      {error && <Banner>{error}</Banner>}
      {items === null ? (
        <Card>
          <EmptyState icon="verified_user" title="Busca un serial" text="Verás el producto, la venta, el cliente y si sigue en garantía." />
        </Card>
      ) : items.length === 0 ? (
        <Card>
          <EmptyState icon="search_off" title="No se encontró" text="Revisa el número o busca por el nombre del cliente." />
        </Card>
      ) : (
        <div className="space-y-3">
          {items.map((s) => (
            <Card key={s.id} className="p-4">
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-mono text-lg font-bold">{s.serial}</span>
                <Chip tone={STATUS[s.status]!.tone}>{STATUS[s.status]!.label}</Chip>
                {s.status === 'VENDIDO' &&
                  (s.warrantyUntil ? (
                    <Chip tone={s.inWarranty ? 'success' : 'danger'} icon={s.inWarranty ? 'verified_user' : 'gpp_bad'}>
                      {s.inWarranty ? `En garantía hasta ${s.warrantyUntil}` : `Garantía vencida (${s.warrantyUntil})`}
                    </Chip>
                  ) : (
                    <Chip>Sin garantía</Chip>
                  ))}
              </div>
              <div className="mt-1 font-semibold">{s.productName}</div>
              <dl className="mt-2 grid gap-x-4 gap-y-1 text-sm sm:grid-cols-2">
                {s.status === 'VENDIDO' && (
                  <>
                    <div>
                      <dt className="inline text-muted">Venta: </dt>
                      <dd className="inline">
                        {s.saleNumber ? (
                          <Link to={`/ventas?numero=${s.saleNumber}`} className="font-semibold text-primary hover:underline">
                            #{s.saleNumber}
                          </Link>
                        ) : (
                          '—'
                        )}{' '}
                        {s.soldAt ? `· ${dateTime(s.soldAt)}` : ''}
                      </dd>
                    </div>
                    <div>
                      <dt className="inline text-muted">Cliente: </dt>
                      <dd className="inline font-semibold">{s.customerName ?? 'Sin registrar'}</dd>
                    </div>
                  </>
                )}
                {s.notes && (
                  <div className="sm:col-span-2">
                    <dt className="inline text-muted">Nota: </dt>
                    <dd className="inline">{s.notes}</dd>
                  </div>
                )}
                <div>
                  <dt className="inline text-muted">Entró: </dt>
                  <dd className="inline">{dateTime(s.createdAt)}</dd>
                </div>
              </dl>
            </Card>
          ))}
        </div>
      )}
    </div>
  )
}
