//! Logical playback queue.
//!
//! The Rust queue is the single source of truth for *what* plays next. The
//! Android Media3 session owns *how* the current item is played. The platform
//! layer asks the queue for the next/previous entry and reports completion.
//!
//! Entries have stable `entry_id`s so the same track can appear in the queue
//! more than once and UI operations (remove, move) are unambiguous.

mod rng;

use core_domain::Track;
use serde::{Deserialize, Serialize};

use rng::XorShift64;

/// Restart the current track instead of going back when the playback position
/// is beyond this threshold.
pub const PREVIOUS_RESTART_THRESHOLD_MS: u64 = 3_000;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RepeatMode {
    #[default]
    Off,
    One,
    All,
}

impl RepeatMode {
    /// Off → All → One → Off, matching common player UIs.
    pub fn cycled(self) -> Self {
        match self {
            Self::Off => Self::All,
            Self::All => Self::One,
            Self::One => Self::Off,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct QueueEntry {
    pub entry_id: u64,
    pub track: Track,
}

/// Why `advance` is being called.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AdvanceReason {
    /// The current track finished playing on its own.
    TrackEnded,
    /// The user explicitly pressed "next".
    UserSkip,
}

/// Result of a "previous" request.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PreviousOutcome {
    /// Seek the current entry back to the start.
    RestartCurrent,
    /// The current entry changed.
    Moved(QueueEntry),
    /// Queue is empty.
    Nothing,
}

/// Serializable snapshot used for the UI and for persisting across process
/// death.
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
pub struct QueueSnapshot {
    pub entries: Vec<QueueEntry>,
    pub current_index: Option<usize>,
    pub repeat: RepeatMode,
    pub shuffle: bool,
    /// Entry ids in unshuffled order, present only while shuffle is enabled.
    pub original_order: Option<Vec<u64>>,
    pub next_entry_id: u64,
}

#[derive(Debug, Clone)]
pub struct Queue {
    entries: Vec<QueueEntry>,
    current: Option<usize>,
    repeat: RepeatMode,
    /// `Some` while shuffle is on: the unshuffled order of entry ids.
    original_order: Option<Vec<u64>>,
    next_entry_id: u64,
    rng: XorShift64,
}

impl Default for Queue {
    fn default() -> Self {
        Self::new(0x5EED_1234_ABCD_0001)
    }
}

impl Queue {
    pub fn new(seed: u64) -> Self {
        Self {
            entries: Vec::new(),
            current: None,
            repeat: RepeatMode::Off,
            original_order: None,
            next_entry_id: 1,
            rng: XorShift64::new(seed),
        }
    }

    pub fn restore(snapshot: QueueSnapshot, seed: u64) -> Self {
        let len = snapshot.entries.len();
        let max_id = snapshot
            .entries
            .iter()
            .map(|e| e.entry_id)
            .max()
            .unwrap_or(0);
        Self {
            current: snapshot.current_index.filter(|i| *i < len),
            entries: snapshot.entries,
            repeat: snapshot.repeat,
            original_order: if snapshot.shuffle {
                Some(snapshot.original_order.unwrap_or_default())
            } else {
                None
            },
            next_entry_id: snapshot.next_entry_id.max(max_id + 1),
            rng: XorShift64::new(seed),
        }
    }

    pub fn snapshot(&self) -> QueueSnapshot {
        QueueSnapshot {
            entries: self.entries.clone(),
            current_index: self.current,
            repeat: self.repeat,
            shuffle: self.original_order.is_some(),
            original_order: self.original_order.clone(),
            next_entry_id: self.next_entry_id,
        }
    }

