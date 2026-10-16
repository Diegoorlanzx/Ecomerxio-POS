"""Fase 9: publica la tienda en línea del "Minimercado La Esquina" (tienda de ejemplo) con domicilios y pedidos de muestra.

Requiere que la tienda de ejemplo exista (scripts/crear_tienda_demo.py). Todo por la API pública, como lo haría una persona.

Uso:
    python scripts/activar_tienda_online_demo.py                 # contra http://localhost:8090
    python scripts/activar_tienda_online_demo.py --url https://app.midominio.com

Deja: módulo Tienda en línea activo, dirección /t/la-esquina, horario 7 a. m. a 10 p. m., 4 barrios con su domicilio,
pago contra entrega / Nequi / Bre-B, los cigarrillos y licores ocultos en línea, dos precios especiales en línea y
4 pedidos de ejemplo: uno nuevo, uno en preparación, uno en camino y uno ya entregado y cobrado.
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request

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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://localhost:8090")
    ap.add_argument("--dominio", default="tiendademo.test")
    a = ap.parse_args()
    api = Api(a.url)
    step = lambda s: print(f"• {s}", flush=True)

    login = api.call("POST", "/auth/login", {"email": f"dueno@{a.dominio}", "password": PASSWORD}, ok_errors=True) or {}
    if "token" not in login:
        raise SystemExit("No existe la tienda de ejemplo. Créala primero con: python scripts/crear_tienda_demo.py")
    t = login["token"]
    cajera = api.call("POST", "/auth/login", {"email": f"cajera@{a.dominio}", "password": PASSWORD})["token"]

    step("Activando el módulo Tienda en línea")
    m = api.call("GET", "/settings/pos/modules", token=t)
    api.call("PUT", "/settings/pos/modules", {"businessType": m["businessType"], "modules": sorted(set(m["enabled"]) | {"TIENDA_ONLINE"})}, t)

    step("Configurando la vitrina /t/la-esquina")
    schedule = [{"day": d, "open": "07:00", "close": "22:00", "closed": False} for d in range(1, 8)]
    api.call("PUT", "/online-store", {
        "enabled": True, "slug": "la-esquina", "mode": "PEDIDOS",
        "welcome": "Tu tienda de barrio a un clic: domicilio en 30 minutos en Prado, Boston, Villa Hermosa y Buenos Aires.",
        "whatsapp": "3001234567", "showStock": False, "deliveryEnabled": True, "pickupEnabled": True, "minOrder": 8000,
        "payMethods": ["CONTRA_ENTREGA", "NEQUI", "BREB"], "schedule": schedule, "paused": False, "prepMinutes": 25, "showBadge": True,
        "zones": [{"name": "Prado", "fee": 0, "minOrder": 10000, "active": True},
                  {"name": "Boston", "fee": 2500, "minOrder": 15000, "active": True},
                  {"name": "Villa Hermosa", "fee": 3500, "minOrder": 20000, "active": True},
                  {"name": "Buenos Aires", "fee": 4000, "minOrder": 20000, "active": True}]}, t)

    step("Qué se publica: sin cigarrillos ni licores, y precios especiales en línea")
    items = api.call("GET", "/online-store/products", token=t)
    changes = []
    for i in items:
        cat = (i.get("categoryName") or "").lower()
        if any(w in cat for w in ("cigarr", "licor")):
            changes.append({"productId": i["id"], "visible": False})
    by_name = {i["name"]: i for i in items}
    for name, price in (("Gaseosa Coca-Cola 1.5 L", 5800), ("Papas Margarita pollo 115 g", 4200)):
        hit = next((i for n, i in by_name.items() if n.lower().startswith(name.lower()[:14])), None)
        if hit:
            changes.append({"productId": hit["id"], "onlinePrice": price})
    if changes:
        api.call("PUT", "/online-store/products", changes, t)

    step("Pedidos de ejemplo")
    store = api.call("GET", "/public/stores/la-esquina")
    zones = {z["name"]: z["id"] for z in store["zones"]}
    simple = [p for p in store["products"] if p["productType"] in ("SIMPLE", "COMBO") and p["available"] and p["presentations"]]
    pick = lambda *words: next((p for p in simple if all(w in p["name"].lower() for w in words)), simple[0])
    orders = [
        ("Marcela Quintero", "3014567788", "Boston", "Carrera 39 # 52-18 apto 301", "NEQUI", [(pick("coca"), 2), (pick("pan"), 1), (pick("huevo"), 1)], "Por favor traer cambio de 50"),
        ("Jorge Iván Patiño", "3127778899", "Prado", "Calle 58 # 49-20", "CONTRA_ENTREGA", [(pick("leche"), 3), (pick("arroz"), 2)], None),
        ("Luz Dary Cardona", "3205551212", "Villa Hermosa", "Calle 65 # 36-40", "CONTRA_ENTREGA", [(pick("aguila"), 6), (pick("papas"), 3)], "Tocar dos veces"),
        ("Esteban Gil", "3009990011", None, None, "BREB", [(pick("aceite"), 1), (pick("azucar"), 1)], None),
    ]
    placed = []
    for name, phone, zone, address, pay, lines, notes in orders:
        body = {"name": name, "phone": phone, "fulfillment": "DOMICILIO" if zone else "RECOGER", "zoneId": zones.get(zone),
                "address": address, "notes": notes, "payMethod": pay,
                "items": [{"presentationId": p["presentations"][0]["id"], "quantity": q} for p, q in lines]}
        r = api.call("POST", "/public/stores/la-esquina/orders", body, ok_errors=True)
        if "_error" in r:
            print(f"  (no se pudo crear el pedido de {name}: {r['_message'][:120]})")
            continue
        placed.append(r)
    own = api.call("GET", "/online-orders", token=cajera)
    by_number = {o["number"]: o for o in own}
    if len(placed) >= 4:
        # 2: en preparación · 3: en camino · 4: entregado y cobrado (recoge en tienda)
        o2, o3, o4 = (by_number[p["number"]] for p in placed[1:4])
        api.call("POST", f"/online-orders/{o2['id']}/accept", {}, cajera)
        api.call("POST", f"/online-orders/{o2['id']}/status/PREPARANDO", {}, cajera)
        api.call("POST", f"/online-orders/{o3['id']}/accept", {}, cajera)
        api.call("POST", f"/online-orders/{o3['id']}/status/EN_CAMINO", {}, cajera)
        api.call("POST", f"/online-orders/{o4['id']}/accept", {}, cajera)
        api.call("POST", f"/online-orders/{o4['id']}/status/LISTO", {}, cajera)
        r = api.call("POST", f"/online-orders/{o4['id']}/deliver", {"payments": [{"method": "BREB", "amount": o4["total"], "reference": "BRB-77120"}]}, cajera, ok_errors=True)
        if "_error" in r:
            print(f"  (el pedido #{o4['number']} quedó listo sin cobrar: {r['_message'][:120]})")
    print(f"\n✓ Listo. Tienda en línea: {a.url.replace(':8090', ':5190')}/t/la-esquina · pedidos en Más → Pedidos en línea")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
