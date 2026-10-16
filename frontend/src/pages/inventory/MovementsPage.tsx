import { useCallback, useEffect, useState } from 'react'
import { Banner, Button, Card, Chip, EmptyState, Spinner } from '../../components/ui'
import { api } from '../../lib/api'
import { dateTime, qty } from '../../lib/format'
import type { MovementDto, Page } from '../../lib/posTypes'
import type { UserDto } from '../../lib/types'

const TYPES: Record<string, string> = {
  VENTA: 'Venta',
  ANULACION: 'Anulación',
  DEVOLUCION: 'Devolución',
  AJUSTE_ENTRADA: 'Ajuste +',
  AJUSTE_SALIDA: 'Ajuste −',
  DANADO: 'Dañado/vencido',
  INICIAL: 'Inicial',
  IMPORTACION: 'Importación',
  COMPRA: 'Compra',
  CONTEO: 'Conteo',
  DEVOLUCION_PROVEEDOR: 'Devolución a proveedor',
  ENTRADA_SERIAL: 'Entrada por serial',
  BAJA_SERIAL: 'Baja de serial',
}

/** Todas las entradas y salidas de inventario, filtrables por tipo y por empleado. */
export default function MovementsPage() {
  const [type, setType] = useState('')
  const [userId, setUserId] = useState('')
  const [users, setUsers] = useState<UserDto[]>([])
  const [page, setPage] = useState(0)
  const [data, setData] = useState<Page<MovementDto> | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    api<UserDto[]>('/users').then(setUsers).catch(() => {})
  }, [])

  const load = useCallback(() => {
    const p = new URLSearchParams({ page: String(page), size: '50' })
    if (type) p.set('type', type)
    if (userId) p.set('userId', userId)
    api<Page<MovementDto>>(`/inventory/movements?${p}`).then(setData).catch((e) => setError(e.message))
  }, [type, userId, page])
  useEffect(load, [load])

  const sel = 'h-11 rounded-xl border border-line bg-card px-3 text-sm outline-none focus:border-primary'
  return (
    <div className="space-y-4">
      <div>
        <h2 className="text-[22px] font-semibold tracking-tight">Movimientos de inventario</h2>
        <p className="text-sm text-muted">Cada entrada y salida queda con quién la hizo, cuándo y por qué.</p>
      </div>
      <div className="flex flex-wrap gap-2">
        <select aria-label="Tipo" className={sel} value={type} onChange={(e) => (setPage(0), setType(e.target.value))}>
          <option value="">Todos los tipos</option>
          {Object.entries(TYPES).map(([k, v]) => (
            <option key={k} value={k}>
              {v}
            </option>
          ))}
        </select>
        {users.length > 0 && (
          <select aria-label="Empleado" className={sel} value={userId} onChange={(e) => (setPage(0), setUserId(e.target.value))}>
            <option value="">Todos los empleados</option>
            {users.map((u) => (
              <option key={u.id} value={u.id}>
                {u.fullName}
              </option>
            ))}
          </select>
        )}
      </div>
      {error && <Banner>{error}</Banner>}
      {!data ? (
        <Spinner />
      ) : data.items.length === 0 ? (
        <Card>
          <EmptyState icon="swap_vert" title="Sin movimientos" />
        </Card>
      ) : (
        <Card className="divide-y divide-line-soft">
          {data.items.map((m) => (
            <div key={m.id} className="flex items-center gap-3 px-4 py-3">
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-1.5">
                  <span className="text-sm font-semibold">{m.productName}</span>
                  <Chip tone={m.quantity > 0 ? 'success' : 'danger'}>{TYPES[m.type] ?? m.type}</Chip>
                </div>
                <div className="truncate text-xs text-muted">
                  {m.reason} · {m.userName ?? 'Sistema'} · {dateTime(m.createdAt)}
                </div>
              </div>
              <div className="text-right">
                <div className={`num font-bold ${m.quantity > 0 ? 'text-success-ink' : 'text-danger-ink'}`}>
                  {m.quantity > 0 ? '+' : ''}
                  {qty(m.quantity)}
                </div>
                <div className="num text-xs text-muted">queda {qty(m.stockAfter)}</div>
              </div>
            </div>
          ))}
        </Card>
      )}
      {data && data.pages > 1 && (
        <div className="flex items-center justify-center gap-3">
          <Button size="sm" variant="secondary" disabled={page === 0} onClick={() => setPage((x) => x - 1)}>
            Anterior
          </Button>
          <span className="num text-sm text-muted">
            {page + 1} / {data.pages}
          </span>
          <Button size="sm" variant="secondary" disabled={page + 1 >= data.pages} onClick={() => setPage((x) => x + 1)}>
            Siguiente
          </Button>
        </div>
      )}
    </div>
  )
}
