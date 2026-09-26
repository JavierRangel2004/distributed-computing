import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

import content_log
import discovery
import peers
from logger import registrar_evento


def crear_servidor(host, port, node_id, node_port):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            partes = urlsplit(self.path)
            ruta = partes.path.rstrip("/")
            if ruta not in ("", "/health", "/topology", "/content"):
                self.send_error(404)
                return
            if ruta == "/health":
                payload = {"status": "ok", "node_id": node_id}
            elif ruta == "/content":
                try:
                    desde = int(parse_qs(partes.query).get("since", ["0"])[0])
                except ValueError:
                    desde = 0
                payload = content_log.desde(desde)
                payload["node_id"] = node_id
            else:
                payload = {
                    "node": {
                        "node_id": node_id, "role": "node",
                        "port": node_port, "api_port": port,
                    },
                    "active_connections": peers.topologia(),
                    "known_nodes": discovery.conocidos(),
                }
            body = json.dumps(payload, ensure_ascii=False, indent=2).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, formato, *args):
            return

    return ThreadingHTTPServer((host, port), Handler)


def iniciar_api(host, port, node_id, node_port):
    servidor = crear_servidor(host, port, node_id, node_port)
    registrar_evento(f"API TOPOLOGÍA -> http://{host}:{port}/topology")
    servidor.serve_forever()
