"""Связность каталога правил и локальной документации."""
import json
from pathlib import Path
import re
import unittest
from urllib.parse import unquote, urlsplit


ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "docs" / "vulnerabilities"
FIELDS = ("**Поиск:**", "**Уязвимый вариант:**", "**Исправление:**", "**Проверка:**")


class CatalogTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rules = json.loads((ROOT / "rules" / "defense.json").read_text(
            encoding="utf-8"))["rules"]

    def test_rule_links_reach_their_catalog_sections(self):
        for rule in self.rules:
            with self.subTest(rule=rule["id"]):
                path, anchor = rule["doc"].split("#", 1)
                self.assertTrue(path.startswith("docs/vulnerabilities/"))
                source = (ROOT / path).read_text(encoding="utf-8")
                self.assertIn(f'<a id="{anchor}"></a>', source)
                self.assertIn(f"## {rule['id']}. ", source)

    def test_each_catalog_section_has_russian_review_fields(self):
        seen = set()
        for path in CATALOG.glob("*.md"):
            if path.name == "README.md":
                continue
            source = path.read_text(encoding="utf-8")
            sections = re.findall(
                r"^## ([A-Z][A-Z0-9-]+)\. ([\s\S]*?)(?=^<a id=|\Z)", source, re.M)
            self.assertTrue(sections, path)
            for rule_id, body in sections:
                with self.subTest(rule=rule_id):
                    self.assertNotIn(rule_id, seen)
                    seen.add(rule_id)
                    for field in FIELDS:
                        self.assertIn(field, body)

    def test_local_markdown_links_and_anchors_resolve(self):
        for path in self.markdown_paths():
            source = self.without_fences(path.read_text(encoding="utf-8"))
            targets = re.findall(r"\]\(([^)]+)\)", source)
            targets.extend(re.findall(r"^\s*\[[^\]]+\]:\s*(\S+)", source, re.M))
            for raw_target in targets:
                target = raw_target.strip().split(' "', 1)[0].strip("<>")
                parsed = urlsplit(target)
                if parsed.scheme or parsed.netloc:
                    continue
                file_part, anchor = unquote(parsed.path), unquote(parsed.fragment)
                dest = ((ROOT / file_part.lstrip("/")) if file_part.startswith("/")
                        else (path.parent / file_part) if file_part else path).resolve()
                with self.subTest(source=path.relative_to(ROOT), link=target):
                    self.assertTrue(dest.is_relative_to(ROOT))
                    self.assertTrue(dest.is_file())
                    if anchor and dest.suffix.lower() == ".md":
                        self.assertIn(anchor, self.anchors(dest.read_text(encoding="utf-8")))

    def test_retired_operator_entry_points_are_removed(self):
        for relative in (
            "deployment/doctor.py", "integration/stack_smoke.py", "lab/health_service.py",
            "scripts/defense_ops.py", "scripts/common.py",
        ):
            self.assertFalse((ROOT / relative).exists(), relative)

    @staticmethod
    def markdown_paths():
        return [*ROOT.glob("*.md"), * (ROOT / "docs").rglob("*.md")]

    @staticmethod
    def without_fences(source):
        return re.sub(r"(?ms)^(`{3,}|~{3,})[^\n]*\n.*?^\1\s*$", "", source)

    @classmethod
    def anchors(cls, source):
        source = cls.without_fences(source)
        anchors = set(re.findall(r'<(?:a|h[1-6])\s+[^>]*id=[\"\']([^\"\']+)', source))
        occurrences = {}
        for heading in re.findall(r"^#{1,6}\s+(.+?)(?:\s+#+)?$", source, re.M):
            heading = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", heading)
            heading = re.sub(r"<[^>]*>", "", heading).lower()
            slug = re.sub(r"[^\w\- ]", "", heading).replace(" ", "-")
            count = occurrences.get(slug, 0)
            occurrences[slug] = count + 1
            anchors.add(slug if not count else f"{slug}-{count}")
        return anchors


if __name__ == "__main__":
    unittest.main()
