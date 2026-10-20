import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { Banner, Button, Card, Chip, EmptyState, Icon, Spinner, StatCard, useToast } from '../../components/ui'
import { api } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { money, pct, qty } from '../../lib/format'
import type { Category, Page, ProductDto, StoreSettings } from '../../lib/posTypes'
import { BulkModal, CategoriesModal, ImportModal } from './CatalogTools'
import ProductForm from './ProductForm'
import { AdjustModal, HistoryModal } from './StockDialogs'
import InventoryNav from './InventoryNav'
import { ComboModal, SerialsModal, VariantsModal } from './AdvancedProductDialogs'

type Filter = '' | 'BAJO' | 'AGOTADO' | 'EXCESO' | 'DISCONTINUED'

const STOCK_CHIP: Record<string, { tone: 'danger' | 'warning' | 'success' | 'primary'; label: (p: ProductDto) => string; bar: string }> = {
  AGOTADO: { tone: 'danger', label: () => 'Agotado', bar: 'bg-danger' },
  BAJO: { tone: 'warning', label: (p) => `Stock bajo (${qty(p.stock)} / mín ${qty(p.stockMin)})`, bar: 'bg-warning' },
  EXCESO: { tone: 'primary', label: (p) => `Exceso (${qty(p.stock)} / máx ${qty(p.stockMax)})`, bar: 'bg-primary' },
  NORMAL: { tone: 'success', label: (p) => `Normal (${qty(p.stock)}${p.stockMin ? ` / mín ${qty(p.stockMin)}` : ''})`, bar: 'bg-success' },
}

