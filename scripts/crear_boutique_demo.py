"""Crea la boutique de ejemplo de la Fase 8: "Boutique Luna" (ropa, calzado y accesorios).

Todo se hace por la API pública, igual que lo haría una persona. Muestra el catálogo avanzado:
variantes (tallas, colores, números), combos, relojes con serial y garantía, y ventas de las últimas semanas.
También activa la báscula en el "Minimercado La Esquina" (si existe) con dos productos por peso.

Uso:
    python scripts/crear_boutique_demo.py                       # contra http://localhost:8090
    python scripts/crear_boutique_demo.py --url https://app.midominio.com --dominio boutique.midominio.com
"""
import argparse
import json
import random
import sys
import time
import urllib.error
import urllib.request
import uuid
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


# (clave, nombre, categoría, marca, precio base, costo, opciones, ajuste de precio por valor, stock por variante)
VARIANT_PRODUCTS = [
    ("camiseta", "Camiseta básica algodón", "Camisetas", "Luna", 39900, 16000,
     [("Talla", ["S", "M", "L", "XL"]), ("Color", ["Negro", "Blanco", "Azul"])], {"XL": 2000}, (6, 14)),
    ("polo", "Camiseta polo piqué", "Camisetas", "Luna", 59900, 26000,
     [("Talla", ["S", "M", "L", "XL"]), ("Color", ["Blanco", "Vinotinto"])], {"XL": 3000}, (4, 10)),
    ("jean", "Jean slim fit", "Jeans", "Denim Co", 119900, 52000,
     [("Talla", ["28", "30", "32", "34", "36"]), ("Color", ["Azul clásico", "Negro"])], {"36": 5000}, (3, 9)),
    ("vestido", "Vestido floral midi", "Vestidos", "Luna", 129900, 55000,
     [("Talla", ["S", "M", "L"])], {}, (5, 10)),
    ("tenis", "Tenis urbanos lona", "Calzado", "Urban", 149900, 68000,
     [("Número", ["36", "37", "38", "39", "40", "41", "42"]), ("Color", ["Blanco", "Negro"])], {}, (2, 6)),
    ("gorra", "Gorra bordada", "Accesorios", "Luna", 34900, 12000,
     [("Color", ["Negro", "Beige", "Azul"])], {}, (6, 12)),
]

# (clave, nombre, categoría, marca, precio, costo, stock, código)
SIMPLE_PRODUCTS = [
    ("medias", "Medias tobilleras x3", "Accesorios", "Luna", 19900, 7000, 40, "7709991000011"),
    ("cinturon", "Cinturón de cuero", "Accesorios", "Cuero Fino", 59900, 24000, 15, "7709991000028"),
    ("bolsa", "Bolsa de regalo", "Accesorios", None, 3000, 900, 80, "7709991000035"),
]

