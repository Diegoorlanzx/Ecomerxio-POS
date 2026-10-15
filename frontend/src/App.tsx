import type { ReactNode } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import AppShell, { MAIN_NAV } from './components/AppShell'
import { Spinner } from './components/ui'
import { useAuth } from './lib/auth'
import ComingSoon from './pages/ComingSoon'
import MorePage from './pages/MorePage'
import CashPage from './pages/cash/CashPage'
import SalesPage from './pages/sales/SalesPage'
import PosSettingsPage from './pages/admin/PosSettingsPage'
import PosPage from './pages/pos/PosPage'
import InventoryPage from './pages/inventory/InventoryPage'
import MovementsPage from './pages/inventory/MovementsPage'
import PromotionsPage from './pages/inventory/PromotionsPage'
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
        <Route path="/pos" element={<Guard when={(c) => c('POS_VENDER')}><PosPage /></Guard>} />
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
        <Route path="/caja" element={<Guard when={(c) => c('CAJA_OPERAR') || c('CAJA_SUPERVISAR')}><CashPage /></Guard>} />
        <Route path="/ventas" element={<SalesPage />} />
        <Route path="/ajustes-pos" element={<PosSettingsPage />} />
        <Route path="/inventario" element={<Guard when={(c) => c('INVENTARIO_VER') || c('PRODUCTOS_VER')}><InventoryPage /></Guard>} />
        <Route path="/promociones" element={<Guard when={(c) => c('PRODUCTOS_PRECIOS')}><PromotionsPage /></Guard>} />
        <Route path="/movimientos" element={<Guard when={(c) => c('INVENTARIO_VER')}><MovementsPage /></Guard>} />
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
