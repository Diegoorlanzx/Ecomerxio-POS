import { useNavigate } from "react-router-dom";
import { Button, Card, Chip, Icon, Toggle } from "../../components/ui";
import { useGuide } from "../../components/guide";
import { TOURS, TOUR_SECTIONS } from "../../lib/tours";
import { useAuth } from "../../lib/auth";

/** Centro de la guía interactiva: asistente, recorridos y ayudas contextuales. */
export default function GuidePage() {
  const guide = useGuide();
  const navigate = useNavigate();
  const { can, has } = useAuth();
  if (!guide) return null;
  const tours = TOURS.filter(
    (t) => t.show(can) && (!t.module || has(t.module)),
  );
  const { state, update, startTour, openWizard } = guide;

  return (
    <div className="space-y-5">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Guía interactiva</h1>
        <p className="text-sm text-muted">
          Aprende a usar cada pantalla con recorridos paso a paso sobre la
          aplicación real.
        </p>
      </div>

      <Card className="flex items-center gap-3 p-4">
        <span className="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl bg-primary-tint text-primary">
          <Icon name="rocket_launch" className="text-[26px]" />
        </span>
        <div className="min-w-0 flex-1">
          <h2 className="font-semibold">Asistente de configuración</h2>
          <p className="text-sm text-muted">
            Los pasos para dejar tu tienda lista para vender.
          </p>
        </div>
        <Button variant="secondary" onClick={openWizard} className="shrink-0">
          Abrir
        </Button>
      </Card>

      <Card className="space-y-3 p-4">
        <div className="flex items-start gap-3">
          <span className="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl bg-warning-soft text-warning-ink">
            <Icon name="sports_esports" className="text-[26px]" />
          </span>
          <div className="min-w-0 flex-1">
            <h2 className="font-semibold">Modo de práctica</h2>
            <p className="text-sm text-muted">
              Vende y cobra en la caja como si fuera de verdad, sin miedo a equivocarte: nada se guarda, no se descuenta inventario y el tiquete
              dice "PRÁCTICA". Ideal para entrenar a un empleado nuevo.
            </p>
          </div>
        </div>
        <Toggle checked={state.practice} onChange={(v) => update({ practice: v })} label="Activar modo de práctica" />
        {state.practice && (
          <div className="rounded-xl bg-canvas p-3 text-sm">
            <div className="mb-1 font-semibold">Ejercicios para practicar en la caja</div>
            <ol className="list-decimal space-y-0.5 pl-5 text-muted">
              <li>Vende 2 productos y cobra en efectivo con un billete de $50.000: mira el cambio.</li>
              <li>Busca un producto por su nombre y otro con el lector de códigos.</li>
              <li>Cobra una venta dividida: parte en efectivo y parte por Nequi.</li>
              <li>Cambia la cantidad de un producto y aplícale un descuento.</li>
              <li>Vende un producto por peso (0,75 kg) tocándolo en el carrito.</li>
            </ol>
            <Button size="sm" className="mt-3" icon="point_of_sale" onClick={() => navigate('/pos')}>
              Ir a la caja a practicar
            </Button>
          </div>
        )}
      </Card>

      {TOUR_SECTIONS.map((section) => {
        const list = tours.filter((t) => t.section === section);
        if (!list.length) return null;
        return (
          <section key={section}>
            <h2 className="mb-3 text-xs font-bold tracking-wider text-muted uppercase">
              Recorridos guiados · {section}
            </h2>
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              {list.map((tour) => {
                const done = state.completedTours.includes(tour.id);
                const paused =
                  state.activeTour?.id === tour.id ? state.activeTour : null;
                return (
                  <Card key={tour.id} className="flex flex-col gap-3 p-4">
                    <div className="flex items-start gap-3">
                      <span
                        className={`flex h-12 w-12 shrink-0 items-center justify-center rounded-xl ${done ? "bg-success-soft text-success-ink" : "bg-primary-tint text-primary"}`}
                      >
                        <Icon
                          name={done ? "check_circle" : tour.icon}
                          className="text-[24px]"
                        />
                      </span>
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="font-semibold">{tour.title}</span>
                          {done && <Chip tone="success">Completado</Chip>}
                          {paused && (
                            <Chip tone="primary">
                              Pausado en el paso {paused.step + 1}
                            </Chip>
                          )}
                        </div>
                        <p className="mt-0.5 text-xs text-muted">
                          {tour.description}
                        </p>
                        <p className="mt-1 text-xs text-muted">
                          {tour.steps.length} pasos
                        </p>
                      </div>
                    </div>
                    <div className="mt-auto flex gap-2">
                      {paused && (
                        <Button
                          size="sm"
                          className="flex-1"
                          icon="play_arrow"
                          onClick={() => startTour(tour.id, paused.step)}
                        >
                          Reanudar
                        </Button>
                      )}
                      <Button
                        size="sm"
                        variant={paused ? "secondary" : "primary"}
                        className="flex-1"
                        onClick={() => startTour(tour.id)}
                      >
                        {paused || done ? "Empezar de nuevo" : "Empezar"}
                      </Button>
                    </div>
                  </Card>
                );
              })}
            </div>
          </section>
        );
      })}

      <Card className="px-4">
        <Toggle
          checked={state.showContextualHelp}
          onChange={(v) => update({ showContextualHelp: v })}
          label="Mostrar ayudas (i) y (?)"
          hint="Explicaciones al pasar el mouse en cifras, módulos y campos difíciles (Costo, Stock mínimo…). Apágalas cuando ya conozcas la app."
        />
      </Card>
    </div>
  );
}
