use thiserror::Error;

pub type DomainResult<T> = Result<T, DomainError>;

/// Error type shared across the core. Messages must never contain secrets
/// (tokens, credentials); they may be shown to users and written to logs.
#[derive(Debug, Clone, Error, PartialEq, Eq)]
pub enum DomainError {
    #[error("invalid input: {0}")]
    InvalidInput(String),
    #[error("not found: {0}")]
    NotFound(String),
    #[error("capability unavailable: {0}")]
    CapabilityUnavailable(String),
    #[error("not authenticated")]
    NotAuthenticated,
    #[error("network error: {0}")]
    Network(String),
    #[error("rate limited; retry after {retry_after_ms} ms")]
    RateLimited { retry_after_ms: u64 },
    #[error("provider error: {0}")]
    Provider(String),
    #[error("storage error: {0}")]
    Storage(String),
    #[error("operation cancelled")]
    Cancelled,
    #[error("internal error: {0}")]
    Internal(String),
}

impl DomainError {
    /// Whether retrying the same operation later may succeed.
    pub fn is_transient(&self) -> bool {
        matches!(self, Self::Network(_) | Self::RateLimited { .. })
    }
}
