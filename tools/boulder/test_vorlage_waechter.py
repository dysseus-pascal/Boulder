#!/usr/bin/env python3
"""Tests fuer vorlage_waechter.py: Liste lesen, Abweichungen zaehlen, Befunde."""
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import vorlage_waechter as w  # noqa: E402


class ListeLesen(unittest.TestCase):
    def test_stand_zahlen_und_woerter(self):
        stand, eintraege = w.lies_liste('# Kopf\ncore abc\n\na.kt 12\nb.png binaer\nc.kt geloescht\nREADME.md eigen\n')
        self.assertEqual('abc', stand)
        self.assertEqual({'a.kt': 12, 'b.png': 'binaer', 'c.kt': 'geloescht', 'README.md': 'eigen'}, eintraege)

    def test_ohne_stand_doppelt_oder_unbekannt_ist_ein_fehler(self):
        for text in ('a.kt 1\n', 'core abc\na.kt 1\na.kt 2\n', 'core abc\na.kt viel\n', 'core abc\na.kt\n'):
            with self.subTest(text=text), self.assertRaises(ValueError):
                w.lies_liste(text)


class Abweichungen(unittest.TestCase):
    def test_nur_core_dateien_zaehlen(self):
        numstat = '3\t2\ta.kt\n-\t-\tbild.png\n0\t40\tweg.kt\n50\t0\tneu.kt\n'
        status = 'M\ta.kt\nM\tbild.png\nD\tweg.kt\nA\tneu.kt\n'
        ist = w.abweichungen(numstat, status, {'a.kt', 'bild.png', 'weg.kt'})
        self.assertEqual({'a.kt': 5, 'bild.png': 'binaer', 'weg.kt': 'geloescht'}, ist)


class Pruefen(unittest.TestCase):
    def test_genau_wie_eingetragen_ist_gruen(self):
        self.assertEqual([], w.pruefe({'a.kt': 5, 'README.md': 300}, {'a.kt': 5, 'README.md': 'eigen'}))

    def test_mehr_ist_rot(self):
        self.assertEqual(['a.kt: 6 statt 5'], w.pruefe({'a.kt': 6}, {'a.kt': 5}))

    def test_weniger_verlangt_einen_kleineren_eintrag(self):
        self.assertEqual(['a.kt: 4 statt 5 - Eintrag senken'], w.pruefe({'a.kt': 4}, {'a.kt': 5}))

    def test_eine_neue_core_datei_ist_rot(self):
        befunde = w.pruefe({'b.kt': 2}, {})
        self.assertEqual(1, len(befunde))
        self.assertTrue(befunde[0].startswith('b.kt: Core-Datei geaendert (2)'))

    def test_eine_wieder_unveraenderte_datei_verlangt_das_streichen(self):
        self.assertEqual(['a.kt: wieder wie bei Core - Eintrag streichen'], w.pruefe({}, {'a.kt': 5}))

    def test_ein_anderer_zustand_ist_rot(self):
        self.assertEqual(['a.kt: geloescht statt 5'], w.pruefe({'a.kt': 'geloescht'}, {'a.kt': 5}))


if __name__ == '__main__':
    unittest.main()