    pub fn entries(&self) -> &[QueueEntry] {
        &self.entries
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    pub fn current(&self) -> Option<&QueueEntry> {
        self.current.and_then(|i| self.entries.get(i))
    }

    pub fn current_index(&self) -> Option<usize> {
        self.current
    }

    pub fn repeat(&self) -> RepeatMode {
        self.repeat
    }

    pub fn set_repeat(&mut self, mode: RepeatMode) {
        self.repeat = mode;
    }

    pub fn is_shuffled(&self) -> bool {
        self.original_order.is_some()
    }

    fn wrap(&mut self, tracks: Vec<Track>) -> Vec<QueueEntry> {
        tracks
            .into_iter()
            .map(|track| {
                let entry_id = self.next_entry_id;
                self.next_entry_id += 1;
                QueueEntry { entry_id, track }
            })
            .collect()
    }

    fn index_of(&self, entry_id: u64) -> Option<usize> {
        self.entries.iter().position(|e| e.entry_id == entry_id)
    }

    /// Replaces the queue and starts at `start_index` (clamped).
    pub fn replace(&mut self, tracks: Vec<Track>, start_index: usize) -> Option<&QueueEntry> {
        self.entries = self.wrap(tracks);
        self.current = if self.entries.is_empty() {
            None
        } else {
            Some(start_index.min(self.entries.len() - 1))
        };
        if self.original_order.is_some() {
            self.original_order = Some(self.entries.iter().map(|e| e.entry_id).collect());
            self.shuffle_upcoming(true);
        }
        self.current()
    }

    /// Inserts tracks directly after the current entry.
    pub fn play_next(&mut self, tracks: Vec<Track>) -> Vec<u64> {
        let new = self.wrap(tracks);
        let ids: Vec<u64> = new.iter().map(|e| e.entry_id).collect();
        let at = self.current.map_or(0, |i| i + 1).min(self.entries.len());
        let cur_id = self.current().map(|e| e.entry_id);
        if let Some(order) = self.original_order.as_mut() {
            let pos = cur_id
                .and_then(|cid| order.iter().position(|id| *id == cid))
                .map_or(0, |p| p + 1);
            order.splice(pos..pos, ids.iter().copied());
        }
        self.entries.splice(at..at, new);
        if self.current.is_none() && !self.entries.is_empty() {
            self.current = Some(0);
        }
        ids
    }

    /// Appends tracks to the end of the queue.
    pub fn add_to_queue(&mut self, tracks: Vec<Track>) -> Vec<u64> {
        let new = self.wrap(tracks);
        let ids: Vec<u64> = new.iter().map(|e| e.entry_id).collect();
        if let Some(order) = self.original_order.as_mut() {
            order.extend(ids.iter().copied());
        }
        self.entries.extend(new);
        if self.current.is_none() && !self.entries.is_empty() {
            self.current = Some(0);
        }
        ids
    }

    /// Removes an entry. Returns `true` if the *current* entry was removed (the
    /// player must then load the new current entry, if any).
    pub fn remove(&mut self, entry_id: u64) -> Option<bool> {
        let idx = self.index_of(entry_id)?;
        self.entries.remove(idx);
        if let Some(order) = self.original_order.as_mut() {
            order.retain(|id| *id != entry_id);
        }
        let mut current_removed = false;
        self.current = match self.current {
            _ if self.entries.is_empty() => {
                current_removed = true;
                None
            }
            Some(c) if c == idx => {
                current_removed = true;
                if idx < self.entries.len() {
                    Some(idx)
                } else if self.repeat == RepeatMode::All {
                    Some(0)
                } else {
                    Some(self.entries.len() - 1)
                }
            }
            Some(c) if c > idx => Some(c - 1),
            other => other,
        };
        Some(current_removed)
    }

    /// Moves an entry from one position to another (both indices refer to the
    /// visible order). The current entry keeps playing.
    pub fn move_entry(&mut self, from: usize, to: usize) -> bool {
        let len = self.entries.len();
        if from >= len || to >= len {
            return false;
        }
        if from == to {
            return true;
        }
        let current_id = self.current().map(|e| e.entry_id);
        let entry = self.entries.remove(from);
        self.entries.insert(to, entry);
        self.current = current_id.and_then(|id| self.index_of(id));
        true
    }

    /// Makes the given entry current.
    pub fn jump_to(&mut self, entry_id: u64) -> Option<&QueueEntry> {
        let idx = self.index_of(entry_id)?;
        self.current = Some(idx);
        self.current()
    }

    /// Advances to the next entry. Returns `None` when playback should stop.
    pub fn advance(&mut self, reason: AdvanceReason) -> Option<&QueueEntry> {
        let cur = self.current?;
        if reason == AdvanceReason::TrackEnded && self.repeat == RepeatMode::One {
            return self.current();
        }
        if cur + 1 < self.entries.len() {
            self.current = Some(cur + 1);
            return self.current();
        }
        match self.repeat {
            RepeatMode::All | RepeatMode::One => {
                if self.original_order.is_some() {
                    // New shuffle pass for the next cycle.
                    self.current = Some(0);
                    self.shuffle_upcoming(false);
                }
                self.current = Some(0);
                self.current()
            }
            RepeatMode::Off => None,
        }
    }

    /// Handles "previous". `position_ms` is the current playback position.
    pub fn previous(&mut self, position_ms: u64) -> PreviousOutcome {
        let Some(cur) = self.current else {
            return PreviousOutcome::Nothing;
        };
        if position_ms > PREVIOUS_RESTART_THRESHOLD_MS {
            return PreviousOutcome::RestartCurrent;
        }
        let target = if cur > 0 {
            cur - 1
        } else if self.repeat == RepeatMode::All {
            self.entries.len() - 1
        } else {
            return PreviousOutcome::RestartCurrent;
        };
        self.current = Some(target);
        PreviousOutcome::Moved(self.entries[target].clone())
    }

    /// Enables or disables shuffle. Enabling keeps the current entry and
    /// randomises all entries after it; disabling restores the original order.
    pub fn set_shuffle(&mut self, enabled: bool) {
        match (enabled, self.original_order.is_some()) {
            (true, false) => {
                self.original_order = Some(self.entries.iter().map(|e| e.entry_id).collect());
                self.shuffle_upcoming(true);
            }
            (false, true) => {
                let order = self.original_order.take().unwrap_or_default();
                let current_id = self.current().map(|e| e.entry_id);
                let rank = |id: u64| order.iter().position(|o| *o == id).unwrap_or(usize::MAX);
                self.entries.sort_by_key(|e| rank(e.entry_id));
                self.current = current_id.and_then(|id| self.index_of(id));
            }
            _ => {}
        }
    }

    /// Shuffles entries. With `keep_current_first`, the current entry is moved
    /// to the front and the rest are shuffled after it.
    fn shuffle_upcoming(&mut self, keep_current_first: bool) {
        if self.entries.len() < 2 {
            return;
        }
        let start = match (keep_current_first, self.current) {
            (true, Some(c)) => {
                let entry = self.entries.remove(c);
                self.entries.insert(0, entry);
                self.current = Some(0);
                1
            }
            _ => 0,
        };
        // Fisher–Yates on entries[start..].
        let slice = &mut self.entries[start..];
        for i in (1..slice.len()).rev() {
            let j = self.rng.next_below(i as u64 + 1) as usize;
            slice.swap(i, j);
        }
    }
}

#[cfg(test)]
mod tests;
