"""Crea la tienda de ejemplo de TiendaPOSS: "Minimercado La Esquina".

Todo se hace por la API pública (igual que un usuario), así que también sirve como prueba completa del sistema.

Uso:
    python scripts/crear_tienda_demo.py                      # contra http://localhost:8090
    python scripts/crear_tienda_demo.py --url https://app.midominio.com --dominio demo.midominio.com

Crea: catálogo de 30 productos con presentaciones y códigos de barras, 5 proveedores con sus empaques y costos,
9 usuarios (uno por cada rol), clientes con fiado, ~50 días de ventas con los turnos de caja cuadrados, pagos
digitales verificados, compras (por aprobar, recibida, retrasada), gastos e ingresos, promociones, cuenta de cobro
con QR, recomendaciones de precios, notificaciones y, al final, una copia de seguridad "estado inicial de la demo"
para poder restaurarla cuando se quiera.
"""
import argparse
import base64
import json
import random
import struct
import sys
import time
import urllib.error
import urllib.request
import uuid
import zlib
from collections import defaultdict
from datetime import date, datetime, timedelta, timezone

COL = timezone(timedelta(hours=-5))
PASSWORD = "Demo2026!"


class Api:
    def __init__(self, base):
        self.base = base.rstrip("/") + "/api"

    def call(self, method, path, body=None, token=None, ok_errors=False):
        data = None if body is None else json.dumps(body).encode("utf-8")
        headers = {"Content-Type": "application/json; charset=utf-8"}
        if token:
            headers["Authorization"] = "Bearer " + token
        req = urllib.request.Request(self.base + path, method=method, data=data, headers=headers)
        for attempt in range(3):
            try:
                with urllib.request.urlopen(req, timeout=60) as r:
                    t = r.read().decode("utf-8")
                    return json.loads(t) if t else None
            except urllib.error.HTTPError as e:
                msg = e.read().decode("utf-8", "replace")[:300]
                if ok_errors:
                    return {"_error": e.code, "_message": msg}
                raise SystemExit(f"\n✗ {method} {path} → {e.code}: {msg}")
            except urllib.error.URLError as e:
                if attempt == 2:
                    raise SystemExit(f"\n✗ No se pudo conectar con {self.base}: {e.reason}")
                time.sleep(2)


def qr_png(text_seed):
    """PNG con patrón tipo QR (ilustrativo: NO es un código de pago real)."""
    random.seed(text_seed)
    n, scale = 29, 6

    def finder(x, y, ox, oy):
        dx, dy = x - ox, y - oy
        if 0 <= dx < 7 and 0 <= dy < 7:
            return dx in (0, 6) or dy in (0, 6) or (2 <= dx <= 4 and 2 <= dy <= 4)
        return None

    bits = [[random.random() < 0.45 for _ in range(n)] for _ in range(n)]
    rows = []
    for py in range(n * scale):
        y = py // scale
        row = b"\x00"
        for px in range(n * scale):
            x = px // scale
            v = None
            for ox, oy in ((0, 0), (n - 7, 0), (0, n - 7)):
                f = finder(x, y, ox, oy)
                if f is not None:
                    v = f
            row += b"\x00" if (bits[y][x] if v is None else v) else b"\xff"
        rows.append(row)

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    w = n * scale
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, w, 8, 0, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(b"".join(rows))) + chunk(b"IEND", b"")
    return "data:image/png;base64," + base64.b64encode(png).decode()


