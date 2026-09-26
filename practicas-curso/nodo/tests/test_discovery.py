import os
import sys
import unittest
from unittest.mock import patch

SRC = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
sys.path.insert(0, SRC)

import discovery


class DiscoveryTests(unittest.TestCase):
    def test_persiste_y_conecta_un_nodo_nuevo(self):
        llamadas = []
        with patch.object(discovery, "_persistir") as persistir:
            discovery.configurar(
                "connections.json",
                "127.0.0.1:8070",
                lambda ip, puerto: llamadas.append((ip, puerto)),
            )
            self.assertTrue(discovery.descubrir("192.0.2.10:8071"))
            self.assertFalse(discovery.descubrir("192.0.2.10:8071"))

        persistir.assert_called_once_with()
        self.assertEqual(["192.0.2.10:8071"], discovery.conocidos())
        self.assertEqual([("192.0.2.10", "8071")], llamadas)

    def test_no_registra_endpoint_propio(self):
        with patch.object(discovery, "_persistir"):
            discovery.configurar(
                "connections.json",
                "127.0.0.1:8070",
                lambda ip, puerto: None,
            )
            self.assertFalse(discovery.descubrir("localhost:8070"))
            self.assertEqual([], discovery.conocidos())


if __name__ == "__main__":
    unittest.main()
