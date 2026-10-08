from errors import NotReadyError
from models import HealthStatus
from protocol import project_analysis


class ConfiguredDirectorProvider:
    """保留章节分析的服务边界，不把具体 LLM 绑定进 TTS Provider。"""

    def __init__(self, analyze=None):
        self._analyze = analyze

    def health(self):
        return HealthStatus(ready=self._analyze is not None)

    def analyze(self, request):
        if self._analyze is None:
            raise NotReadyError()
        value = self._analyze(request)
        return project_analysis(value, request.as_dict())