# (clave, nombre, categoría, marca, precio, costo, impuesto %, existencia final, mínimo, popularidad, empaque, factor, precio empaque, proveedor)
PRODUCTS = [
    ("aguila", "Cerveza Águila 330 ml lata", "Cervezas", "Bavaria", 3200, 2150, 0, 72, 48, 10, "Six-pack", 6, 18000, "bavaria"),
    ("poker", "Cerveza Poker 330 ml lata", "Cervezas", "Bavaria", 3000, 2000, 0, 60, 48, 9, "Six-pack", 6, 17000, "bavaria"),
    ("club", "Club Colombia Dorada 330 ml", "Cervezas", "Bavaria", 3800, 2600, 0, 36, 24, 5, "Six-pack", 6, 21600, "bavaria"),
    ("corona", "Corona Extra 355 ml", "Cervezas", "AB InBev", 5500, 3900, 0, 24, 12, 3, None, None, None, "bavaria"),
    ("coca400", "Coca-Cola 400 ml", "Bebidas", "Coca-Cola", 3000, 1900, 19, 40, 24, 8, None, None, None, "femsa"),
    ("coca15", "Coca-Cola 1,5 L", "Bebidas", "Coca-Cola", 6500, 4300, 19, 18, 12, 4, None, None, None, "femsa"),
    ("pony", "Pony Malta 330 ml", "Bebidas", "Postobón", 2500, 1600, 19, 30, 24, 5, None, None, None, "postobon"),
    ("manzana", "Manzana Postobón 400 ml", "Bebidas", "Postobón", 2500, 1550, 19, 28, 24, 4, None, None, None, "postobon"),
    ("agua", "Agua Cristal 600 ml", "Bebidas", "Postobón", 1800, 950, 0, 40, 24, 6, None, None, None, "postobon"),
    ("gatorade", "Gatorade 500 ml", "Bebidas", "PepsiCo", 4500, 3100, 19, 12, 12, 2, None, None, None, "femsa"),
    ("aguardiente", "Aguardiente Antioqueño 375 ml", "Licores", "FLA", 28000, 21500, 0, 10, 6, 3, None, None, None, "fla"),
    ("ron", "Ron Medellín Añejo 375 ml", "Licores", "FLA", 32000, 23000, 0, 22, 4, 0, None, None, None, "fla"),
    ("marlboro", "Marlboro Rojo cajetilla 20", "Cigarrillos", "Philip Morris", 11500, 9800, 0, 20, 15, 4, "Cartón x10", 10, 112000, "andina"),
    ("lucky", "Lucky Strike cajetilla 20", "Cigarrillos", "BAT", 10000, 8600, 0, 15, 10, 3, None, None, None, "andina"),
    ("papas", "Papas Margarita Natural 39 g", "Snacks", "Margarita", 2500, 1600, 19, 30, 20, 6, None, None, None, "andina"),
    ("detodito", "Detodito Natural 45 g", "Snacks", "Frito Lay", 2800, 1850, 19, 24, 15, 4, None, None, None, "andina"),
    ("mani", "Maní Moto 50 g", "Snacks", "La Especial", 1500, 900, 19, 20, 15, 4, None, None, None, "andina"),
    ("chocorramo", "Chocorramo", "Snacks", "Ramo", 2300, 1500, 19, 24, 12, 5, None, None, None, "andina"),
    ("leche", "Leche Alquería entera 1 L", "Lácteos", "Alquería", 4200, 3400, 0, 18, 12, 5, None, None, None, "andina"),
    ("yogurt", "Yogurt Alpina fresa 200 g", "Lácteos", "Alpina", 2900, 2100, 0, 14, 10, 3, None, None, None, "andina"),
    ("arroz", "Arroz Diana 500 g", "Granos y abarrotes", "Diana", 2700, 2550, 0, 30, 20, 4, None, None, None, "andina"),
    ("aceite", "Aceite Premier 900 ml", "Granos y abarrotes", "Premier", 11900, 9300, 19, 10, 6, 2, None, None, None, "andina"),
    ("azucar", "Azúcar Manuelita 1 kg", "Granos y abarrotes", "Manuelita", 4800, 4000, 5, 14, 8, 2, None, None, None, "andina"),
    ("huevo", "Huevo AA unidad", "Granos y abarrotes", "Granja", 600, 420, 0, 120, 60, 6, "Panal x30", 30, 16500, "andina"),
    ("pan", "Pan tajado Bimbo", "Panadería", "Bimbo", 6900, 5300, 0, 8, 6, 2, None, None, None, "andina"),
    ("jabon", "Jabón Rey barra", "Aseo", "Rey", 3200, 2300, 19, 15, 8, 1, None, None, None, "andina"),
    ("papel", "Papel higiénico Familia x4", "Aseo", "Familia", 7500, 5600, 19, 10, 6, 1, None, None, None, "andina"),
    ("hielo", "Bolsa de hielo 3 kg", "Bebidas", "Hielo Polar", 5000, 2500, 0, 0, 5, 3, None, None, None, None),
    ("encendedor", "Encendedor Bic", "Cigarrillos", "Bic", 3000, 1700, 19, 25, 5, 0, None, None, None, "andina"),
    ("vino", "Vino Gato Negro 750 ml", "Licores", "San Pedro", 32000, 24500, 19, 6, 2, 0, None, None, None, "fla"),
]
QTY = {"aguila": [1, 1, 2, 3, 6], "poker": [1, 2, 3, 6], "club": [1, 2], "huevo": [1, 2, 6, 10, 30], "agua": [1, 1, 2]}

