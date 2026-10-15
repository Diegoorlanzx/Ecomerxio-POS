import { Link } from 'react-router-dom'
import { Chip, Icon, SectionLabel } from '../components/ui'
import { useAuth } from '../lib/auth'

interface Tile {
  label: string
  text: string
  icon: string
  to?: string
  phase?: number
  show: (can: (p: string) => boolean) => boolean
}

const SECTIONS: { title: string; tiles: Tile[] }[] = [
  {
    title: 'Administración',
    tiles: [
      { label: 'Usuarios', text: 'Empleados y accesos', icon: 'group', to: '/usuarios', show: (c) => c('USUARIOS_GESTIONAR') },
      { label: 'Roles y permisos', text: 'Qué puede hacer cada cargo', icon: 'admin_panel_settings', to: '/roles', show: (c) => c('ROLES_GESTIONAR') || c('USUARIOS_GESTIONAR') },
      { label: 'Actividad', text: 'Registro de accesos y cambios', icon: 'history', to: '/actividad', show: (c) => c('AUDITORIA_VER') },
      { label: 'Datos de la tienda', text: 'Logo, contacto, moneda y zona', icon: 'storefront', to: '/configuracion', show: () => true },
      { label: 'Ajustes de caja', text: 'Impuestos, comprobante, pagos y cajas', icon: 'tune', to: '/ajustes-pos', show: () => true },
      { label: 'Copias de seguridad', text: 'Respaldos, restauración y exportar datos', icon: 'backup', to: '/respaldos', show: (c) => c('CONFIG_EDITAR') },
      { label: 'Integraciones', text: 'Correo, webhook, WhatsApp y pagos', icon: 'hub', to: '/notificaciones?tab=config', show: (c) => c('CONFIG_EDITAR') },
      { label: 'Mi cuenta', text: 'Contraseña y sesiones', icon: 'manage_accounts', to: '/perfil', show: () => true },
    ],
  },
  {
    title: 'Operación',
    tiles: [
      { label: 'Historial de ventas', text: 'Comprobantes, anulaciones y devoluciones', icon: 'receipt_long', to: '/ventas', show: (c) => c('POS_VENDER') || c('POS_HISTORIAL') || c('REPORTES_VER') },
      { label: 'Promociones', text: '3x2, combos y descuentos', icon: 'local_offer', to: '/promociones', show: (c) => c('PRODUCTOS_PRECIOS') },
      { label: 'Movimientos', text: 'Entradas y salidas de inventario', icon: 'swap_vert', to: '/movimientos', show: (c) => c('INVENTARIO_VER') },
      { label: 'Proveedores', text: 'Contactos, precios y entregas', icon: 'local_shipping', to: '/proveedores', show: (c) => c('PROVEEDORES_VER') || c('COMPRAS_VER') },
      { label: 'Compras', text: 'Pedidos y reposición automática', icon: 'shopping_cart_checkout', to: '/compras', show: (c) => c('COMPRAS_VER') || c('COMPRAS_RECIBIR') },
      { label: 'Pagos digitales', text: 'Verificar y conciliar pagos', icon: 'qr_code_2', to: '/pagos', show: (c) => c('PAGOS_VERIFICAR') || c('PAGOS_CONCILIAR') },
      { label: 'Gastos y finanzas', text: 'Flujo de caja, utilidad y por pagar', icon: 'account_balance_wallet', to: '/finanzas', show: (c) => c('FINANZAS_VER') || c('FINANZAS_GASTOS') },
      { label: 'Fiados', text: 'Clientes que pagan después', icon: 'menu_book', to: '/fiados', show: (c) => c('FIADO_GESTIONAR') || c('FIADO_AUTORIZAR') },
    ],
  },
  {
    title: 'Análisis',
    tiles: [
      { label: 'Analítica de ventas', text: 'Informes por período', icon: 'monitoring', to: '/analitica', show: (c) => c('REPORTES_VER') },
      { label: 'Rentabilidad', text: 'Cuánto deja cada producto', icon: 'trending_up', to: '/rentabilidad', show: (c) => c('COSTOS_VER') },
      { label: 'Notificaciones', text: 'Avisos, umbrales y reportes', icon: 'notifications_active', to: '/notificaciones', show: () => true },
      { label: 'Asistente IA', text: 'Pregúntale a tus datos', icon: 'auto_awesome', to: '/asistente', show: (c) => c('ASISTENTE_IA') },
      { label: 'Recomendaciones', text: 'Precios y promociones para aprobar', icon: 'sell', to: '/recomendaciones', show: (c) => c('COSTOS_VER') || c('PRODUCTOS_PRECIOS') },
    ],
  },
]

export default function MorePage() {
  const { can } = useAuth()
  return (
    <div className="space-y-6">
      {SECTIONS.map((s) => {
        const tiles = s.tiles.filter((t) => t.show(can))
        if (!tiles.length) return null
        return (
          <section key={s.title}>
            <SectionLabel>{s.title}</SectionLabel>
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
              {tiles.map((t) => {
                const inner = (
                  <>
                    <div className={`mb-3 flex h-11 w-11 items-center justify-center rounded-xl ${t.to ? 'bg-primary-tint text-primary' : 'bg-line-soft text-muted'}`}>
                      <Icon name={t.icon} className="text-[24px]" />
                    </div>
                    <div className="text-sm font-semibold">{t.label}</div>
                    <div className="mt-0.5 text-xs text-muted">{t.text}</div>
                    {t.phase && (
                      <div className="mt-2">
                        <Chip icon="schedule">Fase {t.phase}</Chip>
                      </div>
                    )}
                  </>
                )
                return t.to ? (
                  <Link key={t.label} to={t.to} className="rounded-2xl border border-line bg-card p-4 shadow-tier1 transition hover:border-primary/40 active:scale-[0.98]">
                    {inner}
                  </Link>
                ) : (
                  <div key={t.label} aria-disabled className="rounded-2xl border border-dashed border-line bg-card/60 p-4">
                    {inner}
                  </div>
                )
              })}
            </div>
          </section>
        )
      })}
    </div>
  )
}
