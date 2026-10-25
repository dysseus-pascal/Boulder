#!/usr/bin/env python3
"""Boulder: Waechter fuer die Naehe zu Core (der Vorlage).

Aufruf: vorlage_waechter.py [<repo>]

Vergleicht den Arbeitsbaum (auch Uncommittetes) mit dem Stand von Core, den tools/boulder/vorlage.txt
nennt, und prueft jede Datei, die es bei Core gibt, gegen ihren Eintrag dort:
  - eine Zahl: so viele geaenderte Zeilen (eingefuegte plus entfernte) hat die Datei genau;
  - "binaer": ein ersetztes Bild oder eine andere Datei ohne Zeilen;
  - "geloescht": Boulder hat die Datei entfernt;
  - "eigen": Boulder fuehrt die Datei als eigenes Dokument (README, CI), Zeilen zaehlen nicht.
Rot (Rueckgabe 1), wenn eine Core-Datei geaendert oder geloescht ist, die die Liste nicht nennt,
wenn sie mehr oder weniger Zeilen aendert als ihr Eintrag, oder wenn ein Eintrag nicht mehr
zutrifft. Weniger zaehlt auch: dann sinkt der Eintrag im selben Commit, damit die Liste genau
festhaelt, wie weit Boulder von Core abweicht, und ein spaeteres Wachsen im Diff auffaellt.
Neue Dateien sind Boulders eigene und frei.
"""
import pathlib
import subprocess
import sys

LISTE = pathlib.Path('tools/boulder/vorlage.txt')
WOERTER = ('binaer', 'geloescht', 'eigen')


def git(repo, *argumente):
    return subprocess.run(['git', '-C', str(repo), *argumente], check=True, capture_output=True,
                          text=True).stdout


def lies_liste(text):
    """(Core-Stand, {Pfad: Eintrag}) aus vorlage.txt; Eintrag ist eine Zahl oder ein Wort."""
    stand = None
    eintraege = {}
    for nummer, zeile in enumerate(text.splitlines(), 1):
        zeile = zeile.strip()
        if not zeile or zeile.startswith('#'):
            continue
        teile = zeile.split()
        if teile[0] == 'core' and len(teile) == 2:
            stand = teile[1]
            continue
        if len(teile) != 2:
            raise ValueError(f'Zeile {nummer}: "Pfad Eintrag" erwartet: {zeile}')
        pfad, wert = teile
        if wert not in WOERTER and not wert.isdigit():
            raise ValueError(f'Zeile {nummer}: {wert} ist weder Zahl noch {", ".join(WOERTER)}')
        if pfad in eintraege:
            raise ValueError(f'Zeile {nummer}: {pfad} steht doppelt')
        eintraege[pfad] = int(wert) if wert.isdigit() else wert
    if stand is None:
        raise ValueError('die Zeile "core <commit>" fehlt')
    return stand, eintraege


def abweichungen(numstat, status, core_dateien):
    """{Pfad: Zahl | 'binaer' | 'geloescht'} fuer die geaenderten Core-Dateien."""
    geloescht = {z.split('\t', 1)[1] for z in status.splitlines() if z.startswith('D\t')}
    ergebnis = {}
    for zeile in numstat.splitlines():
        dazu, weg, pfad = zeile.split('\t', 2)
        if pfad not in core_dateien:
            continue
        if pfad in geloescht:
            ergebnis[pfad] = 'geloescht'
        elif dazu == '-':
            ergebnis[pfad] = 'binaer'
        else:
            ergebnis[pfad] = int(dazu) + int(weg)
    return ergebnis


def pruefe(ist, eintraege):
    """Die Befunde als Text; leer heisst gruen."""
    befunde = []
    for pfad, wert in sorted(ist.items()):
        soll = eintraege.get(pfad)
        if soll is None:
            befunde.append(f'{pfad}: Core-Datei geaendert ({wert}), steht nicht in {LISTE}. '
                           'Boulder-Code gehoert in eigene Dateien, hier nur ein Haken.')
        elif soll == 'eigen':
            continue
        elif soll != wert:
            befunde.append(f'{pfad}: {wert} statt {soll}' +
                           (' - Eintrag senken' if isinstance(wert, int) and isinstance(soll, int)
                            and wert < soll else ''))
    for pfad, soll in sorted(eintraege.items()):
        if pfad not in ist and soll != 'eigen':
            befunde.append(f'{pfad}: wieder wie bei Core - Eintrag streichen')
    return befunde


def main(argumente):
    repo = pathlib.Path(argumente[1] if len(argumente) > 1 else '.')
    stand, eintraege = lies_liste((repo / LISTE).read_text())
    core_dateien = set(git(repo, 'ls-tree', '-r', '--name-only', stand).splitlines())
    ist = abweichungen(git(repo, 'diff', '--numstat', '--no-renames', stand),
                       git(repo, 'diff', '--name-status', '--no-renames', stand), core_dateien)
    befunde = pruefe(ist, eintraege)
    for befund in befunde:
        print(befund)
    zeilen = sum(w for p, w in ist.items() if isinstance(w, int) and eintraege.get(p) != 'eigen')
    print(f'{len(ist)} Core-Dateien weichen ab, {zeilen} gezaehlte Zeilen; {"rot" if befunde else "gruen"}')
    return 1 if befunde else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
