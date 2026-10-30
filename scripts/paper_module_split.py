#!/usr/bin/env python3
"""Validate managed commands and new module behavior on an isolated Paper test server only."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time
from paper_validation import Rcon


def exercise(server, fixture_file):
    repo = Path(__file__).resolve().parent.parent
    hot = server / 'plugins/QuantumPlugin/hot'
    fixtures = json.loads(fixture_file.read_text())
    initial = json.loads((hot / 'status.json').read_text())
    report = {'checks': [], 'instanceId': initial['instanceId']}

    def check(name, passed):
        if not passed: raise AssertionError(name)
        report['checks'].append(name)
        (server / 'module-split-validation.json').write_text(json.dumps(report, indent=2, ensure_ascii=False))
        print('PASS ' + name, flush=True)

    def deploy(version, code=0):
        result = subprocess.run([sys.executable, str(repo / 'scripts/deploy-hot.py'), '--release', fixtures[version],
            '--hot-dir', str(hot)], capture_output=True, text=True, timeout=70)
        check('deploy ' + version, result.returncode == code)
        return json.loads(result.stdout)

    with Rcon(server) as client:
        client.probe('spawn QHotTester')
        deploy('v1')
        client.probe('track')
        first = client.probe('module-command QHotTester damageindicator enable')
        check('v1 command implementation loaded from new child module', first['indicator'] and '[V1]' in ''.join(first['messages']))
        check('managed command completion active', client.probe('module-tab QHotTester damageindicator d')['suggestions'] == ['disable'])
        check('one hit event produces one chat and one actionbar', client.probe('combat-event QHotTester')['messages'] == ['Hit!', 'Hit!'])
        client.probe('text-target QHotTester')
        client.probe('module-command QHotTester summontext First')
        check('v1 text command implementation active', 'V1:First' in client.probe('text-count')['texts'])
        for command in ['firework launch', 'newyeartnt give', 'newyeardumplings']:
            result = client.probe('module-command QHotTester ' + command)
            check('activity window preserved: ' + command, result['messages'].count('该活动还未开始。') == 1)

        deploy('v2')
        client.probe('track')
        check('player preference survives hot replacement', client.probe('module-command QHotTester damageindicator query')['indicator'])
        second = client.probe('module-command QHotTester damageindicator enable')
        check('v2 command replaced v1 without duplicate execution', len(second['messages']) == 1 and '[V2]' in second['messages'][0])
        client.probe('module-command QHotTester summontext Second')
        texts = client.probe('text-count')['texts']
        check('text displays persist while command code changes', 'V1:First' in texts and 'V2:Second' in texts)
        broken = deploy('broken', 1)
        check('health failure rolls back the entire candidate', broken['code'] == 'ROLLED_BACK')
        restored = client.probe('module-command QHotTester damageindicator enable')
        check('failed activation restores old command handler', len(restored['messages']) == 1 and '[V2]' in restored['messages'][0])
        client.command('qhot rollback')
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            status = json.loads((hot / 'status.json').read_text())
            if status['activeRelease'].endswith('-v1') and status['phase'] == 'ready': break
            time.sleep(.1)
        result = client.probe('module-command QHotTester damageindicator enable')
        check('manual rollback restores v1 command implementation', '[V1]' in ''.join(result['messages']))
        check('module events are not duplicated after rollback', client.probe('combat-event QHotTester')['messages'] == ['Hit!', 'Hit!'])
        check('game server process unchanged throughout updates', status['instanceId'] == initial['instanceId'])
        check('all five modules remain active', len(status['modules']) == 5 and not status['restartRequired'])
        # Rollback loads a fresh generation; track it before counting surviving loader references.
        client.probe('track')
        client.probe('gc'); time.sleep(.5); client.probe('gc'); time.sleep(.5)
        resources = client.probe('resources')
        check('old command closures release retired class loaders', resources['aliveTrackedLoaders'] == 5)
        report['resources'] = resources
        (server / 'module-split-validation.json').write_text(json.dumps(report, indent=2, ensure_ascii=False))
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server-dir', type=Path, required=True)
    parser.add_argument('--fixtures', type=Path, required=True)
    args = parser.parse_args()
    exercise(args.server_dir.resolve(), args.fixtures.resolve())
