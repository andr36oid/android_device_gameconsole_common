#!/usr/bin/env python3
"""Manifest contract for Daijishou 1.5.0's player-package preflight.

DaijishouLibraryModel.play$playPlayableItem checks packages from
queryIntentActivities(MAIN + LAUNCHER, 0), not getPackageInfo. Exercise the
shipped player arguments against the manifests with the runtime ES entry enabled
(es_icon=1 and ready), on both fresh and kept-/data installs.
This is host contract coverage; PackageManager and actual launches need hardware.
"""
from pathlib import Path
import re
import sqlite3
import unittest
import xml.etree.ElementTree as ET


COMMON = Path(__file__).resolve().parents[2]
ANDROID = '{http://schemas.android.com/apk/res/android}'
BRIDGE = 'org.andr36oid.subsystem'
SETUP = 'org.andr36oid.subsystemsetup'
ES = '.EmulationStationLauncher'


def components(manifest, disabled=(), runtime_enabled=()):
    """Enabled components and MAIN/LAUNCHER matches for these two manifests."""
    app = manifest.find('application')
    enabled = {}
    launchers = []
    if app.get(ANDROID + 'enabled', 'true') == 'false':
        return enabled, launchers
    for node in app:
        if node.tag not in ('activity', 'activity-alias'):
            continue
        name = node.get(ANDROID + 'name')
        if name in disabled or (name not in runtime_enabled and node.get(ANDROID + 'enabled', 'true') == 'false'):
            continue
        if node.tag == 'activity-alias':
            # Android requires the target activity to precede its alias.
            assert node.get(ANDROID + 'targetActivity') in enabled
        enabled[name] = node
        if node.get(ANDROID + 'exported') != 'true':
            continue
        for intent in node.findall('intent-filter'):
            actions = {x.get(ANDROID + 'name') for x in intent.findall('action')}
            categories = {x.get(ANDROID + 'name') for x in intent.findall('category')}
            if ('android.intent.action.MAIN' in actions and
                    'android.intent.category.LAUNCHER' in categories):
                launchers.append(name)
    return enabled, launchers


class PlayerLaunchersTest(unittest.TestCase):
    def setUp(self):
        self.manifests = {
            BRIDGE: ET.parse(COMMON / 'PortMaster/subsystem/SubsystemBridge/AndroidManifest.xml').getroot(),
            SETUP: ET.parse(COMMON / 'SubsystemSetup/AndroidManifest.xml').getroot(),
        }
        # Evaluate the actual SQL expressions, including char(10) in arguments.
        self.players = []
        with sqlite3.connect(':memory:') as db:
            db.execute('CREATE TABLE player (uid, player, name, description, args, kill, warning, regex, extra)')
            for line in (COMMON / 'emulators/daijishou/daijishou-seed.txt').read_text().splitlines():
                fields = line.split('\t', 3)
                if fields[0] != 'player' or '.andr36oid.' not in fields[3]:
                    continue
                db.execute('DELETE FROM player')
                db.execute('INSERT INTO player VALUES (' + fields[3] + ')')
                uid, args = db.execute('SELECT uid, args FROM player').fetchone()
                match = re.search(r'-n\s+(org\.andr36oid\.[^/\s]+)/([^\s]+)', args)
                if match:
                    self.players.append((uid, *match.groups()))

    def test_fresh_and_kept_data_players(self):
        self.assertTrue(any(uid == 'ports.andr36oid.subsystem' for uid, _, _ in self.players))
        self.assertTrue(any(uid.endswith('.andr36oid.darkosen') for uid, _, _ in self.players))
        for kept_data in (False, True):
            # BootReceiver / dashboard restore the alias from ready + es_icon.
            # Old aliases removed from the APK cannot contribute launcher matches.
            for uid, package, activity in self.players:
                with self.subTest(player=uid, kept_data=kept_data):
                    self.assertEqual(package, SETUP)
                    enabled, launchers = components(self.manifests[package], runtime_enabled={ES})
                    self.assertEqual(launchers, [ES])
                    self.assertEqual(enabled[activity].get(ANDROID + 'exported'), 'true')
                    self.assertEqual(activity, '.LaunchPortActivity' if uid == 'ports.andr36oid.subsystem' else '.RetroArchActivity')

    def test_only_es_can_appear_in_the_launcher(self):
        for package, manifest in self.manifests.items():
            _, launchers = components(manifest, runtime_enabled={ES})
            self.assertEqual(launchers, [ES] if package == SETUP else [])
            names = {n.get(ANDROID + 'name') for n in manifest.find('application')}
            self.assertNotIn('.SubsystemSetupLauncher', names)
            self.assertNotIn('.PortMasterLauncher', names)

    def test_disabling_es_documents_the_package_preflight_limit(self):
        # With MAIN/LAUNCHER as the app list AND preflight, hiding the last entry
        # also removes both players' package match. No hidden launcher workaround.
        enabled, launchers = components(self.manifests[SETUP], disabled={ES})
        self.assertIn('.RetroArchActivity', enabled)
        self.assertIn('.LaunchPortActivity', enabled)
        self.assertEqual(launchers, [])

    def test_headless_bridge_remains_an_exported_transition_endpoint(self):
        enabled, launchers = components(self.manifests[BRIDGE])
        self.assertEqual(enabled['.LaunchPortActivity'].get(ANDROID + 'exported'), 'true')
        self.assertEqual(launchers, [])


if __name__ == '__main__':
    unittest.main()
