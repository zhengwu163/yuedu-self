"""Offline HTTP chapter benchmark. Private config/output paths are CLI inputs."""
import argparse
import hashlib
import json
import re
import subprocess
import threading
import time
import wave
import urllib.request
from urllib.parse import urlsplit
from pathlib import Path


def split_text(text, limit=50):
    result = []
    while text:
        cut = min(limit, len(text))
        if len(text) > limit:
            for marks in ('。！？\n', '，、；：'):
                positions = [i + 1 for i, c in enumerate(text[:limit]) if c in marks]
                if positions:
                    cut = max(positions)
                    break
        result.append(text[:cut])
        text = text[cut:]
    return result


def summarize(rows):
    wall = sum(r['wall_s'] for r in rows)
    duration = sum(r['duration_s'] for r in rows)
    return {'synthesis_s': wall, 'duration_s': duration, 'rtf': wall / duration}


def role_segments(text, annotation, limit=50):
    rows, position = [], 0
    for index, match in enumerate(re.finditer('\u201c[^\u201d]*\u201d', text)):
        rows.extend({'text': part, 'role': 'narrator'} for part in split_text(text[position:match.start()], limit))
        role = next((r for r in ('male', 'female') if index in annotation[r]), 'narrator')
        rows.extend({'text': part, 'role': role} for part in split_text(match.group(), limit))
        position = match.end()
    rows.extend({'text': part, 'role': 'narrator'} for part in split_text(text[position:], limit))
    return rows


def write_json(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
    temporary.replace(path)


def validate_url(value):
    parsed = urlsplit(value)
    if (parsed.scheme != 'http' or parsed.hostname not in ('127.0.0.1', 'localhost')
            or not parsed.port or parsed.username or parsed.password
            or parsed.path or parsed.query or parsed.fragment):
        raise ValueError('benchmark requires an unadorned loopback HTTP origin')
    return value


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *_args, **_kwargs):
        raise ValueError('benchmark refuses HTTP redirects')