SUPPLIERS = {
    "bavaria": dict(name="Bavaria", contactName="Ricardo Gómez (preventista)", whatsapp="3104440001", email="pedidos@bavaria-demo.test", city="Medellín",
                    creditDays=8, orderDays="lun,jue", deliveryDays="mar,vie", leadTimeDays=1, paymentTerms="8 días"),
    "femsa": dict(name="Coca-Cola FEMSA", contactName="Paula Ríos", whatsapp="3104440002", email="pedidos@femsa-demo.test", city="Medellín",
                  creditDays=15, orderDays="mar", deliveryDays="jue", leadTimeDays=2, paymentTerms="15 días"),
    "postobon": dict(name="Postobón", contactName="Jorge Arango", whatsapp="3104440003", city="Medellín", creditDays=15, orderDays="mie",
                     deliveryDays="vie", leadTimeDays=2, paymentTerms="15 días"),
    "fla": dict(name="Distribuidora de Licores FLA", contactName="Martha Cano", whatsapp="3104440004", city="Medellín", creditDays=30,
                orderDays="lun", deliveryDays="mie", leadTimeDays=2, paymentTerms="30 días"),
    "andina": dict(name="Distribuidora La Andina", contactName="Hernán Muñoz", whatsapp="3104440005", email="ventas@andina-demo.test",
                   city="Bello", creditDays=15, orderDays="lun,mie,vie", deliveryDays="mar,jue,sab", leadTimeDays=1, paymentTerms="15 días"),
}
# Empaque de compra por proveedor: (empaque, factor)
BUY_PACKS = {"aguila": ("Caja x24", 24), "poker": ("Caja x24", 24), "club": ("Caja x24", 24), "corona": ("Caja x24", 24),
             "coca400": ("Paca x12", 12), "coca15": ("Paca x6", 6), "pony": ("Paca x12", 12), "manzana": ("Paca x12", 12),
             "agua": ("Paca x24", 24), "gatorade": ("Paca x12", 12), "aguardiente": ("Caja x12", 12), "ron": ("Caja x12", 12),
             "marlboro": ("Cartón x10", 10), "lucky": ("Cartón x10", 10), "papas": ("Display x12", 12), "detodito": ("Display x12", 12),
             "mani": ("Display x24", 24), "chocorramo": ("Caja x12", 12), "leche": ("Canasta x12", 12), "yogurt": ("Bandeja x12", 12),
             "arroz": ("Arroba x25", 25), "aceite": ("Caja x12", 12), "azucar": ("Fardo x25", 25), "huevo": ("Panal x30", 30),
             "pan": ("Unidad", 1), "jabon": ("Caja x24", 24), "papel": ("Fardo x12", 12), "encendedor": ("Display x50", 50), "vino": ("Caja x6", 6)}

STAFF = [  # (rol del sistema, nombre, correo, PIN)
    ("ADMINISTRADOR", "Carolina Mejía", "admin", None),
    ("SUPERVISOR", "Julián Restrepo", "supervisor", "1357"),
    ("CAJERO", "Laura Gómez", "cajera", None),
    ("CAJERO", "Andrés Zapata", "cajero", None),
    ("INVENTARIO", "Mateo Arias", "inventario", None),
    ("COMPRAS", "Sandra Ochoa", "compras", None),
    ("CONTADOR", "Beatriz Londoño", "contadora", None),
    ("AUDITOR", "Felipe Cardona", "auditor", None),
]
CUSTOMERS = [  # (nombre, cédula, celular, fía)
    ("Doña Gloria Ramírez", "43512890", "3005551201", True),
    ("Jhon Fredy Ospina", "1036223344", "3127778899", True),
    ("Marta Lucía Henao", "42988123", "3015552233", True),
    ("Carlos Andrés Ruiz", "1017234567", "3209991122", True),
    ("Ana María Vélez", "1152443322", "3046667788", False),
    ("Taller El Paisa", "900123456", "3113334455", False),
]


