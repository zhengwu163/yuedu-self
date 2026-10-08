class ServiceError(Exception):
    status = 503
    code = "service_unavailable"

    def __init__(self, message=None):
        super().__init__(message or self.code)


class ProviderError(ServiceError):
    status = 502
    code = "provider_error"


class AuthenticationError(ProviderError):
    status = 401
    code = "unauthorized"


class InvalidRequestError(ServiceError):
    status = 400
    code = "invalid_request"


class NotReadyError(ServiceError):
    status = 503
    code = "not_ready"


class BusyError(ServiceError):
    status = 429
    code = "busy"


class ProviderTimeoutError(ProviderError):
    status = 504
    code = "provider_timeout"


class ProtocolError(ServiceError):
    status = 502
    code = "invalid_provider_response"


class AudioError(ProviderError):
    code = "invalid_audio"
