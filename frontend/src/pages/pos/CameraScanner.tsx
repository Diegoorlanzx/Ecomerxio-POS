import { useEffect, useRef, useState } from 'react'
import { Banner, Modal } from '../../components/ui'

interface Detector {
  detect: (src: CanvasImageSource) => Promise<{ rawValue: string }[]>
}

/** Lee códigos de barras con la cámara del celular (BarcodeDetector: Chrome/Edge en Android y PC). */
export default function CameraScanner({ onCode, onClose }: { onCode: (code: string) => void; onClose: () => void }) {
  const video = useRef<HTMLVideoElement>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    let stream: MediaStream | null = null
    let stop = false
    let last = ''
    const W = window as unknown as { BarcodeDetector?: new (o: { formats: string[] }) => Detector }
    if (!W.BarcodeDetector) {
      setError('Este navegador no puede leer códigos con la cámara. Usa un lector USB o Bluetooth, o escribe el código.')
      return
    }
    const detector = new W.BarcodeDetector({ formats: ['ean_13', 'ean_8', 'upc_a', 'upc_e', 'code_128', 'code_39', 'qr_code'] })
    ;(async () => {
      try {
        stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } })
        if (!video.current) return
        video.current.srcObject = stream
        await video.current.play()
        const tick = async () => {
          if (stop || !video.current) return
          try {
            const codes = await detector.detect(video.current)
            const code = codes[0]?.rawValue
            if (code && code !== last) {
              last = code
              navigator.vibrate?.(60)
              onCode(code)
              setTimeout(() => (last = ''), 1500)
            }
          } catch {
            /* cuadro sin código */
          }
          requestAnimationFrame(tick)
        }
        tick()
      } catch {
        setError('No se pudo abrir la cámara. Revisa el permiso de cámara del navegador.')
      }
    })()
    return () => {
      stop = true
      stream?.getTracks().forEach((t) => t.stop())
    }
  }, [onCode])

  return (
    <Modal open title="Escanear con la cámara" onClose={onClose}>
      {error ? (
        <Banner tone="warning">{error}</Banner>
      ) : (
        <div className="relative overflow-hidden rounded-2xl bg-black">
          <video ref={video} playsInline muted className="aspect-[4/3] w-full object-cover" />
          <div className="pointer-events-none absolute inset-x-8 top-1/2 h-0.5 -translate-y-1/2 bg-danger shadow-[0_0_12px_rgba(239,68,68,0.9)]" />
          <p className="absolute inset-x-0 bottom-2 text-center text-xs text-white/80">Apunta al código de barras. Cada lectura agrega una unidad.</p>
        </div>
      )}
    </Modal>
  )
}
