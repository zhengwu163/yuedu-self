"""Read back completed benchmark evidence without trusting completion flags alone."""
import argparse
import hashlib
import json
import math
import re
import wave
from pathlib import Path

from bench import role_segments, split_text, write_json


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def verify_chapter(path, fixtures):
    record = json.loads(path.read_text(encoding='utf-8'))
    if record.get('complete') is not True:
        raise ValueError('chapter incomplete')
    text = (fixtures / (record['chapter'] + '.txt')).read_text(encoding='utf-8')
    if record['text_sha256'] != hashlib.sha256(text.encode()).hexdigest():
        raise ValueError('fixture changed')
    limit = record.get('segment_chars', 50)
    if record.get('roles'):
        annotations = json.loads((fixtures / 'roles.json').read_text(encoding='utf-8'))
        rows = role_segments(text, annotations[record['chapter']], limit)
    else:
        rows = [{'text': chunk, 'role': 'narrator'} for chunk in split_text(text, limit)]
    expected = [(i, row) for i, row in enumerate(rows) if row['text'].strip()]
    if [r['index'] for r in record['segments']] != [i for i, _ in expected]:
        raise ValueError('missing, duplicate or reordered segments')
    for saved, (_, row) in zip(record['segments'], expected):
        if saved['text_sha256'] != hashlib.sha256(row['text'].encode()).hexdigest():
            raise ValueError('segment text changed')
        if not re.fullmatch(r'[0-9]{4}\.ogg', saved['file']):
            raise ValueError('invalid segment filename')
        if digest(path.parent / saved['file']) != saved['sha256']:
            raise ValueError('segment audio changed')
    output = path.parent / 'chapter.wav'
    if digest(output) != record['audio_sha256']:
        raise ValueError('chapter audio changed')
    with wave.open(str(output), 'rb') as audio:
        frames = audio.getnframes()
        duration = frames / audio.getframerate()
        byte_count = 0
        while data := audio.readframes(65536):
            byte_count += len(data)
        if frames <= 0 or byte_count != frames * audio.getnchannels() * audio.getsampwidth():
            raise ValueError('truncated or empty chapter PCM')
    wall = sum(r['wall_s'] for r in record.get('batches', record['segments']))
    if not math.isclose(wall, record['synthesis_s'], rel_tol=1e-9):
        raise ValueError('inconsistent synthesis time')
    return {'chapter': record['chapter'], 'state': record['state'], 'segments': len(expected),
        'synthesis_s': wall, 'duration_s': duration, 'rtf': wall / duration,
        'acquire_s': record.get('acquire_s'), 'first_audio_s': record['first_audio_s'],
        'gpu_peak_mib': record['gpu_peak_mib'], 'audio_sha256': record['audio_sha256'],
        'text_sha256': record['text_sha256'], 'verified': True,
        'batch': record.get('batch', 1), 'roles': record.get('roles', False),
        'cold_first_audio_s': record.get('cold_first_audio_s'),
        'verified_complete_wall_s': record.get('verified_complete_wall_s'),
        'chapter_wall_s': record.get('chapter_wall_s')}


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    fixtures = Path(__file__).parent / 'fixtures'
    result = []
    for path in sorted(args.root.rglob('result.json')):
        if json.loads(path.read_text(encoding='utf-8')).get('complete'):
            row = verify_chapter(path, fixtures)
            row['run'] = path.parent.parent.name
            row['directory'] = path.parent.relative_to(args.root).as_posix()
            result.append(row)
    write_json(args.output, result)
    for row in result:
        print(row['directory'], 'RTF', round(row['rtf'], 4), 'seconds', round(row['synthesis_s'], 2),
            'audio', round(row['duration_s'], 2), 'MiB', round(row['gpu_peak_mib'], 1))


if __name__ == '__main__':
    main()