class GpuMonitor:
    def __enter__(self):
        import pynvml
        self.nv = pynvml
        pynvml.nvmlInit()
        self.handle = pynvml.nvmlDeviceGetHandleByIndex(0)
        self.peak = 0
        self.stop = threading.Event()
        def sample():
            while not self.stop.is_set():
                self.peak = max(self.peak, self.nv.nvmlDeviceGetMemoryInfo(self.handle).used)
                self.stop.wait(.1)
        self.thread = threading.Thread(target=sample, daemon=True)
        self.thread.start()
        return self

    def __exit__(self, *_):
        self.stop.set()
        self.thread.join()
        self.nv.nvmlShutdown()


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--url', default='http://127.0.0.1:8787')
    parser.add_argument('--chapters', nargs='+', default=['ch01', 'ch02', 'ch03'])
    parser.add_argument('--states', nargs='+', default=['cold', 'warm'])
    args = parser.parse_args()
    validate_url(args.url)
    config = json.loads(args.config.read_text(encoding='utf-8-sig'))
    root = args.config.parent
    token = (root / config['tokenFile']).read_text().strip()
    # Disable system HTTP proxies: benchmark text must only reach the local service.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    def call(path, value=None, lease=None):
        headers = {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'}
        if lease:
            headers['X-NovelAudio-Lease'] = lease
        request = urllib.request.Request(args.url + path, headers=headers,
            data=None if value is None else json.dumps(value, ensure_ascii=False).encode())
        with opener.open(request, timeout=1500) as response:
            data = response.read()
            return json.loads(data) if 'json' in response.headers.get('Content-Type', '') else data
    ffmpeg = config['tts']['ffmpeg'] if 'tts' in config else config['ttsFfmpeg']
    ffprobe = config['tts']['ffprobe'] if 'tts' in config else config['ttsFfprobe']
    args.output.mkdir(parents=True, exist_ok=True)
    for chapter in args.chapters:
        text_path = Path(__file__).parent / 'fixtures' / (chapter + '.txt')
        text = text_path.read_text(encoding='utf-8')
        chunks = split_text(text)
        for state in args.states:
            directory = args.output / (chapter + '-' + state)
            directory.mkdir(exist_ok=True)
            manifest = directory / 'result.json'
            if manifest.exists():
                raise FileExistsError('preserve prior evidence; select a fresh output directory')
            record = {'chapter': chapter, 'state': state, 'complete': False,
                'text_sha256': hashlib.sha256(text.encode()).hexdigest(),
                'segments': [], 'segment_count': len(chunks), 'segment_chars': 50,
                'voice': 'local.qwen3-tts.narrator', 'memory_scope': 'total GPU used, 100ms samples'}
            lease = None
            with GpuMonitor() as gpu:
                started = time.perf_counter()
                try:
                    lease = call('/v1/runtime/acquire', {'sessionId': 'benchmark-' + directory.name,
                        'purpose': 'listen', 'expectedChapterCount': 1})['leaseId']
                    record['acquire_s'] = time.perf_counter() - started
                    def synthesize(segment):
                        return call('/v1/tts/synthesize', {'text': segment,
                            'voiceAssetId': record['voice'], 'language': 'zh-CN', 'speed': 1.0}, lease)
                    if state == 'warm':
                        synthesize('清晨的钟声从远处传来。')
                        started = time.perf_counter()
                    for index, chunk in enumerate(chunks):
                        # Whitespace is retained in coverage but does not need a TTS request.
                        if not chunk.strip():
                            continue
                        before = time.perf_counter()
                        audio = synthesize(chunk)
                        returned = time.perf_counter()
                        elapsed = time.perf_counter() - before
                        target = directory / f'{index:04d}.ogg'
                        target.write_bytes(audio)
                        subprocess.run([ffmpeg, '-v', 'error', '-i', str(target), '-f', 'null', '-'],
                            check=True, capture_output=True, timeout=60)
                        duration = float(subprocess.check_output([ffprobe, '-v', 'error',
                            '-show_entries', 'format=duration', '-of', 'default=nw=1:nk=1', str(target)]))
                        if not record['segments']:
                            record['first_audio_s'] = returned - started
                            record['first_verified_audio_s'] = time.perf_counter() - started
                        record['segments'].append({'index': index, 'wall_s': elapsed,
                            'duration_s': duration, 'sha256': hashlib.sha256(audio).hexdigest(),
                            'text_sha256': hashlib.sha256(chunk.encode()).hexdigest(), 'file': target.name})
                        record['gpu_peak_mib'] = gpu.peak / 2**20
                        write_json(manifest, record)
                        print(directory.name, len(record['segments']), '/', len(chunks),
                            round(elapsed, 2), 's', flush=True)
                    record.update(summarize(record['segments']))
                    record['chapter_wall_s'] = time.perf_counter() - started
                    record['chapter_rtf'] = record['chapter_wall_s'] / record['duration_s']
                    listing = directory / 'concat.txt'
                    listing.write_text(''.join("file '" + r['file'] + "'\n" for r in record['segments']))
                    output = directory / 'chapter.wav'
                    subprocess.run([ffmpeg, '-v', 'error', '-y', '-f', 'concat', '-safe', '0', '-i',
                        str(listing), '-ac', '1', '-ar', '24000', str(output)], check=True, capture_output=True)
                    subprocess.run([ffmpeg, '-v', 'error', '-i', str(output), '-f', 'null', '-'],
                        check=True, capture_output=True)
                    record['audio_sha256'] = hashlib.sha256(output.read_bytes()).hexdigest()
                    record['container_duration_sum_s'] = record['duration_s']
                    with wave.open(str(output), 'rb') as decoded:
                        record['duration_s'] = decoded.getnframes() / decoded.getframerate()
                        record['sample_rate'] = decoded.getframerate()
                    record['rtf'] = record['synthesis_s'] / record['duration_s']
                    record['verified_complete_wall_s'] = time.perf_counter() - started
                    record['complete'] = True
                except Exception as error:
                    record['error_type'] = type(error).__name__
                    raise
                finally:
                    try:
                        if lease:
                            call('/v1/runtime/release', {}, lease)
                    finally:
                        record['gpu_peak_mib'] = gpu.peak / 2**20
                        write_json(manifest, record)


if __name__ == '__main__':
    main()
