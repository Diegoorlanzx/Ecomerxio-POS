import { NavLink } from 'react-router-dom'
import { Icon } from '../../components/ui'
import { useAuth } from '../../lib/auth'

/** Pestañas del módulo de inventario. */
export default function InventoryNav() {
  const { can } = useAuth()
  const items = [
    { to: '/inventario', label: 'Catálogo', icon: 'inventory_2', show: true, end: true },
    { to: '/inventario/inteligente', label: 'Inteligente', icon: 'insights', show: can('INVENTARIO_VER') },
    { to: '/inventario/conteos', label: 'Conteos', icon: 'fact_check', show: can('INVENTARIO_CONTEO') || can('INVENTARIO_VER') },
    { to: '/inventario/vencimientos', label: 'Vencimientos', icon: 'event_busy', show: can('INVENTARIO_VER') },
  ].filter((x) => x.show)
  return (
    <nav className="no-scrollbar -mx-4 flex gap-1 overflow-x-auto px-4 lg:mx-0 lg:px-0">
      {items.map((x) => (
        <NavLink
          key={x.to}
          to={x.to}
          end={x.end}
          className={({ isActive }) =>
            `flex h-10 shrink-0 items-center gap-1.5 rounded-xl px-3.5 text-sm font-semibold transition ${isActive ? 'bg-navy text-white' : 'bg-card text-muted shadow-tier1 hover:text-ink'}`
          }
        >
          <Icon name={x.icon} className="text-[18px]" />
          {x.label}
        </NavLink>
      ))}
    </nav>
  )
}
