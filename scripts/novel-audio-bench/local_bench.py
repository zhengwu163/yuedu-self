"""Serial offline engine benchmark; run only while the installed runtime is idle."""
import argparse
import hashlib
import json
import os
import subprocess
import sys
import time
import wave
from pathlib import Path

from bench import GpuMonitor, role_segments, split_text, summarize, write_json

os.environ.update(HF_HUB_OFFLINE='1', TRANSFORMERS_OFFLINE='1', HF_HUB_DISABLE_TELEMETRY='1')
ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT), str(ROOT / 'scripts' / 'novel-audio-local')]


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('--engine', choices=['batch', 'graphs', 'voicestudio'], required=True)
    parser.add_argument('--batch', type=int, choices=[1, 4, 8], default=1)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--segment-chars', type=int, default=50)
    parser.add_argument('--non-streaming', action='store_true')
    parser.add_argument('--roles', action='store_true')
    parser.add_argument('--vs-source', type=Path)
    parser.add_argument('--vs-engine', choices=['omnivoice', 'voxcpm2'], default='omnivoice')
    parser.add_argument('--chapters', nargs='+', default=['ch01', 'ch02', 'ch03'])
    args = parser.parse_args()
    if args.engine != 'batch' and args.batch != 1:
        parser.error('this backend is single-item only')
    if args.output.exists():
        parser.error('select a fresh output directory to preserve prior evidence')
    args.output.mkdir(parents=True)
    from config import load_config
    from voices import VoiceCatalog
    from audio import encode_ogg_opus
    import importlib.metadata
    import numpy as np
    import torch
    config = load_config(args.config)
    catalog = VoiceCatalog(config.voice_catalog, root=config.root)
    voice_ids = {'narrator': 'local.qwen3-tts.narrator',
        'male': 'local.qwen3-tts.young-male', 'female': 'local.qwen3-tts.young-female'}
    versions = {}
    for distribution in ['torch', 'transformers', 'qwen-tts', 'qwen-tts-hf', 'faster-qwen3-tts', 'omnivoice', 'voxcpm']:
        try:
            versions[distribution] = importlib.metadata.version(distribution)
        except importlib.metadata.PackageNotFoundError:
            pass
    settings = {'engine': args.engine, 'vs_engine': args.vs_engine, 'batch': args.batch,
        'segment_chars': args.segment_chars, 'non_streaming_mode': args.non_streaming,
        'roles': args.roles, 'versions': versions, 'model_weights_sha256':
        hashlib.file_digest((config.tts.base_model / 'model.safetensors').open('rb'), 'sha256').hexdigest()
        if args.engine in ('batch', 'graphs') else None}
    write_json(args.output / 'settings.json', settings)
    with GpuMonitor() as monitor:
        started = time.perf_counter()
        if args.engine == 'batch':
            from qwen_batch_backend import QwenBatchTtsAdapter
            adapter = QwenBatchTtsAdapter(config, catalog)
            adapter._load_model(config.tts.base_model)
            def synthesize(rows):
                return adapter.synthesize_batch([dict(text=r['text'], voiceAssetId=voice_ids[r['role']], speed=1.0)
                    for r in rows], non_streaming_mode=args.non_streaming)
        elif args.engine == 'graphs':
            from faster_qwen3_tts import FasterQwen3TTS
            model = FasterQwen3TTS.from_pretrained(str(config.tts.base_model),
                device='cuda', dtype=torch.bfloat16, attn_implementation='sdpa', local_files_only=True)
            def synthesize(rows):
                row = rows[0]
                voice = catalog.asset(voice_ids[row['role']], None)
                waves, rate = model.generate_voice_clone(text=row['text'], language='Chinese',
                    ref_audio=str(voice.reference_audio), ref_text=voice.reference_text,
                    non_streaming_mode=args.non_streaming)
                return [encode_ogg_opus((waves[0], rate), 1.0, config.tts.ffmpeg, config.tts.ffprobe,
                    config.root / 'state' / 'audio-tmp')]
        else:
            if args.vs_source is None:
                parser.error('VoiceStudio requires --vs-source')
            sys.path.insert(0, str(args.vs_source / 'backend'))
            if sys.platform == 'win32':
                # Match the Windows application entrypoint: Triton is unavailable there.
                os.environ.update(TORCH_COMPILE_DISABLE='1', TORCHDYNAMO_DISABLE='1', TORCHINDUCTOR_DISABLE='1')
            from services.tts_backend import get_backend_class
            backend = get_backend_class(args.vs_engine)()
            def synthesize(rows):
                row = rows[0]
                voice = catalog.asset(voice_ids[row['role']], None)
                wave = backend.generate(row['text'], language='zh',
                    ref_audio=str(voice.reference_audio), ref_text=voice.reference_text,
                    num_step=16, guidance_scale=2.0, speed=1.0, denoise=False)
                return [encode_ogg_opus((wave, backend.sample_rate), 1.0, config.tts.ffmpeg,
                    config.tts.ffprobe, config.root / 'state' / 'audio-tmp')]
        settings['initialization_s'] = time.perf_counter() - started
        # A complete utterance initializes reference features and CUDA graphs before warm timing.
        torch.manual_seed(42)
        np.random.seed(42)
        warm = synthesize([{'text': '清晨的钟声从远处传来。', 'role': 'narrator'}])
        settings['cold_first_audio_s'] = time.perf_counter() - started
        (args.output / 'cold-first.ogg').write_bytes(warm[0])
        settings['cold_gpu_peak_mib'] = monitor.peak / 2**20
        # Match the actual batch shape and prepare each reference used in a role run.
        settings['warmup_roles'] = ['narrator', 'male', 'female'] if args.roles else ['narrator']
        warm_started = time.perf_counter()
        for role in settings['warmup_roles']:
            synthesize([{'text': '清晨的钟声从远处传来。', 'role': role}] * args.batch)
        settings['warmup_s'] = time.perf_counter() - warm_started
        write_json(args.output / 'settings.json', settings)
        annotations = json.loads((Path(__file__).parent / 'fixtures' / 'roles.json').read_text(encoding='utf-8'))
        for chapter in args.chapters:
            text = (Path(__file__).parent / 'fixtures' / (chapter + '.txt')).read_text(encoding='utf-8')
            rows = role_segments(text, annotations[chapter], args.segment_chars) if args.roles else [
                {'text': chunk, 'role': 'narrator'} for chunk in split_text(text, args.segment_chars)]
            assert ''.join(row['text'] for row in rows) == text
            rows = [dict(row, index=i) for i, row in enumerate(rows) if row['text'].strip()]
            directory = args.output / chapter
            directory.mkdir()
            record = dict(settings, chapter=chapter, state='warm', complete=False, segments=[], batches=[],
                text_sha256=hashlib.sha256(text.encode()).hexdigest(), segment_count=len(rows))
            manifest = directory / 'result.json'
            monitor.peak = 0
            torch.cuda.reset_peak_memory_stats()
            chapter_start = time.perf_counter()
            try:
                for offset in range(0, len(rows), args.batch):
                    batch = rows[offset:offset + args.batch]
                    torch.manual_seed(42 + offset)
                    np.random.seed(42 + offset)
                    before = time.perf_counter()
                    audios = synthesize(batch)
                    torch.cuda.synchronize()
                    returned = time.perf_counter()
                    if len(audios) != len(batch):
                        raise ValueError('batch output count mismatch')
                    if offset == 0:
                        record['first_audio_s'] = returned - chapter_start
                    record['batches'].append({'offset': offset, 'count': len(batch), 'wall_s': returned - before})
                    for row, data in zip(batch, audios):
                        target = directory / f"{row['index']:04d}.ogg"
                        target.write_bytes(data)
                        subprocess.run([str(config.tts.ffmpeg), '-v', 'error', '-i', str(target), '-f', 'null', '-'],
                            check=True, capture_output=True, timeout=60)
                        duration = float(subprocess.check_output([str(config.tts.ffprobe), '-v', 'error',
                            '-show_entries', 'format=duration', '-of', 'default=nw=1:nk=1', str(target)]))
                        record['segments'].append({'index': row['index'], 'role': row['role'], 'file': target.name,
                            'duration_s': duration, 'sha256': hashlib.sha256(data).hexdigest(),
                            'text_sha256': hashlib.sha256(row['text'].encode()).hexdigest()})
                    record['gpu_peak_mib'] = monitor.peak / 2**20
                    write_json(manifest, record)
                    print(args.engine, chapter, len(record['segments']), '/', len(rows),
                        round(returned - before, 2), 's', flush=True)
                record['duration_s'] = sum(r['duration_s'] for r in record['segments'])
                record['synthesis_s'] = sum(r['wall_s'] for r in record['batches'])
                record['rtf'] = record['synthesis_s'] / record['duration_s']
                record['chapter_wall_s'] = time.perf_counter() - chapter_start
                record['chapter_rtf'] = record['chapter_wall_s'] / record['duration_s']
                listing = directory / 'concat.txt'
                listing.write_text(''.join("file '" + r['file'] + "'\n" for r in record['segments']))
                output = directory / 'chapter.wav'
                subprocess.run([str(config.tts.ffmpeg), '-v', 'error', '-y', '-f', 'concat', '-safe', '0',
                    '-i', str(listing), '-ac', '1', str(output)], check=True, capture_output=True)
                subprocess.run([str(config.tts.ffmpeg), '-v', 'error', '-i', str(output), '-f', 'null', '-'],
                    check=True, capture_output=True)
                record['audio_sha256'] = hashlib.sha256(output.read_bytes()).hexdigest()
                record['container_duration_sum_s'] = record['duration_s']
                with wave.open(str(output), 'rb') as decoded:
                    record['duration_s'] = decoded.getnframes() / decoded.getframerate()
                    record['sample_rate'] = decoded.getframerate()
                record['rtf'] = record['synthesis_s'] / record['duration_s']
                record['verified_complete_wall_s'] = time.perf_counter() - chapter_start
                record['torch_peak_allocated_mib'] = torch.cuda.max_memory_allocated() / 2**20
                record['complete'] = True
            except Exception as error:
                record['error_type'] = type(error).__name__
                raise
            finally:
                write_json(manifest, record)


if __name__ == '__main__':
    main()
