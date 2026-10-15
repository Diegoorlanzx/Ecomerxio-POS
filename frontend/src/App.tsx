import type { ReactNode } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import AppShell, { MAIN_NAV } from './components/AppShell'
import { Spinner } from './components/ui'
import { useAuth } from './lib/auth'
import ComingSoon from './pages/ComingSoon'
import MorePage from './pages/MorePage'
import ActivityPage from './pages/admin/ActivityPage'
import ProfilePage from './pages/admin/ProfilePage'
import RolesPage from './pages/admin/RolesPage'
import StoreSettingsPage from './pages/admin/StoreSettingsPage'
import UsersPage from './pages/admin/UsersPage'
import LoginPage from './pages/auth/LoginPage'
import { ForgotPasswordPage, ResetPasswordPage } from './pages/auth/RecoverPages'
import RegisterPage from './pages/auth/RegisterPage'

function RequireAuth({ children }: { children: ReactNode }) {
  const { me, loading } = useAuth()
  if (loading) return <Spinner />
  if (!me) return <Navigate to="/login" replace />
  return <>{children}</>
}

function PublicOnly({ children }: { children: ReactNode }) {
  const { me, loading } = useAuth()
  if (loading) return <Spinner />
  if (me) return <Navigate to="/" replace />
  return <>{children}</>
}

function Guard({ when, children }: { when: (can: (p: string) => boolean) => boolean; children: ReactNode }) {
  const { can } = useAuth()
  return when(can) ? <>{children}</> : <Navigate to="/" replace />
}

/** Primera pestaña que el usuario puede ver según sus permisos. */
function Home() {
  const { can } = useAuth()
  const first = MAIN_NAV.find((n) => n.show(can))
  return <Navigate to={first?.to ?? '/mas'} replace />
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<PublicOnly><LoginPage /></PublicOnly>} />
      <Route path="/registro" element={<PublicOnly><RegisterPage /></PublicOnly>} />
      <Route path="/recuperar" element={<PublicOnly><ForgotPasswordPage /></PublicOnly>} />
      <Route path="/restablecer" element={<ResetPasswordPage />} />
      <Route element={<RequireAuth><AppShell /></RequireAuth>}>
        <Route index element={<Home />} />
        <Route
          path="/pos"
          element={
            <ComingSoon
              icon="point_of_sale"
              title="Punto de venta"
              phase={1}
              items={['Búsqueda por nombre, código de barras y categoría', 'Carrito, descuentos, promociones y presentaciones', 'Pagos mixtos y cálculo del cambio', 'Cuentas abiertas (hasta 20)', 'Comprobantes, anulaciones y devoluciones', 'Venta sin internet']}
            />
          }
        />
        <Route
          path="/dashboard"
          element={
            <ComingSoon
              icon="space_dashboard"
              title="Dashboard principal"
              phase={4}
              items={['Ventas del día, semana, quincena y mes', 'Utilidad bruta y neta estimada', 'Estado de caja y pagos por verificar', 'Más vendidos, baja rotación y agotados', 'Alertas y accesos rápidos']}
            />
          }
        />
        <Route
          path="/caja"
          element={
            <ComingSoon
              icon="payments"
              title="Caja y turnos"
              phase={1}
              items={['Apertura con efectivo inicial', 'Ingresos, retiros y gastos de caja', 'Arqueo y cierre por turno', 'Sobrantes y faltantes', 'Historial y reportes por cajero']}
            />
          }
        />
        <Route
          path="/inventario"
          element={
            <ComingSoon
              icon="inventory_2"
              title="Productos e inventario"
              phase={1}
              items={['Catálogo con fotos, códigos, presentaciones e impuestos', 'Existencias en tiempo real y movimientos', 'Ajustes con justificación', 'Cobertura, rotación y clasificación ABC (Fase 2)', 'Importar y exportar con Excel']}
            />
          }
        />
        <Route path="/mas" element={<MorePage />} />
        <Route path="/usuarios" element={<Guard when={(c) => c('USUARIOS_GESTIONAR')}><UsersPage /></Guard>} />
        <Route path="/roles" element={<Guard when={(c) => c('ROLES_GESTIONAR') || c('USUARIOS_GESTIONAR')}><RolesPage /></Guard>} />
        <Route path="/actividad" element={<Guard when={(c) => c('AUDITORIA_VER')}><ActivityPage /></Guard>} />
        <Route path="/configuracion" element={<StoreSettingsPage />} />
        <Route path="/perfil" element={<ProfilePage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
