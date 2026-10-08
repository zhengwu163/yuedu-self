"""Public errors shared by NovelAudioServer providers."""


class NovelAudioError(Exception):
    status = 503
    code = "service_unavailable"

    def __init__(self, message=None):
        super().__init__(message or self.code)


class InvalidRequestError(NovelAudioError):
    status, code = 400, "invalid_request"


class InvalidBackendResponseError(NovelAudioError):
    status, code = 502, "invalid_backend_response"


class BackendError(NovelAudioError):
    status, code = 503, "backend_unavailable"


class RunnerUnavailableError(BackendError):
    code = "runner_unavailable"


class RunnerIncompatibleError(BackendError):
    code = "runner_incompatible"


class MissingReferenceAudioError(BackendError):
    code = "missing_reference_audio"


class ServiceBusyError(NovelAudioError):
    status, code = 429, "busy"


class LeaseError(NovelAudioError):
    status, code = 409, "invalid_lease"


class LeaseExpiredError(LeaseError):
    code = "lease_expired"


class WorkerStartError(NovelAudioError):
    status, code = 503, "worker_start_failed"


class WorkerStopError(NovelAudioError):
    status, code = 503, "worker_stop_failed"


class RequestCancelledError(NovelAudioError):
    status, code = 503, "request_cancelled"


class WorkerUnavailableError(NovelAudioError, RuntimeError):
    status, code = 503, "worker_unavailable"
