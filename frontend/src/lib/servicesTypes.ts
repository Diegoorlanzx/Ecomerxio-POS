/** Fase 11 — Servicios: agenda, profesionales, comisiones y órdenes de servicio técnico. */

export interface ServiceDto {
  productId: number;
  presentationId: number;
  name: string;
  minutes: number;
  price: number;
  commissionPct: number | null;
  categoryId: number | null;
  active: boolean;
}

export interface HourDto {
  weekday: number;
  start: string;
  end: string;
}

export interface StaffDto {
  id: number;
  userId: number | null;
  name: string;
  phone: string | null;
  color: string;
  commissionServicePct: number;
  commissionProductPct: number;
  serviceIds: number[];
  onlineBooking: boolean;
  sortOrder: number;
  active: boolean;
  hours: HourDto[];
}

export type AppointmentStatus =
  "AGENDADA" | "CONFIRMADA" | "ATENDIDA" | "NO_ASISTIO" | "CANCELADA";

export interface AppointmentDto {
  id: number;
  staffId: number;
  staffName: string;
  staffColor: string;
  customerId: number | null;
  customerName: string;
  customerPhone: string | null;
  serviceId: number;
  serviceName: string;
  price: number;
  start: string;
  end: string;
  minutes: number;
  status: AppointmentStatus;
  source: "CAJA" | "ONLINE";
  notes: string | null;
  saleId: number | null;
  saleStatus: string | null;
  reminderSentAt: string | null;
  whatsappUrl: string | null;
  createdBy: string | null;
}

export interface AgendaDto {
  date: string;
  staff: { id: number; name: string; color: string; ranges: HourDto[] }[];
  appointments: AppointmentDto[];
  slotMinutes: number;
  bookingEnabled: boolean;
  bookingUrlSlug: string | null;
}

export interface SlotDto {
  time: string;
  staffIds: number[];
}

export const APPOINTMENT_STATUS: Record<
  AppointmentStatus,
  { label: string; tone: string }
> = {
  AGENDADA: { label: "Agendada", tone: "bg-primary-tint text-primary" },
  CONFIRMADA: { label: "Confirmada", tone: "bg-success-soft text-success-ink" },
  ATENDIDA: { label: "Atendida", tone: "bg-slate-100 text-slate-700" },
  NO_ASISTIO: { label: "No asistió", tone: "bg-danger-soft text-danger-ink" },
  CANCELADA: {
    label: "Cancelada",
    tone: "bg-slate-100 text-slate-500 line-through",
  },
};

export interface CommissionLine {
  date: string;
  saleNumber: number;
  item: string;
  service: boolean;
  base: number;
  pct: number;
  commission: number;
}

export interface StaffCommission {
  staffId: number;
  name: string;
  color: string;
  serviceBase: number;
  serviceCommission: number;
  productBase: number;
  productCommission: number;
  total: number;
  services: number;
  paid: number;
  payoutId: number | null;
  lines: CommissionLine[];
}

export interface PayoutDto {
  id: number;
  staffId: number;
  staffName: string;
  from: string;
  to: string;
  base: number;
  amount: number;
  paidBy: string;
  createdAt: string;
}

export interface CommissionReport {
  from: string;
  to: string;
  staff: StaffCommission[];
  total: number;
  payouts: PayoutDto[];
}

export type OrderStatus =
  | "RECIBIDO"
  | "DIAGNOSTICO"
  | "ESPERANDO_APROBACION"
  | "EN_REPARACION"
  | "LISTO"
  | "ENTREGADO"
  | "CANCELADO";

export const ORDER_STATUS: Record<
  OrderStatus,
  { label: string; tone: string; icon: string }
> = {
  RECIBIDO: {
    label: "Recibido",
    tone: "bg-slate-100 text-slate-700",
    icon: "inventory_2",
  },
  DIAGNOSTICO: {
    label: "En diagnóstico",
    tone: "bg-primary-tint text-primary",
    icon: "troubleshoot",
  },
  ESPERANDO_APROBACION: {
    label: "Esperando aprobación",
    tone: "bg-warning-soft text-warning-ink",
    icon: "hourglass_top",
  },
  EN_REPARACION: {
    label: "En reparación",
    tone: "bg-primary-tint text-primary",
    icon: "build",
  },
  LISTO: {
    label: "Listo para entregar",
    tone: "bg-success-soft text-success-ink",
    icon: "task_alt",
  },
  ENTREGADO: {
    label: "Entregado",
    tone: "bg-slate-100 text-slate-500",
    icon: "done_all",
  },
  CANCELADO: {
    label: "Cancelado",
    tone: "bg-danger-soft text-danger-ink",
    icon: "block",
  },
};

export interface OrderSummary {
  id: number;
  number: number;
  customerName: string;
  customerPhone: string | null;
  device: string;
  status: OrderStatus;
  quoteStatus: string;
  technicianName: string | null;
  total: number;
  deposits: number;
  promisedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface OrderDto {
  id: number;
  number: number;
  customerId: number | null;
  customerName: string;
  customerPhone: string | null;
  device: string;
  brand: string | null;
  model: string | null;
  serial: string | null;
  problem: string;
  accessories: string | null;
  conditionNotes: string | null;
  diagnosis: string | null;
  laborPrice: number;
  quoteStatus: "SIN_COTIZAR" | "PENDIENTE" | "APROBADA" | "RECHAZADA";
  status: OrderStatus;
  technicianId: number | null;
  technicianName: string | null;
  warrantyDays: number;
  promisedAt: string | null;
  warrantyUntil: string | null;
  saleId: number | null;
  deliveredAt: string | null;
  createdBy: string | null;
  createdAt: string;
  parts: {
    id: number;
    presentationId: number;
    name: string;
    quantity: number;
    unitPrice: number;
    total: number;
  }[];
  deposits: {
    id: number;
    amount: number;
    method: string;
    reference: string | null;
    userName: string;
    createdAt: string;
  }[];
  events: {
    status: string;
    note: string | null;
    userName: string;
    createdAt: string;
  }[];
  partsTotal: number;
  total: number;
  depositsTotal: number;
  balance: number;
  whatsappUrl: string | null;
}

export const WEEKDAYS = [
  "",
  "Lunes",
  "Martes",
  "Miércoles",
  "Jueves",
  "Viernes",
  "Sábado",
  "Domingo",
];

/** "2026-10-22T10:00:00" → minutos desde medianoche. */
export function minutesOf(iso: string): number {
  const t = iso.slice(11, 16);
  return Number(t.slice(0, 2)) * 60 + Number(t.slice(3, 5));
}

export function hhmm(min: number): string {
  return `${String(Math.floor(min / 60)).padStart(2, "0")}:${String(min % 60).padStart(2, "0")}`;
}

/** "14:30" → "2:30 p. m." */
export function ampm(t: string): string {
  const h = Number(t.slice(0, 2));
  const m = t.slice(3, 5);
  return `${h % 12 === 0 ? 12 : h % 12}${m === "00" ? "" : ":" + m} ${h < 12 ? "a. m." : "p. m."}`;
}

export function addDays(date: string, n: number): string {
  const d = new Date(date + "T12:00:00");
  d.setDate(d.getDate() + n);
  return d.toISOString().slice(0, 10);
}

export function todayIso(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

export function longDate(date: string): string {
  return new Date(date + "T12:00:00").toLocaleDateString("es-CO", {
    weekday: "long",
    day: "numeric",
    month: "long",
  });
}
