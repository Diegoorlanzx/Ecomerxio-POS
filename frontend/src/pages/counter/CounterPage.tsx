import { useCallback, useEffect, useState } from 'react'
import { Banner, Button, Card, EmptyState, Icon, Spinner, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { relative } from '../../lib/format'

type Status = 'PREPARANDO' | 'LISTO' | 'ENTREGADO'

interface CounterOrder {
  saleId: number
  number: number
  status: Status
  customerName?: string
  items?: string
  createdAt: string
  readyAt?: string
}

/**
 * Mostrador (cafetería, heladería, comidas rápidas): pedidos del día por número de turno. La vista de clientes muestra en
 * grande los números que se preparan y los que están listos (para una pantalla o TV junto a la entrega).
 */
export default function CounterPage() {
  const toast = useToast()
  const [orders, setOrders] = useState<CounterOrder[] | null>(null)
  const [error, setError] = useState('')
  const [screen, setScreen] = useState(false)

  const load = useCallback(() => {
    api<CounterOrder[]>('/counter/orders')
      .then((o) => {
        setOrders(o)
        setError('')
      })
      .catch((e) => setError(e.message))
  }, [])
  useEffect(() => {
    load()
    const id = window.setInterval(load, 8000)
    return () => window.clearInterval(id)
  }, [load])

  async function move(o: CounterOrder, status: Status) {
    try {
      setOrders(await api<CounterOrder[]>(`/counter/orders/${o.saleId}/status`, { body: { status } }))
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'No se pudo actualizar', 'danger')
    }
  }

  if (error && !orders) return <Banner>{error}</Banner>
  if (!orders) return <Spinner />
  const preparing = orders.filter((o) => o.status === 'PREPARANDO')
  const ready = orders.filter((o) => o.status === 'LISTO')

  if (screen) {
    return (
      <div className="fixed inset-0 z-50 grid grid-cols-2 bg-navy text-white">
        <button type="button" onClick={() => setScreen(false)} className="absolute right-3 top-3 rounded-lg bg-white/10 px-3 py-2 text-sm">
          Salir
        </button>
        <section className="border-r border-white/10 p-6">
          <h2 className="mb-6 text-2xl font-bold tracking-wide text-white/70">PREPARANDO</h2>
          <div className="flex flex-wrap gap-4">
            {preparing.map((o) => (
              <span key={o.saleId} className="text-6xl font-black text-white/80">{o.number}</span>
            ))}
          </div>
        </section>
        <section className="p-6">
          <h2 className="mb-6 text-2xl font-bold tracking-wide text-success">¡LISTOS!</h2>
          <div className="flex flex-wrap gap-6">
            {ready.map((o) => (
              <span key={o.saleId} className="animate-pulse text-8xl font-black text-success">{o.number}</span>
            ))}
          </div>
        </section>
      </div>
    )
  }

  const column = (title: string, list: CounterOrder[], next: Status, label: string, icon: string) => (
    <section className="space-y-2">
      <h2 className="text-xs font-semibold tracking-[0.06em] text-muted uppercase">
        {title} ({list.length})
      </h2>
      {list.length === 0 && <p className="text-sm text-muted">Nada por aquí.</p>}
      {list.map((o) => (
        <Card key={o.saleId} className="flex items-center gap-3 p-3">
          <span className="w-14 shrink-0 text-center text-3xl font-black">{o.number}</span>
          <div className="min-w-0 flex-1 text-sm">
            <div className="truncate font-semibold">{o.items}</div>
            <div className="text-muted">
              {o.customerName ? `${o.customerName} · ` : ''}
              {relative(o.createdAt)}
            </div>
          </div>
          <Button size="sm" icon={icon} onClick={() => move(o, next)}>
            {label}
          </Button>
        </Card>
      ))}
    </section>
  )

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Pedidos de mostrador</h1>
          <p className="text-sm text-muted">Cada venta recibe su turno. Márcalo listo cuando esté para entregar; el cliente lo ve en la pantalla.</p>
        </div>
        <Button variant="secondary" icon="tv" onClick={() => setScreen(true)}>
          Pantalla para clientes
        </Button>
      </div>
      {error && <Banner tone="warning">{error}</Banner>}
      {orders.length === 0 ? (
        <EmptyState icon="confirmation_number" title="Sin pedidos hoy" text="Al cobrar una venta aparece aquí con su número de turno." />
      ) : (
        <div className="grid gap-4 md:grid-cols-2">
          {column('Preparando', preparing, 'LISTO', 'Listo', 'check')}
          {column('Listos para entregar', ready, 'ENTREGADO', 'Entregado', 'done_all')}
        </div>
      )}
      <p className="flex items-center gap-1 text-xs text-muted">
        <Icon name="sync" className="text-[16px]" /> Se actualiza solo cada pocos segundos.
      </p>
    </div>
  )
}
