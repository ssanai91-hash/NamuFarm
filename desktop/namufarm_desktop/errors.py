class UserError(Exception):
    """Messages are deliberately safe to display without customer data."""


class ConflictError(UserError):
    pass


class AuthenticationError(UserError):
    """The server explicitly rejected this saved connection."""
    pass


class NetworkError(UserError):
    pass