export default function InventoryPage() {
  const { can, has } = useAuth()
  const toast = useToast()
  const [q, setQ] = useState('')
  const [filter, setFilter] = useState<Filter>('')
  const [categoryId, setCategoryId] = useState<number | null>(null)
  const [page, setPage] = useState(0)
  const [data, setData] = useState<Page<ProductDto> | null>(null)
  const [counts, setCounts] = useState({ total: 0, low: 0, out: 0, excess: 0, discontinued: 0, value: 0 as number | null })
  const [categories, setCategories] = useState<Category[]>([])
  const [settings, setSettings] = useState<StoreSettings | null>(null)
  const [error, setError] = useState('')
  const [editing, setEditing] = useState<ProductDto | 'new' | null>(null)
  const [adjusting, setAdjusting] = useState<ProductDto | null>(null)
  const [history, setHistory] = useState<ProductDto | null>(null)
  const [advanced, setAdvanced] = useState<{ kind: 'variants' | 'combo' | 'serials'; p: ProductDto } | null>(null)
  const [tool, setTool] = useState<'import' | 'categories' | 'bulk' | null>(null)
  const [selected, setSelected] = useState<Set<number>>(new Set())
  const canEdit = can('PRODUCTOS_EDITAR')
  const canAdjust = can('INVENTARIO_AJUSTAR')
  const seeCosts = can('COSTOS_VER')

  const loadMeta = useCallback(async () => {
    const [cats, st] = await Promise.all([api<Category[]>('/categories'), api<StoreSettings>('/settings/pos')])
    setCategories(cats)
    setSettings(st)
    // Contadores de las tarjetas (consultas livianas por estado).
    const get = (extra: string) => api<Page<ProductDto>>(`/products?size=1&level=TOP${extra}`).then((r) => r.total)
    const [total, low, out, excess, disc] = await Promise.all([
      get('&status=ACTIVE'),
      get('&status=ACTIVE&stock=BAJO'),
      get('&status=ACTIVE&stock=AGOTADO'),
      get('&status=ACTIVE&stock=EXCESO'),
      get('&status=DISCONTINUED'),
    ])
    let value: number | null = null
    if (seeCosts) {
      const all = await api<Page<ProductDto>>('/products?size=200&status=ACTIVE&level=TOP')
      value = all.items.reduce((s, p) => s + (p.stockValue ?? 0), 0)
      if (all.pages > 1) value = null
    }
    setCounts({ total, low, out, excess, discontinued: disc, value })
  }, [seeCosts])

  const load = useCallback(async () => {
    const params = new URLSearchParams({ page: String(page), size: '40', level: 'TOP', sort: filter === 'BAJO' || filter === 'AGOTADO' ? 'stock' : 'name' })
    if (q.trim()) params.set('q', q.trim())
    if (categoryId) params.set('categoryId', String(categoryId))
    if (filter === 'DISCONTINUED') params.set('status', 'DISCONTINUED')
    else {
      params.set('status', 'ACTIVE')
      if (filter) params.set('stock', filter)
    }
    try {
      setData(await api<Page<ProductDto>>(`/products?${params}`))
    } catch (e) {
      setError((e as Error).message)
    }
  }, [q, filter, categoryId, page])

  useEffect(() => {
    loadMeta().catch((e) => setError(e.message))
  }, [loadMeta])

  useEffect(() => {
    const t = setTimeout(load, q ? 250 : 0)
    return () => clearTimeout(t)
  }, [load, q])

  const refresh = () => {
    load()
    loadMeta()
  }

  const parents = useMemo(() => categories.filter((c) => !c.parentId), [categories])

  function toggleSelect(id: number) {
    setSelected((s) => {
      const n = new Set(s)
      if (n.has(id)) n.delete(id)
      else n.add(id)
      return n
    })
  }

  async function toggleStatus(p: ProductDto) {
    await api(`/products/${p.id}/status`, { body: { status: p.status === 'ACTIVE' ? 'DISCONTINUED' : 'ACTIVE' } })
    toast(p.status === 'ACTIVE' ? `${p.name} descontinuado` : `${p.name} activo otra vez`)
    refresh()
  }

  const chips: { value: Filter; label: string; count: number; tone: string }[] = [
    { value: '', label: 'Todos', count: counts.total, tone: 'bg-primary text-white' },
    { value: 'BAJO', label: 'Stock bajo', count: counts.low, tone: 'bg-warning-soft text-warning-ink' },
    { value: 'AGOTADO', label: 'Agotados', count: counts.out, tone: 'bg-danger-soft text-danger-ink' },
    { value: 'EXCESO', label: 'Exceso', count: counts.excess, tone: 'bg-primary-soft text-primary-active' },
    { value: 'DISCONTINUED', label: 'Descontinuados', count: counts.discontinued, tone: 'bg-line-soft text-muted' },
  ]

  return (
    <div className="space-y-4">
      <InventoryNav />
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatCard label="SKUs activos" help="Cuántos productos diferentes tienes activos en el catálogo (cada uno es un SKU)." value={counts.total} icon="inventory_2" sub={`${categories.length} categorías`} />
        {seeCosts ? (
          <StatCard label="Valorizado a costo" help="Cuánto dinero tienes en mercancía: existencias × costo de cada producto. Es plata del negocio que está en los estantes." value={counts.value === null ? '—' : money(counts.value)} icon="account_balance_wallet" tone="navy" sub="Capital en mercancía" />
        ) : (
          <StatCard label="Con exceso" help="Productos con más existencias que el máximo que definiste: plata quieta que podrías usar en otra cosa." value={counts.excess} icon="stacks" tone="navy" />
        )}
        <button className="text-left" onClick={() => setFilter('AGOTADO')}>
          <StatCard label="Alerta urgente" help="Productos agotados más los que están por debajo del mínimo. Hay que pedirlos ya para no perder ventas." value={`${counts.out + counts.low}`} icon="emergency" tone="danger" sub={`${counts.out} agotados · ${counts.low} bajos`} />
        </button>
        <Link to="/inventario/inteligente" className="text-left">
          <StatCard label="Por reponer" help="Productos por debajo del mínimo pero que todavía tienen algo. En Inventario inteligente ves cuántos días te duran." value={counts.low} icon="hourglass_bottom" tone="wallet" sub="Ver cobertura y ABC" />
        </Link>
      </div>

      <div className="flex flex-wrap gap-2">
        {canEdit && (
          <Button icon="add_box" onClick={() => setEditing('new')}>
            Nuevo producto
          </Button>
        )}
        {can('PRODUCTOS_IMPORTAR') && (
          <Button variant="secondary" icon="upload_file" onClick={() => setTool('import')}>
            Importar / exportar
          </Button>
        )}
        {canEdit && (
          <Button variant="secondary" icon="category" onClick={() => setTool('categories')}>
            Categorías
          </Button>
        )}
        {can('PRODUCTOS_PRECIOS') && (
          <Link to="/promociones">
            <Button variant="secondary" icon="local_offer">
              Promociones
            </Button>
          </Link>
        )}
        {can('INVENTARIO_VER') && (
          <Link to="/movimientos">
            <Button variant="ghost" icon="swap_vert">
              Movimientos
            </Button>
          </Link>
        )}
      </div>

      <div className="flex items-center gap-2">
        <div className="relative flex-1">
          <Icon name="search" className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-[22px] text-muted" />
          <input
            value={q}
            onChange={(e) => (setPage(0), setQ(e.target.value))}
            placeholder="Buscar por nombre, código de barras, SKU o marca…"
            className="h-12 w-full rounded-xl border border-line bg-card pr-3 pl-10 text-base shadow-tier1 outline-none focus:border-primary focus:ring-4 focus:ring-primary/15"
          />
        </div>
        <select
          aria-label="Categoría"
          value={categoryId ?? ''}
          onChange={(e) => (setPage(0), setCategoryId(e.target.value ? Number(e.target.value) : null))}
          className="h-12 max-w-40 rounded-xl border border-line bg-card px-3 text-sm shadow-tier1 outline-none focus:border-primary"
        >
          <option value="">Categorías</option>
          {parents.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
        </select>
      </div>

      <div className="no-scrollbar -mx-4 flex gap-2 overflow-x-auto px-4">
        {chips.map((c) => (
          <button
            key={c.value}
            onClick={() => (setPage(0), setFilter(c.value))}
            className={`flex h-9 shrink-0 items-center gap-1.5 rounded-full px-3.5 text-xs font-semibold transition ${
              filter === c.value ? c.tone + ' shadow-tier1 ring-2 ring-primary/20' : 'bg-card text-muted shadow-tier1'
            }`}
          >
            {c.label}
            <span className="num opacity-80">({c.count})</span>
          </button>
        ))}
      </div>

      {selected.size > 0 && (
        <div className="sticky top-16 z-20 flex items-center gap-2 rounded-2xl bg-navy px-4 py-3 text-white shadow-tier3">
          <span className="flex-1 text-sm font-semibold">{selected.size} seleccionados</span>
          <Button size="sm" variant="soft" onClick={() => setTool('bulk')}>
            Cambio masivo
          </Button>
          <Button size="sm" variant="ghost" className="text-white hover:bg-white/10" onClick={() => setSelected(new Set())}>
            Limpiar
          </Button>
        </div>
      )}

      {error && <Banner>{error}</Banner>}
      <div className="flex items-center justify-between">
        <h2 className="text-xs font-semibold tracking-[0.06em] text-muted uppercase">Productos en catálogo</h2>
        {data && <span className="num text-xs text-muted">{data.total} resultados</span>}
      </div>

      {!data ? (
        <Spinner />
      ) : data.items.length === 0 ? (
        <Card>
          <EmptyState
            icon="inventory_2"
            title={q || filter ? 'No hay productos con ese filtro' : 'Tu catálogo está vacío'}
            text={q || filter ? undefined : 'Crea tu primer producto o importa tu lista desde Excel.'}
            action={
              canEdit && !q && !filter ? (
                <Button icon="add_box" onClick={() => setEditing('new')}>
                  Nuevo producto
                </Button>
              ) : undefined
            }
          />
        </Card>
      ) : (
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          {data.items.map((p) => {
            const chip = STOCK_CHIP[p.stockStatus]!
            const parent = p.productType === 'PADRE'
            const activeVariants = (p.variants ?? []).filter((v) => v.status === 'ACTIVE')
            const prices = activeVariants.map((v) => Number(v.price ?? 0))
            const base = parent && prices.length
              ? { ...(p.presentations.find((x) => x.isBase) ?? p.presentations[0]!), price: Math.min(...prices), barcode: undefined, marginPct: undefined }
              : p.presentations.find((x) => x.isBase) ?? p.presentations[0]
            const ref = Math.max(p.stockMax || p.stockMin * 3 || p.stock || 1, 1)
            const bar = p.trackStock ? Math.max(0, Math.min(100, (p.stock / ref) * 100)) : 100
            return (
              <Card key={p.id} className={`p-4 ${p.status === 'DISCONTINUED' ? 'opacity-60' : ''}`}>
                <div className="flex gap-3">
                  <div className="relative">
                    {canEdit && (
                      <input
                        type="checkbox"
                        aria-label={`Seleccionar ${p.name}`}
                        checked={selected.has(p.id)}
                        onChange={() => toggleSelect(p.id)}
                        className="absolute -top-1 -left-1 z-10 h-4 w-4 accent-primary"
                      />
                    )}
                    <div className="flex h-16 w-16 items-center justify-center overflow-hidden rounded-xl bg-primary-tint">
                      {p.photo ? <img src={p.photo} alt="" className="h-full w-full object-contain" /> : <Icon name="inventory_2" className="text-[28px] text-primary/50" />}
                    </div>
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="flex items-start gap-1">
                      <h3 className="line-clamp-2 flex-1 text-base leading-tight font-semibold">{p.name}</h3>
                      {p.favorite && <Icon name="star" fill className="text-[18px] text-warning" />}
                    </div>
                    <div className="mt-1 flex flex-wrap items-center gap-1.5 text-[11px] text-muted">
                      <span className="rounded bg-line-soft px-1.5 py-0.5 font-bold text-ink/70">{p.sku}</span>
                      {base?.barcode && <span className="num">EAN {base.barcode}</span>}
                      {p.categoryName && <span>· {p.categoryName}</span>}
                    </div>
                    <div className="mt-1.5 flex flex-wrap gap-1">
                      {parent ? (
                        <Chip tone={chip.tone} icon="style">
                          {activeVariants.length} variantes · {qty(p.stock)} uds
                        </Chip>
                      ) : p.productType === 'COMBO' ? (
                        <Chip tone="primary" icon="lunch_dining">
                          Combo de {p.components?.length ?? 0} {p.components?.length === 1 ? 'producto' : 'productos'}
                        </Chip>
                      ) : p.trackStock ? (
                        <Chip tone={chip.tone}>{chip.label(p)}</Chip>
                      ) : (
                        <Chip>Sin control de stock</Chip>
                      )}
                      {p.trackSerials && <Chip icon="qr_code_2">Serial{p.warrantyMonths ? ` · ${p.warrantyMonths} m garantía` : ''}</Chip>}
                      {p.plu && <Chip icon="scale">PLU {p.plu}</Chip>}
                      {Number(p.reserved ?? 0) > 0 && <Chip tone="wallet" icon="shopping_bag">{qty(Number(p.reserved))} apartadas en pedidos</Chip>}
                    </div>
                  </div>
                </div>
                {p.trackStock && (
                  <div className="mt-3 h-1.5 overflow-hidden rounded-full bg-line-soft">
                    <div className={`h-full rounded-full ${chip.bar}`} style={{ width: `${bar}%` }} />
                  </div>
                )}
                <div className="mt-3 flex items-center justify-between gap-2 rounded-xl bg-primary-tint/60 px-3 py-2.5">
                  <div>
                    <div className="text-[10px] font-bold tracking-wide text-muted uppercase">{parent ? 'Desde' : `PVP${seeCosts ? ' / costo' : ''}`}</div>
                    <div className="num flex items-baseline gap-1.5">
                      <span className="text-xl font-bold tracking-tight">{money(base?.price)}</span>
                      {seeCosts && !parent && p.cost !== undefined && <span className="text-xs text-muted line-through decoration-muted/40">{money(p.cost * (base?.factor ?? 1))}</span>}
                    </div>
                    {p.presentations.length > 1 && <div className="text-[11px] text-muted">+{p.presentations.filter((x) => !x.isBase && x.active !== false).length} presentaciones</div>}
                  </div>
                  {seeCosts && base?.marginPct !== undefined && base.marginPct !== null && (
                    <div className="text-right">
                      <div className="text-[10px] font-bold tracking-wide text-muted uppercase">Margen</div>
                      <div className={`num text-sm font-bold ${base.marginPct < 10 ? 'text-danger-ink' : 'text-success-ink'}`}>+{pct(base.marginPct)}</div>
                    </div>
                  )}
                </div>
                <div className="mt-3 flex flex-wrap gap-1.5">
                  {canEdit && (
                    <Button size="sm" variant="soft" icon="edit" onClick={() => setEditing(p)}>
                      Editar
                    </Button>
                  )}
                  {canEdit && has('VARIANTES') && ((p.productType === 'SIMPLE' && (!p.trackSerials || p.stock === 0)) || parent) && (
                    <Button size="sm" variant={parent ? 'soft' : 'ghost'} icon="style" onClick={() => setAdvanced({ kind: 'variants', p })}>
                      Variantes
                    </Button>
                  )}
                  {canEdit && has('COMBOS') && (p.productType === 'COMBO' || (p.productType === 'SIMPLE' && p.stock === 0 && !p.trackSerials)) && (
                    <Button size="sm" variant={p.productType === 'COMBO' ? 'soft' : 'ghost'} icon="lunch_dining" onClick={() => setAdvanced({ kind: 'combo', p })}>
                      {p.productType === 'COMBO' ? 'Componentes' : 'Volver combo'}
                    </Button>
                  )}
                  {p.trackSerials && (
                    <Button size="sm" variant="soft" icon="qr_code_2" onClick={() => setAdvanced({ kind: 'serials', p })}>
                      Seriales
                    </Button>
                  )}
                  {canAdjust && p.trackStock && !p.trackSerials && (
                    <Button size="sm" variant="ghost" icon="tune" onClick={() => setAdjusting(p)}>
                      Ajustar
                    </Button>
                  )}
                  <Button size="sm" variant="ghost" icon="history" onClick={() => setHistory(p)}>
                    Historial
                  </Button>
                  {canEdit && (
                    <Button size="sm" variant="ghost" icon={p.status === 'ACTIVE' ? 'block' : 'check_circle'} onClick={() => toggleStatus(p)}>
                      {p.status === 'ACTIVE' ? 'Descontinuar' : 'Activar'}
                    </Button>
                  )}
                </div>
              </Card>
            )
          })}
        </div>
      )}

      {data && data.pages > 1 && (
        <div className="flex items-center justify-center gap-3">
          <Button size="sm" variant="secondary" icon="chevron_left" disabled={page === 0} onClick={() => setPage((x) => x - 1)}>
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

      {editing && settings && (
        <ProductForm
          product={editing === 'new' ? null : editing}
          categories={categories}
          defaultTax={{ name: settings.defaultTaxName, rate: settings.defaultTaxRate }}
          onClose={() => setEditing(null)}
          onSaved={(p, created) => {
            setEditing(null)
            toast(created ? `${p.name} creado` : 'Cambios guardados')
            refresh()
          }}
        />
      )}
      {adjusting && (
        <AdjustModal
          product={adjusting}
          onClose={() => setAdjusting(null)}
          onDone={() => {
            setAdjusting(null)
            toast('Ajuste registrado')
            refresh()
          }}
        />
      )}
      {history && <HistoryModal product={history} onClose={() => setHistory(null)} />}
      {advanced?.kind === 'variants' && (
        <VariantsModal
          product={advanced.p}
          onClose={() => setAdvanced(null)}
          onSaved={(p) => {
            setAdvanced(null)
            toast(`Variantes de ${p.name} guardadas`)
            refresh()
          }}
        />
      )}
      {advanced?.kind === 'combo' && (
        <ComboModal
          product={advanced.p}
          onClose={() => setAdvanced(null)}
          onSaved={(p) => {
            setAdvanced(null)
            toast(p.productType === 'COMBO' ? `Combo ${p.name} guardado` : `${p.name} ya no es combo`)
            refresh()
          }}
        />
      )}
      {advanced?.kind === 'serials' && <SerialsModal product={advanced.p} onClose={() => setAdvanced(null)} onChanged={refresh} />}
      {tool === 'import' && <ImportModal onClose={() => setTool(null)} onDone={refresh} />}
      {tool === 'categories' && <CategoriesModal categories={categories} onClose={() => setTool(null)} onChanged={refresh} />}
      {tool === 'bulk' && (
        <BulkModal
          ids={[...selected]}
          categories={categories}
          onClose={() => setTool(null)}
          onDone={() => {
            setTool(null)
            setSelected(new Set())
            toast('Cambio masivo aplicado')
            refresh()
          }}
        />
      )}
    </div>
  )
}
