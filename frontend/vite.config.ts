import { execFile } from 'node:child_process'
import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { federation } from '@module-federation/vite'

/** En desarrollo vuelve a recortar la fuente de íconos cuando cambia un .tsx (íconos nuevos). */
function iconSubset(): Plugin {
  let timer: ReturnType<typeof setTimeout> | undefined
  return {
    name: 'icon-subset',
    apply: 'serve',
    handleHotUpdate({ file }) {
      if (!/\.(tsx?|ts)$/.test(file) || file.includes('node_modules')) return
      clearTimeout(timer)
      timer = setTimeout(() => execFile('python', ['-I', 'scripts/subset-icons.py'], () => {}), 800)
    },
  }
}

/** Lista de todos los archivos de la compilación: el service worker los guarda para abrir cualquier pantalla sin internet. */
function precacheList(): Plugin {
  return {
    name: 'precache-list',
    apply: 'build',
    generateBundle(_options, bundle) {
      const files = Object.keys(bundle).filter((f) => f.startsWith('assets/')).map((f) => '/' + f)
      this.emitFile({ type: 'asset', fileName: 'precache.json', source: JSON.stringify({ builtAt: new Date().toISOString(), files }) })
    },
  }
}

export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    iconSubset(),
    precacheList(),
    // Microfrontend: ecomerxio.com/pos carga remoteEntry.js y llama a mount() (src/remote.tsx). Trae su propio
    // React dentro de un Shadow DOM, así que no comparte dependencias con la página anfitriona.
    federation({
      name: 'ecomerxio_pos',
      filename: 'remoteEntry.js',
      exposes: { './mount': './src/remote.tsx' },
      shared: {},
      dts: false,
    }),
  ],
  // El código y los estilos se piden relativos a donde se cargaron: así sirven igual en la raíz (app sola) que
  // detrás de ecomerxio.com/pos-app (incrustada). El HTML de la app sola sigue con rutas absolutas.
  experimental: {
    renderBuiltUrl(filename, { hostType }) {
      return hostType === 'html' ? '/' + filename : { relative: true }
    },
  },
  preview: {
    port: 5191,
    strictPort: true,
    proxy: { '/api': 'http://localhost:8090' },
  },
  server: {
    port: 5190,
    strictPort: true,
    proxy: { '/api': 'http://localhost:8090' },
  },
})
