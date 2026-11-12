import unittest

from coding_remote.verification import gateway_binding_checks


class GatewayBindingTests(unittest.TestCase):
    def test_occupied_port_recovers_without_routing_to_existing_service(self):
        result=gateway_binding_checks()
        self.assertTrue(all(result.values()))
