#!/usr/bin/env python3
"""Trigger the installed QPlugin Orin pipelines without putting tokens in shell arguments.

Waits for confirmed completion by default. Run on the Orin host with its private control file.
"""
import argparse
import json
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

PIPELINES = ['qplugin-build', 'qplugin-deploy-creative', 'qplugin-deploy-survival', 'qplugin-status']


def request(base, path, token, payload=None):
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(base.rstrip('/') + path, data=data,
        headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'},
        method='GET' if data is None else 'POST')
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            return json.loads(response.read(1048576))
    except urllib.error.HTTPError as error:
        raise RuntimeError('Orin HTTP ' + str(error.code) + ': ' + error.read(2048).decode(errors='replace')) from None


def report(value):
    print(json.dumps(value, ensure_ascii=False), flush=True)


def watch(base, token, job_id, timeout):
    deadline = time.monotonic() + timeout
    previous = None
    while time.monotonic() < deadline:
        status = request(base, '/remote/jobs/' + urllib.parse.quote(job_id, safe='') + '/status', token)
        if status.get('status') != previous:
            report(status); previous = status.get('status')
        if previous == 'SUCCESS': return 0
        if previous == 'FAILED': return 1
        time.sleep(min(2, max(0, deadline - time.monotonic())))
    report({'status': 'UNCONFIRMED', 'jobId': job_id,
            'message': 'Client wait expired; query this job and backend status before retrying.'})
    return 4


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, default=Path('/opt/server/orin/qplugin-control.json'))
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument('--pipeline', choices=PIPELINES)
    target.add_argument('--status', metavar='JOB_ID')
    parser.add_argument('--revision')
    parser.add_argument('--release-commit')
    parser.add_argument('--install-host', action='store_true')
    parser.add_argument('--timeout', type=float, default=2700)
    args = parser.parse_args(arguments)
    if args.timeout <= 0: parser.error('timeout must be positive')
    if args.release_commit and not re.fullmatch('[0-9a-f]{40,64}', args.release_commit):
        parser.error('release-commit must be a full Git SHA')
    if args.revision and args.release_commit: parser.error('Choose revision or release-commit')
    if (args.install_host or args.release_commit) and args.pipeline not in ['qplugin-deploy-creative', 'qplugin-deploy-survival']:
        parser.error('Host installation and existing releases are only valid for a deployment pipeline')
    control = json.loads(args.config.read_text())
    base = control.get('url') or 'http://127.0.0.1:' + str(int(control['port']))
    status_token = control.get('clusterToken') or control['tokens']['qplugin-build']
    if args.status:
        result = request(base, '/remote/jobs/' + urllib.parse.quote(args.status, safe='') + '/status', status_token)
        report(result)
        return 0 if result.get('status') == 'SUCCESS' else 2 if result.get('status') in ['QUEUED', 'RUNNING'] else 1
    inputs = {}
    if args.revision: inputs['revision'] = args.revision
    if args.release_commit: inputs['release_commit'] = args.release_commit
    if args.install_host: inputs['install_host'] = 'true'
    accepted = request(base, '/remote/trigger/' + args.pipeline, control['tokens'][args.pipeline], {'inputs': inputs})
    report(accepted)
    return watch(base, status_token, accepted['jobId'], args.timeout)


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (OSError, ValueError, KeyError, RuntimeError) as error:
        print('Orin request failed: ' + str(error), file=sys.stderr)
        raise SystemExit(1)
