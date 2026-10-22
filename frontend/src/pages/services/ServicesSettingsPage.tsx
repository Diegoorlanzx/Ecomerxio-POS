import { useCallback, useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import ProductPicker from "../../components/ProductPicker";
import {
  Banner,
  Button,
  Card,
  EmptyState,
  Field,
  Icon,
  InfoTip,
  Modal,
  Spinner,
  Tabs,
  Toggle,
  useToast,
} from "../../components/ui";
import { api, ApiError } from "../../lib/api";
import { money } from "../../lib/format";
import type { ProductDto } from "../../lib/posTypes";
import {
  WEEKDAYS,
  type AgendaDto,
  type HourDto,
  type ServiceDto,
  type StaffDto,
} from "../../lib/servicesTypes";

type Tab = "profesionales" | "servicios" | "en-linea";
const COLORS = [
  "#1e66f5",
  "#e64553",
  "#40a02b",
  "#df8e1d",
  "#8839ef",
  "#179299",
  "#ea76cb",
  "#5c5f77",
];

/** Configurar servicios: quién atiende (horario y comisiones), qué servicios hay (duración) y la agenda en línea. */
export default function ServicesSettingsPage() {
  const [params, setParams] = useSearchParams();
  const tab = (params.get("tab") as Tab) ?? "profesionales";
  const setTab = (t: Tab) =>
    setParams(t === "profesionales" ? {} : { tab: t }, { replace: true });
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">
          Configurar servicios
        </h1>
        <p className="text-sm text-muted">
          Profesionales, horarios, comisiones, servicios con su duración y las
          citas desde la tienda en línea.
        </p>
      </div>
      <div className="overflow-x-auto">
        <Tabs
          value={tab}
          onChange={setTab}
          items={[
            { value: "profesionales", label: "Profesionales" },
            { value: "servicios", label: "Servicios" },
            { value: "en-linea", label: "Citas en línea" },
          ]}
        />
      </div>
      {tab === "profesionales" && <StaffTab />}
      {tab === "servicios" && <ServicesTab />}
      {tab === "en-linea" && <BookingTab />}
    </div>
  );
}

function StaffTab() {
  const [list, setList] = useState<StaffDto[] | null>(null);
  const [services, setServices] = useState<ServiceDto[]>([]);
  const [editing, setEditing] = useState<Partial<StaffDto> | null>(null);
  const load = useCallback(
    () =>
      void api<StaffDto[]>("/services/staff")
        .then(setList)
        .catch(() => setList([])),
    [],
  );
  useEffect(() => {
    load();
    api<ServiceDto[]>("/services/catalog")
      .then(setServices)
      .catch(() => {});
  }, [load]);
  if (!list) return <Spinner />;
  return (
    <div className="space-y-3" data-guide="servicios-profesionales">
      <div className="flex justify-end">
        <Button
          icon="person_add"
          onClick={() =>
            setEditing({
              color: COLORS[list.length % COLORS.length],
              hours: [],
            })
          }
        >
          Agregar profesional
        </Button>
      </div>
      {list.length === 0 ? (
        <EmptyState
          icon="groups"
          title="Sin profesionales"
          text="Agrega a cada persona que atiende: su horario, qué servicios hace y su comisión."
        />
      ) : (
        <div className="grid gap-3 sm:grid-cols-2">
          {list.map((s) => (
            <Card key={s.id} className={`p-4 ${s.active ? "" : "opacity-60"}`}>
              <div className="flex items-start justify-between gap-2">
                <div className="flex items-center gap-2">
                  <span
                    className="h-4 w-4 rounded-full"
                    style={{ background: s.color }}
                  />
                  <span className="font-bold">{s.name}</span>
                  {!s.active && (
                    <span className="text-xs text-muted">(inactivo)</span>
                  )}
                </div>
                <Button
                  size="sm"
                  variant="secondary"
                  icon="edit"
                  onClick={() => setEditing(s)}
                >
                  Editar
                </Button>
              </div>
              <div className="mt-2 text-sm text-muted">
                Comisión: {s.commissionServicePct}% servicios ·{" "}
                {s.commissionProductPct}% productos
              </div>
              <div className="mt-1 text-xs text-muted">
                {s.hours.length === 0
                  ? "Horario: lunes a sábado de 8 a. m. a 6 p. m. (por defecto)"
                  : summarizeHours(s.hours)}
              </div>
              <div className="mt-1 text-xs text-muted">
                {s.serviceIds.length === 0
                  ? "Hace todos los servicios"
                  : `Hace ${s.serviceIds.length} servicio(s)`}
                {s.onlineBooking ? " · recibe citas en línea" : ""}
              </div>
            </Card>
          ))}
        </div>
      )}
      {editing && (
        <StaffEditor
          initial={editing}
          services={services}
          onClose={() => setEditing(null)}
          onSaved={() => (setEditing(null), load())}
        />
      )}
    </div>
  );
}

