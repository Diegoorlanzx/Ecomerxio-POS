import { execFile } from 'node:child_process'
import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

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

export default defineConfig({
  plugins: [react(), tailwindcss(), iconSubset()],
  server: {
    port: 5190,
    strictPort: true,
    proxy: { '/api': 'http://localhost:8090' },
  },
})
