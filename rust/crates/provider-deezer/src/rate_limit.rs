use std::collections::VecDeque;
use std::time::Duration;

use tokio::sync::Mutex;
use tokio::time::Instant;

/// Sliding-window rate limiter. Deezer documents a quota of 50 requests per
/// 5 seconds for the public API; we stay just below it.
#[derive(Debug)]
pub(crate) struct RateLimiter {
    max: usize,
    window: Duration,
    hits: Mutex<VecDeque<Instant>>,
}

impl RateLimiter {
    pub(crate) fn new(max: usize, window: Duration) -> Self {
        Self {
            max: max.max(1),
            window,
            hits: Mutex::new(VecDeque::new()),
        }
    }

    pub(crate) fn deezer_default() -> Self {
        Self::new(45, Duration::from_secs(5))
    }

    /// Waits until a request slot is available, then claims it.
    pub(crate) async fn acquire(&self) {
        loop {
            let wait = {
                let mut hits = self.hits.lock().await;
                let now = Instant::now();
                while hits
                    .front()
                    .is_some_and(|t| now.duration_since(*t) >= self.window)
                {
                    hits.pop_front();
                }
                if hits.len() < self.max {
                    hits.push_back(now);
                    return;
                }
                self.window - now.duration_since(*hits.front().expect("non-empty"))
            };
            tokio::time::sleep(wait).await;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn blocks_when_window_full() {
        let rl = RateLimiter::new(2, Duration::from_millis(50));
        let start = Instant::now();
        rl.acquire().await;
        rl.acquire().await;
        assert!(start.elapsed() < Duration::from_millis(30));
        rl.acquire().await;
        assert!(start.elapsed() >= Duration::from_millis(45));
    }
}
