import json
import os
import sys
import threading
import unittest
import urllib.request

SRC = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
sys.path.insert(0, SRC)

import topology_api


class TopologyApiTests(unittest.TestCase):
    def test_expone_topologia_json_y_cors(self):
        servidor = topology_api.crear_servidor("127.0.0.1", 0, "NODO_TEST", 8070)
        hilo = threading.Thread(target=servidor.serve_forever)
        hilo.start()
        try:
            port = servidor.server_address[1]
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/topology") as respuesta:
                payload = json.load(respuesta)
                self.assertEqual("*", respuesta.headers["Access-Control-Allow-Origin"])
            self.assertEqual("NODO_TEST", payload["node"]["node_id"])
            self.assertIn("active_connections", payload)
            self.assertIn("known_nodes", payload)
        finally:
            servidor.shutdown()
            servidor.server_close()
            hilo.join(2)


if __name__ == "__main__":
    unittest.main()
