class UserError(Exception):
    """Messages are deliberately safe to display without customer data."""


class ConflictError(UserError):
    pass


class NetworkError(UserError):
    pass
