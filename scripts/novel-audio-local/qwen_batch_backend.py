"""Opt-in native list batching for local benchmarks; existing v1 stays unchanged."""
from qwen_backend import QwenTtsAdapter
from scripts.novel_audio_server.errors import InvalidBackendResponseError, RunnerUnavailableError


class QwenBatchTtsAdapter(QwenTtsAdapter):
    def synthesize_batch(self, requests, *, non_streaming_mode=False):
        if self.closed:
            raise RuntimeError('adapter is closed')
        if not 1 <= len(requests) <= 8:
            raise ValueError('batch size must be between one and eight')
        operations = [self.build_operation(request) for request in requests]
        if any(operation['operation'] != 'voice_clone' for operation in operations):
            raise ValueError('batch experiment requires fixed clone voices')
        model = self._load_model(operations[0]['modelPath'])
        prompts = []
        try:
            for operation in operations:
                key = (operation['refAudio'], operation['refText'], operation['xVectorOnlyMode'])
                if key not in self.clone_prompts:
                    self.clone_prompts[key] = model.create_voice_clone_prompt(
                        ref_audio=operation['refAudio'], ref_text=operation['refText'],
                        x_vector_only_mode=operation['xVectorOnlyMode'])
                prompts.extend(self.clone_prompts[key])
            waves, rate = model.generate_voice_clone(
                text=[operation['text'] for operation in operations],
                language=[operation['language'] for operation in operations],
                voice_clone_prompt=prompts, non_streaming_mode=non_streaming_mode)
        except Exception:
            raise RunnerUnavailableError() from None
        if len(waves) != len(requests):
            raise InvalidBackendResponseError()
        return [self.audio_encoder((wave, rate), request['speed'], self.config.tts.ffmpeg,
            self.config.tts.ffprobe, self.config.root / 'state' / 'audio-tmp')
            for wave, request in zip(waves, requests)]
