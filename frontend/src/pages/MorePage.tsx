import { Link } from 'react-router-dom'
import { Chip, Icon, SectionLabel } from '../components/ui'
import { useAuth } from '../lib/auth'
import { useInstall } from '../lib/pwa'
import { APP_NAME } from '../lib/brand'
import { asset } from '../lib/host'

interface Tile {
  label: string
  text: string
  icon: string
  to?: string
  phase?: number
  /** Solo si la tienda usa este módulo. */
  module?: string
  /** Solo si el asistente con IA está encendido en la plataforma. */
  needsAssistant?: boolean
  show: (can: (p: string) => boolean) => boolean
}

const SECTIONS: { title: string; tiles: Tile[] }[] = [
  {
    title: 'Administración',
    tiles: [
      { label: 'Usuarios', text: 'Empleados y accesos', icon: 'group', to: '/usuarios', show: (c) => c('USUARIOS_GESTIONAR') },
      { label: 'Roles y permisos', text: 'Qué puede hacer cada cargo', icon: 'admin_panel_settings', to: '/roles', show: (c) => c('ROLES_GESTIONAR') || c('USUARIOS_GESTIONAR') },
      { label: 'Actividad', text: 'Registro de accesos y cambios', icon: 'history', to: '/actividad', show: (c) => c('AUDITORIA_VER') },
      { label: 'Tipo de negocio', text: 'Módulos que usa tu tienda', icon: 'category', to: '/negocio', show: () => true },
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
      { label: 'Pedidos en línea', text: 'Bandeja de pedidos de tu tienda en línea', icon: 'shopping_bag', to: '/pedidos', module: 'TIENDA_ONLINE', show: (c) => c('POS_VENDER') },
      { label: 'Tienda en línea', text: 'Vitrina, domicilios, horario y cartel QR', icon: 'storefront', to: '/tienda-online', module: 'TIENDA_ONLINE', show: (c) => c('CONFIG_EDITAR') || c('PRODUCTOS_EDITAR') },
      { label: 'Historial de ventas', text: 'Comprobantes, anulaciones y devoluciones', icon: 'receipt_long', to: '/ventas', show: (c) => c('POS_VENDER') || c('POS_HISTORIAL') || c('REPORTES_VER') },
      { label: 'Promociones', text: '3x2, combos y descuentos', icon: 'local_offer', to: '/promociones', show: (c) => c('PRODUCTOS_PRECIOS') },
      { label: 'Movimientos', text: 'Entradas y salidas de inventario', icon: 'swap_vert', to: '/movimientos', show: (c) => c('INVENTARIO_VER') },
      { label: 'Proveedores', text: 'Contactos, precios y entregas', icon: 'local_shipping', to: '/proveedores', module: 'COMPRAS', show: (c) => c('PROVEEDORES_VER') || c('COMPRAS_VER') },
      { label: 'Compras', text: 'Pedidos y reposición automática', icon: 'shopping_cart_checkout', to: '/compras', module: 'COMPRAS', show: (c) => c('COMPRAS_VER') || c('COMPRAS_RECIBIR') },
      { label: 'Garantías y seriales', text: 'Buscar un IMEI o serial vendido', icon: 'verified_user', to: '/garantias', module: 'SERIALES', show: (c) => c('POS_VENDER') || c('INVENTARIO_VER') || c('PRODUCTOS_VER') },
      { label: 'Etiquetas de báscula', text: 'Imprimir etiquetas de peso o precio', icon: 'scale', to: '/etiquetas-bascula', module: 'BASCULA', show: (c) => c('PRODUCTOS_VER') || c('INVENTARIO_VER') },
      { label: 'Pagos digitales', text: 'Verificar y conciliar pagos', icon: 'qr_code_2', to: '/pagos', show: (c) => c('PAGOS_VERIFICAR') || c('PAGOS_CONCILIAR') },
      { label: 'Facturación electrónica', text: 'Datos fiscales, resoluciones y clientes', icon: 'receipt_long', to: '/facturacion', show: (c) => c('FACTURACION_CONFIGURAR') },
      { label: 'Gastos y finanzas', text: 'Flujo de caja, utilidad y por pagar', icon: 'account_balance_wallet', to: '/finanzas', show: (c) => c('FINANZAS_VER') || c('FINANZAS_GASTOS') },
      { label: 'Fiados', text: 'Clientes que pagan después', icon: 'menu_book', to: '/fiados', module: 'FIADO', show: (c) => c('FIADO_GESTIONAR') || c('FIADO_AUTORIZAR') },
    ],
  },
  {
    title: 'Análisis',
    tiles: [
      { label: 'Analítica de ventas', text: 'Informes por período', icon: 'monitoring', to: '/analitica', show: (c) => c('REPORTES_VER') },
      { label: 'Rentabilidad', text: 'Cuánto deja cada producto', icon: 'trending_up', to: '/rentabilidad', show: (c) => c('COSTOS_VER') },
      { label: 'Notificaciones', text: 'Avisos, umbrales y reportes', icon: 'notifications_active', to: '/notificaciones', show: () => true },
      { label: 'Asistente IA', text: 'Pregúntale a tus datos', icon: 'auto_awesome', to: '/asistente', show: (c) => c('ASISTENTE_IA'), needsAssistant: true },
      { label: 'Recomendaciones', text: 'Precios y promociones para aprobar', icon: 'sell', to: '/recomendaciones', show: (c) => c('COSTOS_VER') || c('PRODUCTOS_PRECIOS') },
      { label: 'Guía interactiva', text: 'Tutoriales y asistente de configuración', icon: 'school', to: '/guia', show: () => true },
    ],
  },
]

