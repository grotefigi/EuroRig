"""Check Romanian UI resource coverage and format placeholders."""
from collections import Counter
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
SAFETY_FALLBACKS = set()


def strings(path):
    path = Path(path)
    files = sorted(path.glob('*.xml')) if path.is_dir() else [path]
    result = {}
    for resource_file in files:
        root = ET.parse(resource_file).getroot()
        entries = root.findall('string')
        for entry in entries:
            name = entry.attrib['name']
            if name in result:
                raise ValueError(f'Duplicate string name {name} in {path}')
            result[name] = ''.join(entry.itertext())
        for plural in root.findall('plurals'):
            name = plural.attrib['name']
            if name in result:
                raise ValueError(f'Duplicate resource name {name} in {path}')
            result[name] = {item.attrib['quantity']: ''.join(item.itertext()) for item in plural.findall('item')}
    return result


class LocalizationCoverage(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.english = strings(ROOT / 'app/src/main/res/values')
        cls.romanian = strings(ROOT / 'app/src/main/res/values-ro')

    def test_only_reviewed_safety_strings_fall_back_to_english(self):
        self.assertEqual(set(self.english) - set(self.romanian), SAFETY_FALLBACKS)
        self.assertEqual(set(self.romanian) - set(self.english), set())

    def test_translated_format_placeholders_match(self):
        pattern = re.compile(r'%(?:\d+\$)?[a-zA-Z]')
        for name in set(self.english) & set(self.romanian):
            with self.subTest(name=name):
                english, romanian = self.english[name], self.romanian[name]
                if isinstance(english, dict):
                    expected = Counter(pattern.findall(english['other']))
                    for quantity, value in romanian.items():
                        with self.subTest(quantity=quantity):
                            self.assertEqual(expected, Counter(pattern.findall(value)))
                else:
                    self.assertEqual(Counter(pattern.findall(english)), Counter(pattern.findall(romanian)))


if __name__ == '__main__':
    unittest.main()
