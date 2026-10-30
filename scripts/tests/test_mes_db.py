import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location("mes_db", Path(__file__).parents[1] / "db/mes_db.py")
db = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(db)


class DatabaseSafetyTests(unittest.TestCase):
    def setUp(self):
        self.expected = {"sp_bom": {"engine": "InnoDB", "collation": "utf8mb4_0900_ai_ci", "columns": {"id": ["varchar(64)", "NO", None, "utf8mb4_0900_ai_ci", ""]},
                                     "indexes": [["PRIMARY", "0", "1", "id", None, "BTREE"]], "checks": {}}}

    def test_wrong_field_type_cannot_be_baselined(self):
        import copy
        actual = copy.deepcopy(self.expected)
        actual["sp_bom"]["columns"]["id"][0] = "int"
        self.assertTrue(db.contract_errors(actual, self.expected))

    def test_missing_unique_index_cannot_be_baselined(self):
        import copy
        actual = copy.deepcopy(self.expected)
        actual["sp_bom"]["indexes"] = []
        self.assertTrue(db.contract_errors(actual, self.expected))

    def test_missing_check_constraint_cannot_be_baselined(self):
        self.expected["sp_bom"]["checks"] = {"positive": "(`id` > 0)"}
        actual = {"sp_bom": dict(self.expected["sp_bom"], checks={})}
        self.assertTrue(db.contract_errors(actual, self.expected))

    def test_valid_contract_passes(self):
        self.assertEqual([], db.contract_errors(self.expected, self.expected))

    def test_wrong_table_default_collation_cannot_be_baselined(self):
        import copy
        actual = copy.deepcopy(self.expected)
        actual["sp_bom"]["collation"] = "utf8mb4_general_ci"
        self.assertTrue(db.contract_errors(actual, self.expected))

    def test_only_named_local_development_targets_are_allowed(self):
        for port, name in [(3306, "mes_study"), (3308, "mes_flyway_it_" + "a" * 32)]:
            db.validate_target(port, name)
        for port, name in [(3307, "mes_study"), (3306, "production"), (3306, "mes_study;DROP DATABASE x")]:
            with self.assertRaises(ValueError):
                db.validate_target(port, name)


if __name__ == "__main__":
    unittest.main()