function summarizeHours(hs: HourDto[]) {
  const byDay = new Map<number, string[]>();
  hs.forEach((h) =>
    byDay.set(h.weekday, [
      ...(byDay.get(h.weekday) ?? []),
      `${h.start}-${h.end}`,
    ]),
  );
  return [...byDay.entries()]
    .map(([d, r]) => `${WEEKDAYS[d]!.slice(0, 3)} ${r.join(", ")}`)
    .join(" · ");
}

function StaffEditor({
  initial,
  services,
  onClose,
  onSaved,
}: {
  initial: Partial<StaffDto>;
  services: ServiceDto[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const toast = useToast();
  const [name, setName] = useState(initial.name ?? "");
  const [phone, setPhone] = useState(initial.phone ?? "");
  const [color, setColor] = useState(initial.color ?? COLORS[0]!);
  const [svcPct, setSvcPct] = useState(
    String(initial.commissionServicePct ?? 0),
  );
  const [prodPct, setProdPct] = useState(
    String(initial.commissionProductPct ?? 0),
  );
  const [serviceIds, setServiceIds] = useState<number[]>(
    initial.serviceIds ?? [],
  );
  const [online, setOnline] = useState(initial.onlineBooking ?? true);
  const [active, setActive] = useState(initial.active ?? true);
  // Horario por día: [inicio, fin] de una franja y opcionalmente una segunda (tarde).
  const [days, setDays] = useState<Record<number, HourDto[]>>(() => {
    const out: Record<number, HourDto[]> = {};
    const hs = initial.hours ?? [];
    if (hs.length === 0)
      for (let d = 1; d <= 6; d++)
        out[d] = [{ weekday: d, start: "08:00", end: "18:00" }];
    else hs.forEach((h) => (out[h.weekday] = [...(out[h.weekday] ?? []), h]));
    return out;
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  function setRange(d: number, i: number, k: "start" | "end", v: string) {
    setDays({
      ...days,
      [d]: (days[d] ?? []).map((h, j) => (j === i ? { ...h, [k]: v } : h)),
    });
  }

  async function save() {
    setBusy(true);
    setError("");
    try {
      const body = {
        name,
        phone,
        color,
        commissionServicePct: Number(svcPct) || 0,
        commissionProductPct: Number(prodPct) || 0,
        serviceIds,
        onlineBooking: online,
        active,
        hours: Object.values(days).flat(),
      };
      if (initial.id)
        await api(`/services/staff/${initial.id}`, { method: "PUT", body });
      else await api("/services/staff", { body });
      toast("Profesional guardado");
      onSaved();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "No se pudo guardar");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open
      wide
      title={initial.id ? `Editar a ${initial.name}` : "Nuevo profesional"}
      onClose={onClose}
      footer={
        <Button className="w-full" icon="save" loading={busy} onClick={save}>
          Guardar
        </Button>
      }
    >
      <div className="space-y-4">
        {error && <Banner>{error}</Banner>}
        <div className="grid gap-3 sm:grid-cols-2">
          <Field
            label="Nombre"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="Ana Gómez"
          />
          <Field
            label="Celular (opcional)"
            inputMode="tel"
            value={phone}
            onChange={(e) => setPhone(e.target.value)}
          />
          <Field
            label="% comisión en servicios"
            inputMode="decimal"
            value={svcPct}
            onChange={(e) => setSvcPct(e.target.value.replace(/[^\d.]/g, ""))}
            hint="Sobre lo cobrado sin IVA. Un servicio puede tener su propio %."
          />
          <Field
            label="% comisión en productos"
            inputMode="decimal"
            value={prodPct}
            onChange={(e) => setProdPct(e.target.value.replace(/[^\d.]/g, ""))}
            hint="Lo que venda en su cuenta"
          />
        </div>
        <div>
          <div className="mb-1.5 text-xs font-semibold text-muted uppercase">
            Color en la agenda
          </div>
          <div className="flex gap-2">
            {COLORS.map((c) => (
              <button
                key={c}
                type="button"
                aria-label={c}
                onClick={() => setColor(c)}
                className={`h-8 w-8 rounded-full border-2 ${c === color ? "border-ink" : "border-transparent"}`}
                style={{ background: c }}
              />
            ))}
          </div>
        </div>
        <div>
          <div className="mb-1.5 text-xs font-semibold text-muted uppercase">
            Horario
          </div>
          <div className="divide-y divide-line-soft rounded-xl border border-line">
            {[1, 2, 3, 4, 5, 6, 7].map((d) => {
              const rs = days[d] ?? [];
              return (
                <div
                  key={d}
                  className="flex flex-wrap items-center gap-2 px-3 py-2 text-sm"
                >
                  <label className="flex w-28 items-center gap-2 font-semibold">
                    <input
                      type="checkbox"
                      checked={rs.length > 0}
                      onChange={(e) =>
                        setDays({
                          ...days,
                          [d]: e.target.checked
                            ? [{ weekday: d, start: "08:00", end: "18:00" }]
                            : [],
                        })
                      }
                    />
                    {WEEKDAYS[d]}
                  </label>
                  {rs.length === 0 && (
                    <span className="text-muted">No trabaja</span>
                  )}
                  {rs.map((h, i) => (
                    <span key={i} className="flex items-center gap-1">
                      <input
                        type="time"
                        value={h.start}
                        onChange={(e) =>
                          setRange(d, i, "start", e.target.value)
                        }
                        className="h-9 rounded-lg border border-line px-1.5"
                      />
                      –
                      <input
                        type="time"
                        value={h.end}
                        onChange={(e) => setRange(d, i, "end", e.target.value)}
                        className="h-9 rounded-lg border border-line px-1.5"
                      />
                      {i > 0 && (
                        <button
                          type="button"
                          aria-label="Quitar franja"
                          onClick={() =>
                            setDays({
                              ...days,
                              [d]: rs.filter((_, j) => j !== i),
                            })
                          }
                        >
                          <Icon
                            name="close"
                            className="text-[18px] text-muted"
                          />
                        </button>
                      )}
                    </span>
                  ))}
                  {rs.length === 1 && (
                    <button
                      type="button"
                      className="text-xs font-semibold text-primary"
                      onClick={() =>
                        setDays({
                          ...days,
                          [d]: [
                            { ...rs[0]!, end: "12:00" },
                            {
                              weekday: d,
                              start: "14:00",
                              end: rs[0]!.end > "14:00" ? rs[0]!.end : "18:00",
                            },
                          ],
                        })
                      }
                    >
                      + partir (almuerzo)
                    </button>
                  )}
                </div>
              );
            })}
          </div>
        </div>
        {services.length > 0 && (
          <div>
            <div className="mb-1.5 text-xs font-semibold text-muted uppercase">
              Servicios que hace (ninguno marcado = todos)
            </div>
            <div className="flex flex-wrap gap-2">
              {services.map((s) => {
                const on = serviceIds.includes(s.productId);
                return (
                  <button
                    key={s.productId}
                    type="button"
                    onClick={() =>
                      setServiceIds(
                        on
                          ? serviceIds.filter((x) => x !== s.productId)
                          : [...serviceIds, s.productId],
                      )
                    }
                    className={`rounded-xl border px-3 py-1.5 text-sm font-semibold ${on ? "border-primary bg-primary text-white" : "border-line bg-card"}`}
                  >
                    {s.name}
                  </button>
                );
              })}
            </div>
          </div>
        )}
        <Toggle
          checked={online}
          onChange={setOnline}
          label="Recibe citas desde la tienda en línea"
        />
        <Toggle
          checked={active}
          onChange={setActive}
          label="Activo"
          hint="Si sale de la empresa, apágalo: su historial y comisiones quedan."
        />
      </div>
    </Modal>
  );
}

function ServicesTab() {
  const toast = useToast();
  const [list, setList] = useState<ServiceDto[] | null>(null);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<{
    productId: number;
    name: string;
    minutes: string;
    commission: string;
  } | null>(null);
  const [error, setError] = useState("");
  const load = useCallback(
    () =>
      void api<ServiceDto[]>("/services/catalog")
        .then(setList)
        .catch(() => setList([])),
    [],
  );
  useEffect(load, [load]);

  async function saveSettings(
    productId: number,
    minutes: number | null,
    commission: string,
  ) {
    setError("");
    try {
      await api(`/services/catalog/${productId}`, {
        method: "PUT",
        body: {
          minutes,
          commissionPct: commission === "" ? null : Number(commission),
        },
      });
      toast(minutes == null ? "Ya no es un servicio" : "Servicio guardado");
      setEditing(null);
      load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "No se pudo guardar");
    }
  }

  if (!list) return <Spinner />;
  return (
    <div className="space-y-3" data-guide="servicios-lista">
      {error && <Banner>{error}</Banner>}
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-muted">
          Un servicio no maneja inventario y tiene duración: así la agenda sabe
          cuánto tiempo ocupa.
        </p>
        <Button icon="add" onClick={() => setCreating(true)}>
          Nuevo servicio
        </Button>
      </div>
      <Card className="p-3">
        <div className="mb-1.5 text-xs font-semibold text-muted uppercase">
          Convertir un producto que ya existe en servicio
        </div>
        <ProductPicker
          level="ITEMS"
          placeholder="Buscar producto…"
          exclude={list.map((s) => s.productId)}
          onPick={(p: ProductDto) =>
            setEditing({
              productId: p.id,
              name: p.name,
              minutes: "30",
              commission: "",
            })
          }
        />
      </Card>
      {list.length === 0 ? (
        <EmptyState
          icon="content_cut"
          title="Sin servicios"
          text="Crea tus servicios (corte, tinte, manicure, revisión…) con su precio y duración."
        />
      ) : (
        <ul className="divide-y divide-line-soft rounded-2xl border border-line bg-card">
          {list.map((s) => (
            <li
              key={s.productId}
              className={`flex items-center gap-3 px-4 py-3 ${s.active ? "" : "opacity-60"}`}
            >
              <div className="min-w-0 flex-1">
                <div className="truncate font-semibold">{s.name}</div>
                <div className="text-xs text-muted">
                  {s.minutes} min · {money(s.price)}
                  {s.commissionPct != null &&
                    ` · comisión propia ${s.commissionPct}%`}
                </div>
              </div>
              <Button
                size="sm"
                variant="secondary"
                icon="edit"
                onClick={() =>
                  setEditing({
                    productId: s.productId,
                    name: s.name,
                    minutes: String(s.minutes),
                    commission:
                      s.commissionPct == null ? "" : String(s.commissionPct),
                  })
                }
              >
                Editar
              </Button>
            </li>
          ))}
        </ul>
      )}
      {editing && (
        <Modal
          open
          title={editing.name}
          onClose={() => setEditing(null)}
          footer={
            <>
              {list.some((s) => s.productId === editing.productId) && (
                <Button
                  variant="secondary"
                  onClick={() => saveSettings(editing.productId, null, "")}
                >
                  Ya no es servicio
                </Button>
              )}
              <Button
                className="flex-1"
                icon="save"
                onClick={() =>
                  saveSettings(
                    editing.productId,
                    Number(editing.minutes) || 30,
                    editing.commission,
                  )
                }
              >
                Guardar
              </Button>
            </>
          }
        >
          <div className="space-y-3">
            <Field
              label="Duración (minutos)"
              inputMode="numeric"
              value={editing.minutes}
              onChange={(e) =>
                setEditing({
                  ...editing,
                  minutes: e.target.value.replace(/\D/g, ""),
                })
              }
            />
            <Field
              label="% de comisión propio (opcional)"
              inputMode="decimal"
              value={editing.commission}
              onChange={(e) =>
                setEditing({
                  ...editing,
                  commission: e.target.value.replace(/[^\d.]/g, ""),
                })
              }
              hint="Vacío = el % de servicios de cada profesional"
            />
            <p className="text-xs text-muted">
              El precio se cambia en Inventario, como cualquier producto.
            </p>
          </div>
        </Modal>
      )}
      {creating && (
        <NewServiceModal
          onClose={() => setCreating(false)}
          onDone={() => (setCreating(false), load())}
        />
      )}
    </div>
  );
}

function NewServiceModal({
  onClose,
  onDone,
}: {
  onClose: () => void;
  onDone: () => void;
}) {
  const toast = useToast();
  const [name, setName] = useState("");
  const [price, setPrice] = useState("");
  const [minutes, setMinutes] = useState("30");
  const [commission, setCommission] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function create() {
    setBusy(true);
    setError("");
    try {
      const p = await api<ProductDto>("/products", {
        body: {
          name,
          unit: "UNIDAD",
          trackStock: false,
          cost: 0,
          initialStock: 0,
          presentations: [
            {
              name: "Servicio",
              factor: 1,
              price: Number(price) || 0,
              isBase: true,
            },
          ],
        },
      });
      await api(`/services/catalog/${p.id}`, {
        method: "PUT",
        body: {
          minutes: Number(minutes) || 30,
          commissionPct: commission === "" ? null : Number(commission),
        },
      });
      toast("Servicio creado");
      onDone();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "No se pudo crear");
    } finally {
      setBusy(false);
    }
  }
  return (
    <Modal
      open
      title="Nuevo servicio"
      onClose={onClose}
      footer={
        <Button className="w-full" icon="add" loading={busy} onClick={create}>
          Crear servicio
        </Button>
      }
    >
      <div className="space-y-3">
        {error && <Banner>{error}</Banner>}
        <Field
          label="Nombre"
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="Corte de cabello"
        />
        <div className="grid grid-cols-2 gap-3">
          <Field
            label="Precio"
            inputMode="numeric"
            value={price}
            onChange={(e) => setPrice(e.target.value.replace(/\D/g, ""))}
          />
          <Field
            label="Duración (min)"
            inputMode="numeric"
            value={minutes}
            onChange={(e) => setMinutes(e.target.value.replace(/\D/g, ""))}
          />
        </div>
        <Field
          label="% comisión propio (opcional)"
          inputMode="decimal"
          value={commission}
          onChange={(e) => setCommission(e.target.value.replace(/[^\d.]/g, ""))}
        />
      </div>
    </Modal>
  );
}

function BookingTab() {
  const toast = useToast();
  const [agenda, setAgenda] = useState<AgendaDto | null>(null);
  const [enabled, setEnabled] = useState(false);
  const [slot, setSlot] = useState("30");
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    api<AgendaDto>("/services/agenda")
      .then(
        (a) => (
          setAgenda(a),
          setEnabled(a.bookingEnabled),
          setSlot(String(a.slotMinutes))
        ),
      )
      .catch(() => {});
  }, []);
  if (!agenda) return <Spinner />;
  const url = agenda.bookingUrlSlug
    ? `${location.origin}/t/${agenda.bookingUrlSlug}/citas`
    : null;
  async function save() {
    setBusy(true);
    try {
      await api("/services/booking", {
        method: "PUT",
        body: { bookingEnabled: enabled, slotMinutes: Number(slot) || 30 },
      });
      toast("Guardado");
    } catch (e) {
      toast(e instanceof ApiError ? e.message : "No se pudo guardar");
    } finally {
      setBusy(false);
    }
  }
  return (
    <Card className="space-y-3 p-4" data-guide="servicios-en-linea">
      <Toggle
        checked={enabled}
        onChange={setEnabled}
        label="Los clientes pueden pedir cita desde la tienda en línea"
        hint="Eligen servicio, profesional (o cualquiera), día y una hora libre. La cita aparece en la agenda y te llega un aviso."
      />
      <Field
        label="Cada cuánto se ofrecen horas (minutos)"
        inputMode="numeric"
        value={slot}
        onChange={(e) => setSlot(e.target.value.replace(/\D/g, ""))}
        helpTip={
          <InfoTip text="Con 30 se ofrecen 9:00, 9:30, 10:00… Solo se muestran las horas en que el profesional está libre el tiempo completo del servicio." />
        }
      />
      {url ? (
        <p className="text-sm">
          Enlace para tus clientes:{" "}
          <a
            href={url}
            target="_blank"
            rel="noreferrer"
            className="font-semibold break-all text-primary"
          >
            {url}
          </a>
        </p>
      ) : (
        <Banner tone="warning">
          Para recibir citas en línea primero publica tu tienda en línea (Más →
          Tienda en línea).
        </Banner>
      )}
      <Button icon="save" loading={busy} onClick={save}>
        Guardar
      </Button>
    </Card>
  );
}
