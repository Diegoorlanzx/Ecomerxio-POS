import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import ProductPicker from '../../components/ProductPicker'
import { Banner, Button, Card, EmptyState, Field, Modal, Spinner, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { money } from '../../lib/format'
import type { ProductDto } from '../../lib/posTypes'

interface Item {
  presentationId: number
  productName: string
  presentationName: string
  price: number
  quantity: number
  note?: string
}
interface SchoolList {
  id: number
  school: string
  grade: string
  year: number
  items: Item[]
  total: number
}

/** Papelería: listas de útiles por colegio y grado. "Vender esta lista" la carga completa a la caja. */
export default function SchoolListsPage() {
  const navigate = useNavigate()
  const toast = useToast()
  const { can, me } = useAuth()
  const [lists, setLists] = useState<SchoolList[] | null>(null)
  const [error, setError] = useState('')
  const [editing, setEditing] = useState<SchoolList | 'new' | null>(null)
  const [q, setQ] = useState('')

  const load = useCallback(() => {
    api<SchoolList[]>('/school-lists').then(setLists).catch((e) => setError(e.message))
  }, [])
  useEffect(load, [load])

  function sell(l: SchoolList) {
    sessionStorage.setItem('tp_cargar_lista', JSON.stringify(l.items.map((i) => ({ presentationId: i.presentationId, quantity: Number(i.quantity) }))))
    navigate('/pos')
  }

  async function remove(l: SchoolList) {
    try {
      await api(`/school-lists/${l.id}`, { method: 'DELETE' })
      toast('Lista eliminada')
      load()
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'No se pudo eliminar', 'danger')
    }
  }

  const shown = (lists ?? []).filter((l) => `${l.school} ${l.grade}`.toLowerCase().includes(q.trim().toLowerCase()))
  const currency = me?.tenant.currency ?? 'COP'

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Listas de útiles</h1>
          <p className="text-sm text-muted">Arma la lista de cada colegio y grado una vez; en temporada la vendes completa en un toque.</p>
        </div>
        {can('PRODUCTOS_EDITAR') && (
          <Button icon="add" onClick={() => setEditing('new')}>
            Nueva lista
          </Button>
        )}
      </div>
      <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Buscar colegio o grado" className="h-11 w-full rounded-xl border border-line bg-card px-3 text-sm" />
      {error && <Banner>{error}</Banner>}
      {!lists ? (
        <Spinner />
      ) : shown.length === 0 ? (
        <EmptyState icon="backpack" title="Sin listas" text="Crea la lista de útiles de un colegio y grado con los productos de tu inventario." />
      ) : (
        <div className="grid gap-3 md:grid-cols-2">
          {shown.map((l) => (
            <Card key={l.id} className="space-y-2 p-4 text-sm">
              <div className="flex justify-between gap-2">
                <span className="font-bold">
                  {l.school} · {l.grade}
                </span>
                <span className="text-muted">{l.year}</span>
              </div>
              <div className="text-muted">
                {l.items.map((i) => `${Number(i.quantity)} ${i.productName}`).join(', ')}
              </div>
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="text-base font-bold">{money(l.total, currency)}</span>
                <div className="flex gap-2">
                  {can('PRODUCTOS_EDITAR') && (
                    <>
                      <Button size="sm" variant="ghost" icon="edit" onClick={() => setEditing(l)}>
                        Editar
                      </Button>
                      <Button size="sm" variant="ghost" icon="delete" onClick={() => remove(l)}>
                        Quitar
                      </Button>
                    </>
                  )}
                  <Button size="sm" icon="point_of_sale" onClick={() => sell(l)}>
                    Vender esta lista
                  </Button>
                </div>
              </div>
            </Card>
          ))}
        </div>
      )}
      {editing && (
        <ListModal
          list={editing === 'new' ? null : editing}
          currency={currency}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null)
            toast('Lista guardada')
            load()
          }}
        />
      )}
    </div>
  )
}

function ListModal({ list, currency, onClose, onSaved }: { list: SchoolList | null; currency: string; onClose: () => void; onSaved: () => void }) {
  const [school, setSchool] = useState(list?.school ?? '')
  const [grade, setGrade] = useState(list?.grade ?? '')
  const [items, setItems] = useState<Item[]>(list?.items ?? [])
  const [fields, setFields] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)

  function add(p: ProductDto) {
    const pr = p.presentations.find((x) => x.isBase) ?? p.presentations[0]
    if (!pr || pr.id == null || items.some((i) => i.presentationId === pr.id)) return
    const id = pr.id
    setItems([...items, { presentationId: id, productName: p.name, presentationName: pr.name, price: Number(pr.price), quantity: 1 }])
  }

  async function save() {
    setBusy(true)
    setFields({})
    try {
      const body = { school, grade, items: items.map((i) => ({ presentationId: i.presentationId, quantity: i.quantity, note: i.note })) }
      if (list) await api(`/school-lists/${list.id}`, { method: 'PUT', body })
      else await api('/school-lists', { body })
      onSaved()
    } catch (e) {
      if (e instanceof ApiError) setFields(Object.keys(e.fields).length ? e.fields : { items: e.message })
    } finally {
      setBusy(false)
    }
  }

  const total = items.reduce((s, i) => s + i.price * i.quantity, 0)
  return (
    <Modal open wide title={list ? 'Editar lista' : 'Nueva lista de útiles'} onClose={onClose} footer={<Button className="w-full" loading={busy} onClick={save}>Guardar · {money(total, currency)}</Button>}>
      <div className="space-y-3">
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Colegio" value={school} onChange={(e) => setSchool(e.target.value)} error={fields.school} autoFocus />
          <Field label="Grado" placeholder="Tercero, 5°…" value={grade} onChange={(e) => setGrade(e.target.value)} error={fields.grade} />
        </div>
        <ProductPicker level="ITEMS" placeholder="Agregar útil del inventario…" onPick={add} exclude={[]} />
        {fields.items && <Banner>{fields.items}</Banner>}
        <div className="divide-y divide-line-soft">
          {items.map((i, idx) => (
            <div key={i.presentationId} className="flex items-center gap-2 py-2 text-sm">
              <span className="min-w-0 flex-1 truncate">{i.productName}</span>
              <input
                type="number"
                min={1}
                value={i.quantity}
                onChange={(e) => setItems(items.map((x, j) => (j === idx ? { ...x, quantity: Math.max(1, Number(e.target.value) || 1) } : x)))}
                className="h-9 w-16 rounded-lg border border-line px-2 text-center"
              />
              <span className="w-24 text-right tabular-nums">{money(i.price * i.quantity, currency)}</span>
              <button type="button" onClick={() => setItems(items.filter((_, j) => j !== idx))} className="text-muted">
                ✕
              </button>
            </div>
          ))}
        </div>
      </div>
    </Modal>
  )
}