def main():
    ap = argparse.ArgumentParser(description="Crea la tienda de ejemplo de TiendaPOSS")
    ap.add_argument("--url", default="http://localhost:8090", help="Dirección del servidor (sin /api)")
    ap.add_argument("--dominio", default="tiendademo.test", help="Dominio de los correos de prueba")
    ap.add_argument("--dias", type=int, default=50, help="Días de historial de ventas")
    a = ap.parse_args()
    api = Api(a.url)
    rnd = random.Random(2026)
    mail = lambda user: f"{user}@{a.dominio}"
    today = datetime.now(COL).date()

    if api.call("GET", "/health", ok_errors=True) is None:
        pass
    if "token" in (api.call("POST", "/auth/login", {"email": mail("dueno"), "password": PASSWORD}, ok_errors=True) or {}):
        raise SystemExit(f"La tienda de ejemplo ya existe ({mail('dueno')}). Para volver a su estado inicial, restaura la copia "
                         "\"estado inicial de la demo\" en Más → Copias de seguridad; o crea otra con --dominio otra.test")

    step = lambda s: print(f"• {s}", flush=True)
    step("Creando la tienda y el dueño")
    reg = api.call("POST", "/auth/register", {"storeName": "Minimercado La Esquina", "ownerName": "Diego Parada", "email": mail("dueno"),
                                              "password": PASSWORD, "phone": "3001234567"})
    owner = reg["token"]
    api.call("PUT", "/store", {"name": "Minimercado La Esquina", "legalName": "Minimercado La Esquina S.A.S.", "taxId": "901555333-1",
                               "taxRegime": "Responsable de IVA", "address": "Calle 50 # 45-12, barrio Prado", "city": "Medellín",
                               "phone": "6044445566", "whatsapp": "3001234567", "email": mail("tienda"), "currency": "COP",
                               "timezone": "America/Bogota"}, owner)
    api.call("POST", "/auth/pin", {"currentPassword": PASSWORD, "pin": "2468"}, owner)
    st = api.call("GET", "/settings/pos", token=owner)
    api.call("PUT", "/settings/pos", {**{k: st[k] for k in ("pricesIncludeTax", "maxDiscountPct", "allowNegativeStock", "requireOpenShift",
                                                            "openAccountsLimit", "costMethod", "receiptWidthMm", "receiptShowTax",
                                                            "defaultTaxName", "defaultTaxRate", "cashDenominations")},
                                      "paymentMethods": ["EFECTIVO", "NEQUI", "DAVIPLATA", "BREB", "TRANSFERENCIA", "TARJETA"],
                                      "receiptHeader": "Minimercado La Esquina\nNIT 901555333-1 · Calle 50 # 45-12",
                                      "receiptFooter": "¡Gracias por su compra! Domicilios al 300 123 4567"}, owner)
    api.call("PUT", "/settings/pos/finance", {"verifyDigitalPayments": True, "creditEnabled": True, "defaultCreditLimit": 150000,
                                              "defaultCreditDays": 15}, owner)
    api.call("PUT", "/settings/pos/inventory", {**{k: st[k] for k in ("analysisDays", "noRotationDays", "excessDays", "expiryAlertDays",
                                                                       "defaultLeadDays", "reviewDays", "unusualDiffPct", "unusualDiffValue",
                                                                       "abcAPct", "abcBPct")}, "purchaseBudget": 6000000}, owner)
    api.call("POST", "/payment-accounts", {"method": "NEQUI", "label": "Nequi Minimercado La Esquina", "holder": "Diego Parada",
                                           "accountRef": "300 123 4567", "qrImage": qr_png("nequi-demo"), "active": True}, owner)
    api.call("POST", "/payment-accounts", {"method": "BREB", "label": "Llave Bre-B", "holder": "Minimercado La Esquina",
                                           "accountRef": "@laesquina", "active": True}, owner)

    step("Usuarios de cada rol")
    roles = {r.get("systemCode"): r["id"] for r in api.call("GET", "/roles", token=owner)}
    tokens = {"dueno": owner}
    for code, name, user, pin in STAFF:
        api.call("POST", "/users", {"fullName": name, "email": mail(user), "password": PASSWORD, "phone": "300" + str(rnd.randint(1000000, 9999999)),
                                    "roleIds": [roles[code]]}, owner)
        tokens[user] = api.call("POST", "/auth/login", {"email": mail(user), "password": PASSWORD})["token"]
        if pin:
            api.call("POST", "/auth/pin", {"currentPassword": PASSWORD, "pin": pin}, tokens[user])
    for name in ("Caja 2", "Caja 3", "Caja 4"):
        api.call("POST", "/registers", {"name": name}, owner)
    registers = [r["id"] for r in api.call("GET", "/registers", token=owner)]

    step("Clientes")
    customers = {}
    for name, doc, phone, fia in CUSTOMERS:
        customers[name] = api.call("POST", "/pos/customers", {"name": name, "docNumber": doc, "phone": phone, "docType": "CC" if len(doc) < 10 or doc.startswith("10") else "NIT"}, owner)["id"]
    fiadores = [customers[n] for n, _, _, f in CUSTOMERS if f]

    # ---- Plan de ventas (se arma primero para saber cuánto inventario hace falta) ----
    keys = [p[0] for p in PRODUCTS]
    pop = {p[0]: p[9] for p in PRODUCTS}
    price = {p[0]: p[4] for p in PRODUCTS}
    plan = []  # (fecha, hora, minuto, vendedor, líneas[(clave, cantidad)], medio)
    sellers = ["cajera", "cajero", "dueno", "supervisor"]
    for days_ago in range(a.dias, -1, -1):
        d = today - timedelta(days=days_ago)
        n = rnd.randint(15, 22) + (8 if d.weekday() in (4, 5) else 0) + (5 if d.day in (15, 16, 30, 31, 1) else 0) + (a.dias - days_ago) // 12
        if days_ago == 0:
            n = 0  # las de hoy se hacen en vivo más abajo
        for _ in range(n):
            hour = rnd.choices([8, 10, 12, 14, 16, 17, 18, 19, 20, 21], [1, 1, 2, 2, 2, 3, 4, 5, 5, 3])[0]
            lines = {}
            for _ in range(rnd.choice([1, 1, 2, 2, 3])):
                k = rnd.choices(keys, [pop[x] for x in keys])[0]
                if pop[k] == 0:
                    continue
                lines[k] = lines.get(k, 0) + rnd.choice(QTY.get(k, [1, 1, 2]))
            if not lines:
                continue
            method = rnd.choices(["EFECTIVO", "NEQUI", "DAVIPLATA", "TARJETA", "FIADO"], [56, 22, 8, 10, 4])[0]
            plan.append((d, hour, rnd.randint(0, 59), rnd.choices(sellers, [4, 4, 2, 1])[0], list(lines.items()), method))
    # Productos que no se mueven: una venta hace 45 días y nada más (para "sin rotación").
    for k in ("ron", "encendedor", "vino"):
        plan.append((today - timedelta(days=45), 19, 30, "cajera", [(k, 1)], "EFECTIVO"))
    sold = defaultdict(int)
    for *_, lines, _m in plan:
        for k, q in lines:
            sold[k] += q
    today_extra = {"aguila": 20, "poker": 12, "coca400": 8, "huevo": 30, "papas": 4, "agua": 4, "leche": 2, "marlboro": 2}

    step("Catálogo: categorías y 30 productos")
    cats = {}
    for c in sorted({p[2] for p in PRODUCTS}):
        cats[c] = api.call("POST", "/categories", {"name": c}, owner)["id"]
    prod = {}
    for i, (k, name, cat, brand, pr, cost, tax, final, mn, _pop, pack, factor, packPrice, _sup) in enumerate(PRODUCTS):
        initial = final + sold[k] + today_extra.get(k, 0)
        pres = [{"name": "Unidad", "factor": 1, "barcode": f"77{(70210000 + i * 137):08d}{i % 10}", "price": pr,
                 "minPrice": round(pr * 0.9 / 50) * 50, "isBase": True}]
        if pack:
            pres.append({"name": pack, "factor": factor, "barcode": f"77{(80210000 + i * 137):08d}{i % 10}", "price": packPrice, "isBase": False})
        r = api.call("POST", "/products", {"name": name, "brand": brand, "categoryId": cats[cat], "unit": "UNIDAD", "cost": cost,
                                           "initialStock": initial, "stockMin": mn, "stockMax": mn * 4, "taxName": "IVA", "taxRate": tax,
                                           "favorite": pop[k] >= 8, "presentations": pres, "trackLots": k == "yogurt"}, owner)
        prod[k] = {"id": r["id"], "base": next(p["id"] for p in r["presentations"] if p["isBase"]),
                   "pack": next((p["id"] for p in r["presentations"] if not p["isBase"]), None)}

    step("Proveedores y sus catálogos de compra")
    sup = {}
    for key, data in SUPPLIERS.items():
        sup[key] = api.call("POST", "/suppliers", {**data, "taxId": f"8{rnd.randint(10000000, 99999999)}-{rnd.randint(1, 9)}"}, owner)["id"]
    for p in PRODUCTS:
        k, s = p[0], p[13]
        if not s:
            continue
        packName, f = BUY_PACKS[k]
        cost = p[5]
        api.call("POST", f"/suppliers/{sup[s]}/catalog", {"productId": prod[k]["id"], "packName": packName, "packFactor": f,
                                                          "costPerPack": cost * f, "minPacks": 1, "multiplePacks": 1, "isPrimary": True}, owner)
    # Proveedor alterno más caro para comparar.
    api.call("POST", f"/suppliers/{sup['andina']}/catalog", {"productId": prod["coca400"]["id"], "packName": "Paca x12", "packFactor": 12,
                                                             "costPerPack": 1990 * 12, "minPacks": 1, "multiplePacks": 1, "isPrimary": False}, owner)

    step(f"Historial de {a.dias} días de ventas ({len(plan)} ventas, toma un par de minutos)")
    shift_of = {}
    for i, user in enumerate(sellers):
        shift_of[user] = api.call("POST", "/shifts/open", {"registerId": registers[i % len(registers)], "openingCash": 200000}, tokens[user])["id"]
    for n, (d, h, m, user, lines, method) in enumerate(sorted(plan, key=lambda x: (x[0], x[1], x[2]))):
        total = sum(price[k] * q for k, q in lines)
        at = datetime(d.year, d.month, d.day, h, m, tzinfo=COL).astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        body = {"clientRef": str(uuid.uuid4()), "offline": True, "offlineShiftId": shift_of[user], "offlineCreatedAt": at,
                "lines": [{"presentationId": prod[k]["base"], "quantity": q} for k, q in lines]}
        if method == "EFECTIVO":
            body["payments"] = [{"method": "EFECTIVO", "amount": total if rnd.random() < 0.4 else ((total // 10000) + 1) * 10000}]
        elif method == "FIADO":
            body["customer"] = {"customerId": rnd.choice(fiadores)}
            body["payments"] = [{"method": "FIADO", "amount": total}]
        else:
            body["payments"] = [{"method": method, "amount": total, "reference": ("M" if method == "NEQUI" else "R") + str(rnd.randint(10000000, 99999999))}]
        r = api.call("POST", "/pos/sales", body, tokens[user], ok_errors=True)
        if "_error" in r:  # p. ej. fiado sobre el cupo: se cobra en efectivo
            body["payments"] = [{"method": "EFECTIVO", "amount": total}]
            body.pop("customer", None)
            body["clientRef"] = str(uuid.uuid4())
            api.call("POST", "/pos/sales", body, tokens[user])
        if n % 100 == 0:
            print(f"  {n}/{len(plan)}", flush=True)

    step("Verificando pagos digitales del historial y cerrando los turnos")
    for p in api.call("GET", "/payments?status=PENDIENTE", token=owner):
        if p["createdAt"][:10] < str(today - timedelta(days=1)):
            api.call("POST", f"/payments/{p['id']}/confirm", {"note": "Visto en el extracto"}, owner)
    for user in sellers:
        s = api.call("GET", "/shifts/current", token=tokens[user])
        diff = -3500 if user == "cajero" else 0  # un faltante para que la demo muestre diferencias de caja
        api.call("POST", f"/shifts/{s['id']}/close", {"countedCash": round(s["expectedCash"]) + diff,
                                                       "closeNotes": "Faltó un billete de cambio" if diff else "Cuadró"}, tokens[user])
        if user == "dueno":  # el dueño saca la plata a la cuenta del banco al cerrar el historial
            pass

    step("Compras: recibida, por aprobar, retrasada y pago a proveedor")
    def order(supplier, items, token, expected=None):
        lines = [{"productId": prod[k]["id"], "packs": packs} for k, packs in items]
        return api.call("POST", "/purchases", {"supplierId": sup[supplier], "lines": lines, "expectedDate": expected, "source": "MANUAL"}, token)
    o = order("bavaria", [("aguila", 4), ("poker", 3), ("club", 2)], tokens["compras"])
    api.call("POST", f"/purchases/{o['id']}/submit", None, tokens["compras"])
    o = api.call("POST", f"/purchases/{o['id']}/approve", None, owner)
    api.call("POST", f"/purchases/{o['id']}/send", {"via": "WHATSAPP"}, owner)
    api.call("POST", f"/purchases/{o['id']}/receive", {"invoiceNumber": "BV-48812", "lines": [
        {"orderLineId": l["id"], "unitsReceived": l["unitsOrdered"] - (2 if i == 0 else 0), "unitsRejected": 2 if i == 0 else 0,
         "rejectReason": "DANADO" if i == 0 else None} for i, l in enumerate(o["lines"])]}, tokens["inventario"])
    o = order("andina", [("yogurt", 2), ("leche", 2), ("huevo", 3)], tokens["compras"])
    api.call("POST", f"/purchases/{o['id']}/submit", None, tokens["compras"])
    o = api.call("POST", f"/purchases/{o['id']}/approve", None, owner)
    api.call("POST", f"/purchases/{o['id']}/receive", {"invoiceNumber": "AND-2210", "lines": [
        {"orderLineId": l["id"], "unitsReceived": l["unitsOrdered"],
         "lotCode": "L" + str(rnd.randint(1000, 9999)) if l["productName"].startswith("Yogurt") else None,
         "expiryDate": str(today + timedelta(days=6)) if l["productName"].startswith("Yogurt") else None} for l in o["lines"]]}, tokens["inventario"])
    o = order("postobon", [("pony", 2), ("manzana", 2), ("agua", 2)], tokens["compras"])
    api.call("POST", f"/purchases/{o['id']}/submit", None, tokens["compras"])  # queda por aprobar
    o = order("fla", [("aguardiente", 1)], owner, str(today - timedelta(days=2)))
    api.call("POST", f"/purchases/{o['id']}/approve", None, owner, ok_errors=True)  # retrasado si la fecha quedó atrás
    api.call("POST", "/finance/supplier-payments", {"supplierId": sup["bavaria"], "amount": 200000, "method": "TRANSFERENCIA",
                                                     "paidFrom": "BANCO", "reference": "TRF-99812"}, owner)

    step("Gastos e ingresos del mes pasado y de este mes")
    fcats = {c["name"]: c["id"] for c in api.call("GET", "/finance/categories", token=owner)}
    first = today.replace(day=1)
    prev = (first - timedelta(days=1)).replace(day=1)
    for d0 in (prev, first):
        for cat, desc, amount, day in (("Arriendo", "Arriendo del local", 750000, 1), ("Servicios públicos", "EPM energía y agua", 185000, 5),
                                       ("Internet y teléfono", "Internet y plan celular", 70000, 6), ("Nómina", "Nómina quincena 1 (auxiliar)", 420000, 15),
                                       ("Nómina", "Nómina quincena 2 (auxiliar)", 420000, 28), ("Transporte", "Domicilios y fletes", 60000, 18)):
            dd = d0.replace(day=min(day, 28))
            if dd > today:
                continue
            api.call("POST", "/finance/entries", {"kind": "GASTO", "categoryId": fcats[cat], "description": desc, "amount": amount,
                                                  "entryDate": str(dd), "method": "TRANSFERENCIA", "paidFrom": "BANCO"}, owner)
    api.call("POST", "/finance/entries", {"kind": "INGRESO", "categoryId": fcats["Aporte del dueño"], "description": "Capital para surtir diciembre",
                                          "amount": 1000000, "entryDate": str(prev.replace(day=20)), "method": "TRANSFERENCIA", "paidFrom": "BANCO"}, owner)

    step("Hoy: turnos abiertos, ventas en vivo, una anulación, una devolución, gasto de caja y abonos de fiado")
    t_caj = tokens["cajera"]
    api.call("POST", "/shifts/open", {"registerId": registers[0], "openingCash": 200000}, owner)
    api.call("POST", "/shifts/open", {"registerId": registers[1], "openingCash": 150000}, t_caj)
    live = []
    for user, lines, pay in (
            ("cajera", [("aguila", 6), ("papas", 2)], [("EFECTIVO", 30000)]),
            ("cajera", [("huevo", 30)], [("EFECTIVO", 20000)]),
            ("dueno", [("poker", 6), ("hielo", 0)], [("NEQUI", None, "M77120345")]),
            ("cajera", [("coca400", 4), ("agua", 2)], [("DAVIPLATA", None, "D55120933")]),
            ("cajera", [("aguila", 8), ("mani", 0)], [("EFECTIVO", 30000)]),
            ("dueno", [("poker", 6), ("leche", 2)], [("EFECTIVO", 50000)]),
            ("cajera", [("marlboro", 2), ("aguila", 6)], [("NEQUI", None, "M77129001")]),
            ("dueno", [("coca400", 4), ("agua", 2), ("papas", 2)], [("TARJETA", None, "VOUCH-4412")])):
        lines = [(k, q) for k, q in lines if q > 0]
        total = sum(price[k] * q for k, q in lines)
        pays = [{"method": m, "amount": (amt or total), **({"reference": ref[0]} if ref else {})} for m, amt, *ref in pay]
        live.append(api.call("POST", "/pos/sales", {"clientRef": str(uuid.uuid4()), "lines": [{"presentationId": prod[k]["base"], "quantity": q} for k, q in lines],
                                                    "payments": pays}, tokens[user]))
    # Venta fiada de hoy y un abono.
    api.call("POST", "/pos/sales", {"clientRef": str(uuid.uuid4()), "customer": {"customerId": fiadores[0]},
                                    "lines": [{"presentationId": prod["arroz"]["base"], "quantity": 2}, {"presentationId": prod["aceite"]["base"], "quantity": 1}],
                                    "payments": [{"method": "FIADO", "amount": price["arroz"] * 2 + price["aceite"]}]}, t_caj)
    for cid, amount in ((fiadores[0], 20000), (fiadores[1], 15000)):
        api.call("POST", f"/credit/{cid}/payments", {"amount": amount, "method": "EFECTIVO", "note": "Abono"}, t_caj, ok_errors=True)
    # Anulación (con PIN del supervisor) y devolución.
    sup_id = next(u["id"] for u in api.call("GET", "/users", token=owner) if u["email"] == mail("supervisor"))
    pass_tok = api.call("POST", "/pos/approvals", {"approverId": sup_id, "secret": "1357", "permission": "POS_ANULAR"}, t_caj)["token"]
    api.call("POST", f"/pos/sales/{live[1]['id']}/void", {"reason": "Cliente se arrepintió antes de salir", "approvals": [pass_tok]}, t_caj)
    ret_tok = api.call("POST", "/pos/approvals", {"approverId": sup_id, "secret": "1357", "permission": "POS_DEVOLUCION"}, t_caj)["token"]
    sale0 = api.call("GET", f"/pos/sales/{live[0]['id']}", token=owner)
    papas_line = next(l for l in sale0["lines"] if l["productName"].startswith("Papas"))
    api.call("POST", f"/pos/sales/{live[0]['id']}/returns", {"reason": "Paquete abierto", "refundMethod": "EFECTIVO", "approvals": [ret_tok],
                                                              "lines": [{"saleLineId": papas_line["id"], "quantity": 1, "restock": False, "condition": "DANADO"}]}, t_caj)
    shift = api.call("GET", "/shifts/current", token=t_caj)
    api.call("POST", f"/shifts/{shift['id']}/movements", {"type": "GASTO", "amount": 12000, "reason": "Bolsas y servilletas", "category": "Insumos"}, t_caj)

    step("Promociones")
    api.call("POST", "/pos/promotions", {"name": "Six-pack Águila", "type": "PRECIO_POR_CANTIDAD", "productId": prod["aguila"]["id"],
                                         "bundleQty": 6, "bundlePrice": 17500, "active": True}, owner, ok_errors=True)
    api.call("POST", "/pos/promotions", {"name": "Chocorramo 3x2", "type": "LLEVA_PAGA", "productId": prod["chocorramo"]["id"],
                                         "bundleQty": 3, "payQty": 2, "active": True}, owner, ok_errors=True)
    api.call("POST", "/pos/promotions", {"name": "10% en snacks los viernes", "type": "PORCENTAJE", "categoryId": cats["Snacks"], "percent": 10,
                                         "active": False}, owner, ok_errors=True)

    step("Inteligencia: recomendaciones, notificaciones y copia de seguridad inicial")
    api.call("POST", "/recommendations/generate", None, owner, ok_errors=True)
    api.call("POST", "/notifications/scan", None, owner, ok_errors=True)
    api.call("PUT", "/notifications/channels", {"emailTo": mail("dueno"), "dailyReportHour": 21}, owner)
    api.call("POST", "/backups", None, owner)

    print("\n✓ Tienda de ejemplo lista: Minimercado La Esquina")
    print(f"  Contraseña de todos los usuarios: {PASSWORD}")
    print(f"  Dueño:        {mail('dueno')}  (PIN 2468)")
    for code, name, user, pin in STAFF:
        print(f"  {code.title():<13} {mail(user)}  ({name}{', PIN ' + pin if pin else ''})")


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    main()