STAFF = [("CAJERO", "Valentina Ríos", "cajera", None), ("SUPERVISOR", "Camilo Duarte", "supervisor", "1357")]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://localhost:8090")
    ap.add_argument("--dominio", default="boutiquedemo.test")
    ap.add_argument("--dias", type=int, default=30)
    a = ap.parse_args()
    api = Api(a.url)
    mail = lambda u: f"{u}@{a.dominio}"
    rnd = random.Random(8)
    step = lambda s: print(f"• {s}", flush=True)

    if "token" in (api.call("POST", "/auth/login", {"email": mail("dueno"), "password": PASSWORD}, ok_errors=True) or {}):
        raise SystemExit(f"La boutique de ejemplo ya existe ({mail('dueno')}). Restaura su copia \"estado inicial\" o usa --dominio otra.test")

    step("Creando la boutique (tipo de negocio: ropa, calzado y accesorios)")
    owner = api.call("POST", "/auth/register", {"storeName": "Boutique Luna", "ownerName": "Mariana Luna", "email": mail("dueno"),
                                                "password": PASSWORD, "phone": "3105556677", "businessType": "MODA",
                                                "acceptTerms": True, "acceptPrivacy": True})["token"]
    api.call("PUT", "/store", {"name": "Boutique Luna", "legalName": "Boutique Luna S.A.S.", "taxId": "901777888-2",
                               "taxRegime": "Responsable de IVA", "address": "Carrera 43A # 9-20, El Poblado", "city": "Medellín",
                               "phone": "6043332211", "whatsapp": "3105556677", "email": mail("tienda"), "currency": "COP",
                               "timezone": "America/Bogota"}, owner)
    api.call("POST", "/auth/pin", {"currentPassword": PASSWORD, "pin": "2468"}, owner)
    # Además de lo del perfil de moda, relojes con serial y garantía.
    api.call("PUT", "/settings/pos/modules", {"businessType": "MODA", "modules": ["VARIANTES", "COMBOS", "COMPRAS", "SERIALES"]}, owner)
    st = api.call("GET", "/settings/pos", token=owner)
    api.call("PUT", "/settings/pos", {**{k: st[k] for k in ("pricesIncludeTax", "maxDiscountPct", "allowNegativeStock", "requireOpenShift",
                                                            "openAccountsLimit", "costMethod", "receiptWidthMm", "receiptShowTax",
                                                            "cashDenominations")},
                                      "defaultTaxName": "IVA", "defaultTaxRate": 19,
                                      "paymentMethods": ["EFECTIVO", "TARJETA", "NEQUI", "BREB", "TRANSFERENCIA"],
                                      "receiptHeader": "Boutique Luna\nNIT 901777888-2 · Cra 43A # 9-20",
                                      "receiptFooter": "Cambios hasta 30 días con este comprobante y la etiqueta."}, owner)

    step("Usuarios")
    roles = {r.get("systemCode"): r["id"] for r in api.call("GET", "/roles", token=owner)}
    tokens = {"dueno": owner}
    for code, name, user, pin in STAFF:
        api.call("POST", "/users", {"fullName": name, "email": mail(user), "password": PASSWORD, "roleIds": [roles[code]]}, owner)
        tokens[user] = api.call("POST", "/auth/login", {"email": mail(user), "password": PASSWORD})["token"]
        if pin:
            api.call("POST", "/auth/pin", {"currentPassword": PASSWORD, "pin": pin}, tokens[user])
    registers = [r["id"] for r in api.call("GET", "/registers", token=owner)]

    step("Categorías y productos con variantes")
    cats = {}
    for c in ("Camisetas", "Jeans", "Vestidos", "Calzado", "Accesorios", "Relojes"):
        cats[c] = api.call("POST", "/categories", {"name": c}, owner)["id"]
    sellable = {}  # clave -> lista de (presentationId, precio, productId)
    avail = {}  # productId -> unidades disponibles (para no vender lo que no hay)
    parts = {}  # productId del combo -> [(productId, cantidad)]
    code_seq = [0]
    for key, name, cat, brand, price, cost, options, extra, (smin, smax) in VARIANT_PRODUCTS:
        p = api.call("POST", "/products", {"sku": key.upper()[:3] + "01", "name": name, "unit": "UNIDAD", "categoryId": cats[cat], "brand": brand,
                                           "cost": cost, "stockMin": 1, "taxName": "IVA", "taxRate": 19,
                                           "presentations": [{"name": "Unidad", "factor": 1, "price": price, "isBase": True}]}, owner)
        combos_ = [{}]
        for oname, vals in options:
            combos_ = [{**c, oname: v} for c in combos_ for v in vals]
        rows = []
        for values in combos_:
            code_seq[0] += 1
            body = str(770999200000 + code_seq[0])
            check = (10 - sum(int(d) * (1 if i % 2 == 0 else 3) for i, d in enumerate(body)) % 10) % 10
            vprice = price + sum(extra.get(v, 0) for v in values.values())
            rows.append({"values": values, "barcode": body + str(check), "price": vprice, "cost": cost + (vprice - price) // 3,
                         "initialStock": rnd.randint(smin, smax)})
        dto = api.call("PUT", f"/products/{p['id']}/variants", {"options": [{"name": o, "values": v} for o, v in options], "variants": rows}, owner)
        sellable[key] = [(v["presentationId"], v["price"], v["id"]) for v in dto["variants"]]
        for v in dto["variants"]:
            avail[v["id"]] = v["stock"]

    for key, name, cat, brand, price, cost, stock, code in SIMPLE_PRODUCTS:
        p = api.call("POST", "/products", {"name": name, "unit": "UNIDAD", "categoryId": cats[cat], "brand": brand, "cost": cost,
                                           "initialStock": stock, "stockMin": 5, "taxName": "IVA", "taxRate": 19,
                                           "presentations": [{"name": "Unidad", "factor": 1, "barcode": code, "price": price, "isBase": True}]}, owner)
        base = next(x for x in p["presentations"] if x["isBase"])
        sellable[key] = [(base["id"], price, p["id"])]
        avail[p["id"]] = stock

    step("Combos")
    def variant(key, label):
        prod = api.call("GET", f"/products?level=ITEMS&q={urllib.request.quote(label)}", token=owner)["items"]
        return next(x for x in prod if x["name"].endswith(label))
    gorra_negra = variant("gorra", "Gorra bordada · Negro")
    kit = api.call("POST", "/products", {"name": "Kit regalo: gorra + medias", "unit": "UNIDAD", "categoryId": cats["Accesorios"],
                                         "taxName": "IVA", "taxRate": 19, "trackStock": True,
                                         "presentations": [{"name": "Kit", "factor": 1, "barcode": "7709991000042", "price": 49900, "isBase": True}]}, owner)
    api.call("PUT", f"/products/{kit['id']}/components", {"components": [
        {"productId": gorra_negra["id"], "quantity": 1}, {"productId": sellable["medias"][0][2], "quantity": 1},
        {"productId": sellable["bolsa"][0][2], "quantity": 1}]}, owner)
    sellable["kit"] = [(next(x for x in kit["presentations"] if x["isBase"])["id"], 49900, kit["id"])]
    parts[kit["id"]] = [(gorra_negra["id"], 1), (sellable["medias"][0][2], 1), (sellable["bolsa"][0][2], 1)]
    cam_m = variant("camiseta", "Camiseta básica algodón · M / Negro")
    duo = api.call("POST", "/products", {"name": "Dúo camisetas básicas M negra", "unit": "UNIDAD", "categoryId": cats["Camisetas"],
                                         "taxName": "IVA", "taxRate": 19,
                                         "presentations": [{"name": "Dúo", "factor": 1, "price": 69900, "isBase": True}]}, owner)
    api.call("PUT", f"/products/{duo['id']}/components", {"components": [{"productId": cam_m["id"], "quantity": 2}]}, owner)

    step("Relojes con serial y garantía")
    reloj = api.call("POST", "/products", {"name": "Reloj análogo acero", "unit": "UNIDAD", "categoryId": cats["Relojes"], "brand": "Tempo",
                                           "cost": 140000, "trackSerials": True, "warrantyMonths": 12, "stockMin": 2, "taxName": "IVA", "taxRate": 19,
                                           "presentations": [{"name": "Unidad", "factor": 1, "barcode": "7709991000059", "price": 289900, "isBase": True}]}, owner)
    serials = [f"TMP{2600 + i:05d}" for i in range(8)]
    api.call("POST", f"/products/{reloj['id']}/serials", {"serials": serials, "unitCost": 140000, "reason": "Inventario inicial"}, owner)
    reloj_base = next(x for x in reloj["presentations"] if x["isBase"])["id"]

    step(f"Ventas de los últimos {a.dias} días")
    today = datetime.now(COL).date()
    shifts = {u: api.call("POST", "/shifts/open", {"registerId": registers[0], "openingCash": 300000}, tokens[u])["id"] for u in ("dueno",)}
    keys = ["camiseta"] * 6 + ["polo"] * 3 + ["jean"] * 4 + ["vestido"] * 2 + ["tenis"] * 3 + ["gorra"] * 2 + ["medias"] * 3 + ["cinturon", "kit", "kit"]
    customers = ["Laura Gómez", "Sara Mejía", "Juliana Restrepo", "Andrés Toro", None, None]
    sold_serials = 0
    n_sales = 0
    for d in range(a.dias, 0, -1):
        day = today - timedelta(days=d)
        for _ in range(rnd.randint(2, 7) + (3 if day.weekday() >= 4 else 0)):
            lines, total = [], 0
            for _ in range(rnd.choice([1, 1, 2, 2, 3])):
                pres, price, pid = rnd.choice(sellable[rnd.choice(keys)])
                need = parts.get(pid, [(pid, 1)])
                if any(avail.get(c, 0) < q for c, q in need) or any(l["presentationId"] == pres for l in lines):
                    continue
                for c, q in need:
                    avail[c] -= q
                lines.append({"presentationId": pres, "quantity": 1})
                total += price
            if not lines:
                continue
            if sold_serials < 3 and rnd.random() < 0.08:
                lines.append({"presentationId": reloj_base, "quantity": 1, "serials": [serials[sold_serials]]})
                total += 289900
                sold_serials += 1
            at = datetime(day.year, day.month, day.day, rnd.randint(10, 19), rnd.randint(0, 59), tzinfo=COL).astimezone(timezone.utc)
            method = rnd.choice(["EFECTIVO", "TARJETA", "TARJETA", "NEQUI", "BREB"])
            body = {"clientRef": str(uuid.uuid4()), "offline": True, "offlineShiftId": shifts["dueno"],
                    "offlineCreatedAt": at.strftime("%Y-%m-%dT%H:%M:%SZ"), "lines": lines,
                    "payments": [{"method": method, "amount": total, "reference": None if method == "EFECTIVO" else str(rnd.randint(100000, 999999))}]}
            name = rnd.choice(customers)
            if name:
                body["customer"] = {"name": name, "docNumber": str(1000000000 + customers.index(name)), "phone": "310" + str(1000000 + customers.index(name))}
            api.call("POST", "/pos/sales", body, owner)
            n_sales += 1
    for p in api.call("GET", "/payments?status=PENDIENTE", token=owner):
        api.call("POST", f"/payments/{p['id']}/confirm", {"note": "Visto en el extracto"}, owner)
    s = api.call("GET", "/shifts/current", token=owner)
    api.call("POST", f"/shifts/{s['id']}/close", {"countedCash": round(s["expectedCash"]), "closeNotes": "Cuadró"}, owner)
    api.call("POST", "/shifts/open", {"registerId": registers[0], "openingCash": 300000}, tokens["cajera"])
    print(f"  {n_sales} ventas, {sold_serials} relojes vendidos con serial")

    step("Reposición: compra recibida de Confecciones Andinas (por variante)")
    sup = api.call("POST", "/suppliers", {"name": "Confecciones Andinas", "contactName": "Hernán Ospina", "whatsapp": "3204445566",
                                          "creditDays": 30, "leadTimeDays": 5}, owner)["id"]
    lines = []
    for p in api.call("GET", "/products?level=STOCK&status=ACTIVE&size=200", token=owner)["items"]:
        if p["trackSerials"] or p["stock"] >= 3:
            continue
        lines.append({"productId": p["id"], "unitsReceived": 8 - int(p["stock"]), "unitCost": p.get("cost") or 10000})
    if lines:
        api.call("POST", "/receipts", {"supplierId": sup, "invoiceNumber": "CA-1207", "lines": lines}, owner)
    print(f"  {len(lines)} variantes y productos repuestos")

    step("Copia de seguridad \"estado inicial\"")
    api.call("POST", "/backups", None, owner)

    # Báscula en el minimercado de ejemplo (si existe).
    mm = api.call("POST", "/auth/login", {"email": "dueno@tiendademo.test", "password": PASSWORD}, ok_errors=True) or {}
    if "token" in mm:
        step("Báscula en el Minimercado La Esquina: queso y tomate por peso")
        t = mm["token"]
        m = api.call("GET", "/settings/pos/modules", token=t)
        mods = sorted(set(m["enabled"]) | {"BASCULA"})
        api.call("PUT", "/settings/pos/modules", {"businessType": m["businessType"], "modules": mods, "scaleMode": "PESO", "scalePluDigits": 5}, t)
        for name, plu, price, cost, stock in (("Queso campesino", "101", 22000, 15000, 12), ("Tomate chonto", "102", 4800, 2600, 25)):
            existing = api.call("GET", f"/products?q={urllib.request.quote(name)}", token=t)["items"]
            if existing:
                continue
            api.call("POST", "/products", {"name": name, "unit": "KILOGRAMO", "cost": cost, "initialStock": stock, "plu": plu, "stockMin": 3,
                                           "presentations": [{"name": "Kilo", "factor": 1, "price": price, "isBase": True}]}, t)

    print(f"\n✓ Listo. Entra con {mail('dueno')} / {PASSWORD} (PIN 2468) · cajera {mail('cajera')}")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
