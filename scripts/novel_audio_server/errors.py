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
