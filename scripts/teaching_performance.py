"""Dedicated test DB, one local client: teaching evidence, never campus capacity.
Import is inert. From repository root: python scripts/teaching_performance.py.
Prepare the labelled fixture with scripts/fixtures/S31PerformanceFixture.java
first; the service at 127.0.0.1:8081 must report studyroom_java_test. Twenty
half-hour bookings cover 08:00-18:00 in two rooms (30x20 plus20x20 seats).
Run only when the current test-DB owner explicitly authorizes it.
Each invocation retains a separate result, including all failures and slow samples.
"""
import datetime as dt
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import socket
import statistics
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parent.parent
WORK = ROOT / 'work'
SAMPLES, TARGET_MS = 20, 2000
BASE = os.environ.get('STUDYROOM_API', 'http://127.0.0.1:8081/api').rstrip('/')


class BenchmarkFailure(Exception):
    """Only fixed public failure codes, never raw exception/request text."""


def utc_now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def call(method, path, body=None, token=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request(BASE + path, method=method, headers=headers,
        data=None if body is None else json.dumps(body).encode())
    started = time.perf_counter()
    status, payload, error = None, None, None
    try:
        try:
            response = urllib.request.urlopen(request, timeout=20)
        except urllib.error.HTTPError as failure:
            response = failure
        with response:
            status = response.status
            try:
                payload = json.loads(response.read())
            except (ValueError, UnicodeError):
                error = 'invalid_json_response'
        if error is None and status != 200:
            error = 'http_status_' + str(status)
        if error is None and (not isinstance(payload, dict) or 'data' not in payload):
            error = 'invalid_api_envelope'
    except Exception as failure:
        # Never retain exception text, raw response bodies, passwords or tokens.
        error = 'transport_' + type(failure).__name__
    return {'http_status': status, 'elapsed_ms': (time.perf_counter() - started) * 1000,
        'error': error, 'data': payload.get('data') if isinstance(payload, dict) else None}


def public_result(response):
    return {key: response[key] for key in ('http_status', 'elapsed_ms', 'error')}


def creation_outcome_unknown(http_status, reservation_id):
    """A lost/invalid success or server error can follow a committed creation."""
    if type(reservation_id) is int and reservation_id > 0:
        return False  # Known IDs are retained for the real cancellation pass.
    return http_status is None or http_status == 200 or 500 <= http_status < 600


def summary(samples):
    values = [sample['elapsed_ms'] for sample in samples]
    good = [sample['elapsed_ms'] for sample in samples if sample['status'] == 'success']
    rank = lambda items: sorted(items)[math.ceil(.95 * len(items)) - 1] if items else None
    return {'planned_samples': SAMPLES, 'attempted_samples': len(samples),
        'successful_samples': len(good), 'failed_samples': sum(sample['status'] == 'failure' for sample in samples),
        'in_progress_samples': sum(sample['status'] == 'running' for sample in samples),
        'not_attempted_samples': max(0, SAMPLES - len(samples)),
        'p95_nearest_rank_ms_all_attempts': rank(values), 'p95_nearest_rank_ms_successful': rank(good),
        'median_ms_all_attempts': statistics.median(values) if values else None,
        'maximum_ms_all_attempts': max(values) if values else None, 'target_ms': TARGET_MS,
        'target_met': len(good) >= SAMPLES and len(good) == len(samples) and rank(good) <= TARGET_MS}


def command(arguments):
    result = subprocess.run(arguments, cwd=ROOT, capture_output=True, text=True,
        encoding='utf-8', errors='replace', timeout=20, check=False)
    if result.returncode:
        raise RuntimeError('metadata command failed')
    return result.stdout, result.stderr


def redis_property(name, default):
    env_key = 'STUDYROOM_REDIS_' + name.upper()
    if env_key in os.environ:
        return os.environ[env_key]
    path = ROOT / '.local/application-local.properties'
    if path.exists():
        for line in path.read_text(encoding='utf-8-sig').splitlines():
            key, separator, value = line.partition('=')
            if separator and key.strip() == 'spring.redis.' + name:
                return value.strip()
    return default


def redis_version():
    """Only INFO server, with actual local Redis settings; never log credentials."""
    host, port = redis_property('host', '127.0.0.1'), int(redis_property('port', '6379'))
    password, username = redis_property('password', ''), redis_property('username', '')
    def send(stream, *parts):
        values = [str(part).encode() for part in parts]
        stream.write(b'*' + str(len(values)).encode() + b'\r\n' +
            b''.join(b'$' + str(len(value)).encode() + b'\r\n' + value + b'\r\n' for value in values))
        stream.flush()
        line = stream.readline()
        if line.startswith(b'+'):
            return line[1:-2]
        if line.startswith(b'$'):
            length = int(line[1:-2])
            if 0 <= length <= 65536:
                value = stream.read(length)
                if len(value) == length and stream.read(2) == b'\r\n':
                    return value
        raise RuntimeError('Redis metadata unavailable')
    with socket.create_connection((host, port), timeout=5) as connection:
        connection.settimeout(5)
        with connection.makefile('rwb') as stream:
            if password:
                send(stream, 'AUTH', username, password) if username else send(stream, 'AUTH', password)
            info = send(stream, 'INFO', 'server').decode()
    match = re.search(r'^redis_version:([0-9A-Za-z.+_-]+)\r?$', info, re.MULTILINE)
    if not match:
        raise RuntimeError('Redis version missing')
    return match.group(1)


def versions(result, failed):
    """Run only AFTER HTTP health verifies studyroom_java_test."""
    readers = {
        'commit': lambda: command(['git', 'rev-parse', 'HEAD'])[0].strip(),
        'git_worktree_dirty': lambda: bool(command(['git', 'status', '--porcelain'])[0].strip()),
        'jdk_command_version': lambda: '\n'.join(command(['java', '-version'])),
        'postgresql': lambda: command(['docker', 'compose', 'exec', '-T', 'db', 'psql', '-U',
            'studyroom', '-d', 'studyroom_java_test', '-At', '-c',
            "SELECT json_build_object('database_name',current_database(),'version',current_setting('server_version'),'server_version_num',current_setting('server_version_num'))::text"])[0].strip(),
        'redis': redis_version,
    }
    for key, read in readers.items():
        try:
            value = read()
            if key == 'commit' and not re.fullmatch('[0-9a-f]{40}', value):
                raise RuntimeError('commit unavailable')
            if key == 'jdk_command_version':
                # Do not store JAVA_TOOL_OPTIONS or unrelated environment stderr.
                match = re.search(r'(?m)^(?:openjdk|java)(?: version)?\s+"?([0-9][0-9A-Za-z.+_-]*)', value)
                if not match:
                    raise RuntimeError('JDK version unavailable')
                value = match.group(1)
            if key == 'postgresql':
                value = json.loads(value)
                if value.get('database_name') != 'studyroom_java_test':
                    raise RuntimeError('metadata database mismatch')
            result['versions'][key] = {'status': 'available', 'value': value}
        except Exception as failure:
            result['versions'][key] = {'status': 'unavailable', 'error': type(failure).__name__}
            failed('metadata', key + '_' + type(failure).__name__)
    # S31 validation can precede its final commit; retain the actual uncommitted tools too.
    result['source_sha256'] = {}
    for path in (Path(__file__).resolve(), ROOT / 'scripts/fixtures/S31PerformanceFixture.java'):
        try:
            result['source_sha256'][path.relative_to(ROOT).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
        except OSError as failure:
            failed('metadata', 'source_fingerprint_' + type(failure).__name__)


def main():
    WORK.mkdir(exist_ok=True)
    run_id = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ') + '-' + uuid.uuid4().hex[:8]
    directory = WORK / 's31-performance-runs'
    directory.mkdir(exist_ok=True)
    output, pointer = directory / (run_id + '.json'), WORK / 's31-performance-latest.json'
    output.touch(exist_ok=False)  # Even an ID collision cannot overwrite an earlier run.
    try:
        endpoint = urllib.parse.urlsplit(BASE)
        endpoint_metadata = {'scheme': endpoint.scheme, 'host': endpoint.hostname, 'port': endpoint.port}
    except ValueError:
        endpoint_metadata = {'error': 'invalid_endpoint'}
    result = {'run_id': run_id, 'status': 'running', 'started_at': utc_now(), 'finished_at': None,
        'label': 'Engineering single-client teaching benchmark, not campus concurrency capacity',
        'scope': 'Each whole-map sample contains two sequential room requests, all 1000 seats. Its elapsed_ms sums their HTTP+JSON-decode elapsed times; incremental evidence disk I/O, login and 0.6s real rate pauses are excluded. wall_elapsed_ms separately includes client bookkeeping. No warmup/slow/failure samples discarded.',
        'endpoint': endpoint_metadata,
        'machine': {'os': platform.platform(), 'processor': platform.processor(), 'python': platform.python_version()},
        'client': {'implementation': 'Python urllib.request standard library', 'version': platform.python_version(), 'browser': 'not used'},
        'versions': {}, 'database_name': None, 'fixture': None,
        'query_samples': [], 'reservation_samples': [], 'created_reservations': [],
        'uncertain_creation_samples': [], 'cleanup': [], 'cleanup_failures': [],
        'failures': [], 'first_failure': None, 'accepted': False}
    token, verified = None, False
    active_sample, active_started, active_phase = None, None, None
    def save():
        result['query'], result['reservation_submit'] = summary(result['query_samples']), summary(result['reservation_samples'])
        temporary = output.with_suffix('.tmp')
        temporary.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        temporary.replace(output)
        latest = {'run_id': run_id, 'status': result['status'], 'path': output.relative_to(ROOT).as_posix()}
        temporary = pointer.with_suffix('.tmp')
        temporary.write_text(json.dumps(latest, indent=2) + '\n', encoding='utf-8')
        temporary.replace(pointer)
    def failed(phase, error, sample=None):
        entry = {'phase': phase, 'error': error, 'recorded_at': utc_now()}
        if sample is not None:
            entry['sample'] = sample
        result['failures'].append(entry)
        if result['first_failure'] is None:
            result['first_failure'] = entry.copy()
        save()
    def fail_active(error):
        if active_sample is not None and active_sample['status'] == 'running':
            active_sample.update(status='failure', error=error, elapsed_ms=(time.perf_counter()-active_started)*1000)
            active_sample['interrupted_elapsed_includes_client_bookkeeping'] = True
            if active_phase == 'reservation_submit' and active_sample.get('reservation_id') is None:
                result['uncertain_creation_samples'].append(active_sample['index'])
            failed(active_phase, error, active_sample['index'])
    save()
    try:
        health = call('GET', '/health')
        result['health'] = public_result(health)
        result['health']['reported_database_name'] = health['data'].get('database_name') if isinstance(health['data'], dict) else None
        if health['error'] or not isinstance(health['data'], dict) or health['data'].get('database_name') != 'studyroom_java_test':
            raise BenchmarkFailure('health_did_not_verify_dedicated_test_database')
        verified = True
        result['database_name'] = 'studyroom_java_test'
        save()
        # No metadata DB command, credential read, login or business call precedes health.
        versions(result, failed)
        fixture = json.loads((WORK / 's31-performance-fixture.json').read_text(encoding='utf-8-sig'))
        if len(fixture['rooms']) != 2 or len(set(fixture['rooms'])) != 2 or len(fixture['seats']) != 1000 or len(set(fixture['seats'])) != 1000:
            raise BenchmarkFailure('fixture_requires_two_rooms_and_1000_distinct_seats')
        result['fixture'] = {key: fixture[key] for key in ('label', 'user', 'rooms', 'seats', 'date')}
        result['fixture'].update(room_layouts=['30x20', '20x20'], java_runtime_version=fixture.get('java_runtime_version'), default_opening_verified=fixture.get('default_opening_verified', False))
        login = call('POST', '/auth/login', {'username': 's31_perf_student', 'password': 'S02-test-only-password'})
        result['login'] = public_result(login)
        if login['error'] or not isinstance(login['data'], dict) or not isinstance(login['data'].get('token'), str):
            raise BenchmarkFailure('benchmark_login_failed')
        token = login['data']['token']
        save()
        for index in range(SAMPLES):
            sample = {'index': index + 1, 'started_at': utc_now(), 'status': 'running', 'requests': [], 'elapsed_ms': 0, 'seat_count': 0, 'error': None}
            started, seen = time.perf_counter(), set()
            active_sample, active_started, active_phase = sample, started, 'query'
            result['query_samples'].append(sample)
            save()
            for room_index, room in enumerate(fixture['rooms']):
                response = call('GET', f"/rooms/{room}/seats?date={fixture['date']}&start=08:00&end=08:30", token=token)
                item, data = dict(public_result(response), room_id=room), response['data']
                if not response['error']:
                    try:
                        seats = data['seats']
                        if data['room']['id'] != room or len(seats) != (600 if room_index == 0 else 400):
                            raise ValueError()
                        ids = [seat['id'] for seat in seats]
                        if any(type(value) is not int or value <= 0 for value in ids) or len(set(ids)) != len(ids) or seen.intersection(ids):
                            raise ValueError()
                        seen.update(ids)
                        item['seat_count'] = len(ids)
                    except (KeyError, TypeError, ValueError):
                        item['error'] = 'invalid_room_map_or_seat_count'
                sample['requests'].append(item)
                sample['elapsed_ms'] = sum(part['elapsed_ms'] for part in sample['requests'])
                save()
            sample.update(elapsed_ms=sum(part['elapsed_ms'] for part in sample['requests']), wall_elapsed_ms=(time.perf_counter()-started)*1000, seat_count=len(seen))
            errors = [item['error'] for item in sample['requests'] if item['error']]
            if errors or len(seen) != 1000:
                sample['status'] = 'failure'
                sample['error'] = errors[0] if errors else 'not_exactly_1000_distinct_seats'
            else:
                sample['status'] = 'success'
            save()
            if sample['error']:
                failed('query', sample['error'], index + 1)
            print(f"1000-seat query {index + 1}/{SAMPLES}: {sample['status']} {sample['elapsed_ms']:.3f} ms", flush=True)
            active_sample = None
        base = dt.datetime.fromisoformat(fixture['date'] + 'T08:00:00')
        for index in range(SAMPLES):
            if index:
                time.sleep(0.6)  # Excluded from sample/request elapsed time.
            start, stamp = base + dt.timedelta(minutes=index * 30), utc_now()
            end = start + dt.timedelta(minutes=30)
            sample = {'index':index+1, 'started_at':stamp, 'status':'running', 'reservation_id':None, 'http_status':None, 'error':None, 'elapsed_ms':0}
            active_sample, active_started, active_phase = sample, time.perf_counter(), 'reservation_submit'
            result['reservation_samples'].append(sample)
            save()
            response = call('POST', '/reservations', {'seat_id': fixture['seats'][index], 'date': fixture['date'],
                'start_time': start.strftime('%H:%M'), 'end_time': end.strftime('%H:%M')}, token)
            sample.update(public_result(response))
            data = response['data']
            reservation = data.get('id') if isinstance(data, dict) else None
            if type(reservation) is int and reservation > 0:
                sample['reservation_id'] = reservation
                if reservation not in result['created_reservations']:
                    result['created_reservations'].append(reservation)
            if not response['error'] and sample['reservation_id'] and data.get('status') == 'pending':
                sample['status'] = 'success'
            else:
                sample['status'] = 'failure'
                sample['error'] = response['error'] or 'invalid_reservation_receipt'
                if creation_outcome_unknown(response['http_status'], sample['reservation_id']):
                    result['uncertain_creation_samples'].append(index + 1)
            save()
            if sample['error']:
                failed('reservation_submit', sample['error'], index + 1)
            print(f"create {index + 1}/{SAMPLES}: {sample['status']} {sample['elapsed_ms']:.3f} ms", flush=True)
            active_sample = None
    except KeyboardInterrupt:
        fail_active('KeyboardInterrupt')
        failed('interrupted', 'KeyboardInterrupt')
    except Exception as failure:
        # No raw exception text: it can contain request data or environment secrets.
        error = str(failure) if isinstance(failure, BenchmarkFailure) else type(failure).__name__
        fail_active(error)
        failed('setup_or_execution', error)
    finally:
        if verified and token:
            for reservation in result['created_reservations']:
                try:
                    response = call('POST', f'/reservations/{reservation}/cancel', token=token)
                    item = dict(public_result(response), reservation_id=reservation, status='failure')
                    if not response['error'] and isinstance(response['data'], dict) and response['data'].get('status') == 'cancelled':
                        item['status'] = 'success'
                    else:
                        item['error'] = response['error'] or 'cancellation_not_confirmed'
                    result['cleanup'].append(item)
                    if item['status'] != 'success':
                        result['cleanup_failures'].append(item.copy())
                        failed('cleanup', item['error'])
                except Exception as failure:
                    item = {'reservation_id': reservation, 'status': 'failure', 'error': type(failure).__name__}
                    result['cleanup'].append(item)
                    result['cleanup_failures'].append(item.copy())
                    failed('cleanup', type(failure).__name__)
                save()
        result['finished_at'] = utc_now()
        result['accepted'] = (summary(result['query_samples'])['target_met'] and summary(result['reservation_samples'])['target_met']
            and not result['failures'] and not result['cleanup_failures'] and not result['uncertain_creation_samples'])
        result['status'] = 'success' if result['accepted'] else 'failed'
        save()
        print(json.dumps({'run_id': run_id, 'status': result['status'], 'evidence': output.relative_to(ROOT).as_posix(),
            'query': result['query'], 'reservation_submit': result['reservation_submit'],
            'cleanup_failures': len(result['cleanup_failures']), 'first_failure': result['first_failure']}, indent=2), flush=True)
    return 0 if result['accepted'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
