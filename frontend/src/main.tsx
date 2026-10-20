import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { requestPersistentStorage } from './lib/offline/storage'
import { BrowserRouter } from 'react-router-dom'
import App from './App'
import { ToastProvider } from './components/ui'
import './index.css'
import { AuthProvider } from './lib/auth'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <AuthProvider>
        <ToastProvider>
          <App />
        </ToastProvider>
      </AuthProvider>
    </BrowserRouter>
  </StrictMode>,
)

// App instalable y apertura sin internet (solo en la versión publicada: en desarrollo estorba la recarga en caliente).
if (import.meta.env.PROD && 'serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(() => {})
  })
}

// Que el navegador no borre las ventas sin enviar aunque el disco se llene.
void requestPersistentStorage()