export default function MorePage() {
  const { me, can, has } = useAuth()
  return (
    <div className="space-y-6">
      <PlanCard />
      <InstallCard />
      {SECTIONS.map((s) => {
        const tiles = s.tiles.filter((t) => t.show(can) && (!t.module || has(t.module)) && (!t.needsAssistant || !!me?.assistantEnabled))
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

/** Invita a instalar la app (Android/PC con un clic; en iPhone explica cómo). */
function InstallCard() {
  const { state, install } = useInstall()
  if (state === 'instalada' || state === 'no') return null
  return (
    <div className="flex flex-wrap items-center gap-3 rounded-2xl border border-blue-200 bg-primary-tint p-4">
      <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-primary text-white">
        <Icon name="install_mobile" className="text-[24px]" />
      </span>
      <div className="min-w-0 flex-1 text-sm">
        <div className="font-semibold">Instala {APP_NAME} en este equipo</div>
        <div className="text-muted">
          {state === 'ios'
            ? 'En Safari toca Compartir y luego “Agregar a inicio”. Se abre como una app, a pantalla completa y sin internet.'
            : 'Ábrela desde el escritorio o la pantalla de inicio como una app, a pantalla completa y también sin internet.'}
        </div>
      </div>
      {state === 'lista' && (
        <button type="button" onClick={() => install()} className="h-11 rounded-xl bg-primary px-4 text-sm font-semibold text-white">
          Instalar
        </button>
      )}
    </div>
  )
}

/** Plan de Ecomerxio POS de la tienda y, si está en prueba, cuántos días le quedan. */
function PlanCard() {
  const { me } = useAuth()
  const t = me?.tenant
  if (!t?.planName) return null
  const days = t.trialEndsAt ? Math.ceil((new Date(t.trialEndsAt).getTime() - Date.now()) / 86_400_000) : null
  return (
    <div className="flex flex-wrap items-center gap-3 rounded-2xl border border-line bg-card p-4 shadow-tier1">
      <img src={asset('ecomerxio-simbolo.svg')} alt="" className="h-11 w-11 shrink-0" />
      <div className="min-w-0 flex-1 text-sm">
        <div className="font-semibold">
          {APP_NAME} · Plan {t.planName}
        </div>
        <div className="text-muted">
          {days === null
            ? 'Suscripción activa.'
            : days > 0
              ? `Prueba gratis: te quedan ${days} ${days === 1 ? 'día' : 'días'}.`
              : 'Tu prueba gratis terminó.'}
        </div>
      </div>
    </div>
  )
}
