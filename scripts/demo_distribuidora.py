"""
Crea (o completa) una distribuidora de prueba para mostrar la Fase 12: listas de precios con volumen, una ruta que se
visita hoy con 5 tiendas, y la caja abierta para recibir el recaudo al cerrar la ruta.

Uso:
    python -I scripts/demo_distribuidora.py [URL_API] [CORREO] [CONTRASEÑA]
Si la tienda no existe la registra con esos datos (la cuenta de prueba está en docs/DEV.md).
Se puede correr varias veces: lo que ya existe no se repite.
"""
import datetime
import json
import sys
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8090/api"
EMAIL = sys.argv[2] if len(sys.argv) > 2 else "distribucion@demo.tiendaposs.test"
PASSWORD = sys.argv[3] if len(sys.argv) > 3 else None
TOKEN = None


def call(method, path, body=None):
    headers = {"Content-Type": "application/json"}
    if TOKEN:
        headers["Authorization"] = "Bearer " + TOKEN
    req = urllib.request.Request(BASE + path, method=method, headers=headers,
                                 data=json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors="replace")[:300]


def main():
    global TOKEN
    if not PASSWORD:
        sys.exit("Falta la contraseña (está en docs/DEV.md)")
    st, r = call("POST", "/auth/login", {"email": EMAIL, "password": PASSWORD})
    if st != 200:
        st, r = call("POST", "/auth/register", {"storeName": "Distribuidora La Central (prueba)", "ownerName": "Dueño La Central",
                                                "email": EMAIL, "password": PASSWORD, "businessType": "DISTRIBUIDORA", "plan": "PRO",
                                                "acceptTerms": True, "acceptPrivacy": True})
        if st not in (200, 201):
            sys.exit(f"No se pudo registrar: {st} {r}")
        print("- Distribuidora registrada")
    TOKEN = r["token"]
    call("PUT", "/settings/pos/modules", {"businessType": "DISTRIBUIDORA", "modules": ["DISTRIBUCION", "FIADO", "COMPRAS", "LOTES"]})

    existing = {p["name"]: p for p in call("GET", "/products?size=200")[1]["items"]}

    def product(name, price, cost, stock):
        if name in existing:
            return existing[name]
        st, p = call("POST", "/products", {"name": name, "unit": "UNIDAD", "cost": cost, "initialStock": stock, "taxRate": 0,
                                           "presentations": [{"name": "Unidad", "factor": 1, "price": price, "isBase": True}]})
        existing[name] = p
        return p

    arroz = product("Arroz Diana 500 g", 4000, 2600, 400)
    aceite = product("Aceite Premier 1 L", 10500, 7200, 120)
    azucar = product("Azúcar Manuelita 1 kg", 5200, 3800, 200)
    gaseosa = product("Gaseosa Postobón 1.5 L", 4500, 2900, 300)
    product("Café Sello Rojo 250 g", 9800, 6900, 80)

    def pres(p):
        return call("GET", f"/products/{p['id']}")[1]["presentations"][0]["id"]

    lists = {l["name"]: l["id"] for l in call("GET", "/distribution/price-lists")[1]}
    if "Tiendas" not in lists:
        lists["Tiendas"] = call("POST", "/distribution/price-lists", {"name": "Tiendas", "discountPct": 3, "items": [
            {"presentationId": pres(arroz), "minQty": 12, "price": 3700},
            {"presentationId": pres(arroz), "minQty": 24, "price": 3550},
            {"presentationId": pres(gaseosa), "minQty": 12, "price": 4100}]})[1]["id"]
    if "Mayoristas" not in lists:
        lists["Mayoristas"] = call("POST", "/distribution/price-lists", {"name": "Mayoristas", "discountPct": 8, "items": [
            {"presentationId": pres(arroz), "minQty": 50, "price": 3400},
            {"presentationId": pres(aceite), "minQty": 24, "price": 9300}]})[1]["id"]
    print("- Listas de precios: Tiendas y Mayoristas")

    me = call("GET", "/auth/me")[1]["id"]
    today = datetime.date.today().isoweekday()
    routes = {x["name"]: x["id"] for x in call("GET", "/distribution/routes")[1]}
    if "Ruta Norte" not in routes:
        routes["Ruta Norte"] = call("POST", "/distribution/routes", {"name": "Ruta Norte", "sellerUserId": me,
                                                                    "visitDays": sorted({today, (today % 7) + 3 if (today % 7) + 3 <= 7 else 1}),
                                                                    "commissionPct": 2})[1]["id"]
    if "Ruta Sur" not in routes:
        routes["Ruta Sur"] = call("POST", "/distribution/routes", {"name": "Ruta Sur", "sellerUserId": me,
                                                                  "visitDays": [((today) % 7) + 1], "commissionPct": 2})[1]["id"]

    have = {c["name"] for c in call("GET", "/distribution/customers")[1]}
    for i, (name, addr, route, lst) in enumerate([
        ("Tienda Doña Rosa", "Cra 45 # 80-12", "Ruta Norte", "Tiendas"),
        ("Minimercado El Ahorro", "Cl 82 # 47-30", "Ruta Norte", "Tiendas"),
        ("Granero La Abundancia", "Cra 50 # 85-05", "Ruta Norte", "Mayoristas"),
        ("Cigarrería Los Amigos", "Cl 88 # 52-14", "Ruta Norte", "Tiendas"),
        ("Supertienda La 70", "Av 70 # 33-21", "Ruta Sur", "Mayoristas"),
    ]):
        if name in have:
            continue
        st, c = call("POST", "/pos/customers", {"name": name, "address": addr, "phone": f"30155500{i}{i}"})
        call("PUT", f"/distribution/customers/{c['id']}", {"routeId": routes[route], "priceListId": lists[lst], "routeOrder": i + 1})
    print("- Rutas Norte (hoy) y Sur con 5 clientes")

    if not call("GET", "/shifts/current")[1]:
        reg = call("GET", "/registers")[1][0]["id"]
        call("POST", "/shifts/open", {"registerId": reg, "openingCash": 50000})
        print("- Caja abierta con $50.000")
    print("Listo: entra con", EMAIL)


if __name__ == "__main__":
    main()
