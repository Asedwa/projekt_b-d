use serde::{Deserialize, Serialize};

use crate::{PlaylistId, ProviderId, TrackId};

/// Where a piece of data came from. The UI must label anything that is not
/// `Real` so development fixtures are never mistaken for account data.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DataOrigin {
    /// Real data from the user's device or an authorized provider.
    Real,
    /// Public, unauthenticated catalog data (not tied to a user account).
    PublicCatalog,
    /// Synthetic development fixtures.
    DevelopmentFixture,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Artist {
    pub id: String,
    pub name: String,
    pub picture_url: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Album {
    pub id: String,
    pub title: String,
    pub artist_name: String,
    pub cover_url: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Track {
    pub id: TrackId,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub duration_ms: u64,
    pub artwork_url: Option<String>,
    /// URI that the platform player can open, if playback is authorized.
    /// For previews this points at the provider's preview clip.
    pub playback_uri: Option<String>,
    /// True if `playback_uri` is only a short preview clip.
    pub is_preview: bool,
    pub origin: DataOrigin,
}

impl Track {
    pub fn provider(&self) -> &ProviderId {
        self.id.provider()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PlaylistKind {
    /// Stored locally in the app database.
    Local,
    /// Belongs to a remote provider account.
    Remote,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Playlist {
    pub id: PlaylistId,
    pub name: String,
    pub description: Option<String>,
    pub kind: PlaylistKind,
    pub track_count: u32,
    pub artwork_url: Option<String>,
    pub origin: DataOrigin,
}

pub const MAX_PAGE_SIZE: u32 = 100;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct PageRequest {
    pub offset: u32,
    pub limit: u32,
}

impl PageRequest {
    pub fn new(offset: u32, limit: u32) -> Self {
        Self {
            offset,
            limit: limit.clamp(1, MAX_PAGE_SIZE),
        }
    }

    pub fn first(limit: u32) -> Self {
        Self::new(0, limit)
    }
}

impl Default for PageRequest {
    fn default() -> Self {
        Self::first(25)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Page<T> {
    pub items: Vec<T>,
    pub offset: u32,
    pub total: Option<u32>,
    pub has_more: bool,
}

impl<T> Page<T> {
    pub fn single(items: Vec<T>) -> Self {
        Self {
            offset: 0,
            total: Some(items.len() as u32),
            items,
            has_more: false,
        }
    }

    /// Paginates an in-memory slice.
    pub fn from_slice(all: &[T], req: PageRequest) -> Self
    where
        T: Clone,
    {
        let start = (req.offset as usize).min(all.len());
        let end = (start + req.limit as usize).min(all.len());
        Self {
            items: all[start..end].to_vec(),
            offset: req.offset,
            total: Some(all.len() as u32),
            has_more: end < all.len(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn page_request_clamps_limit() {
        assert_eq!(PageRequest::new(0, 0).limit, 1);
        assert_eq!(PageRequest::new(0, 10_000).limit, MAX_PAGE_SIZE);
    }

    #[test]
    fn page_from_slice() {
        let data: Vec<u32> = (0..10).collect();
        let p = Page::from_slice(&data, PageRequest::new(8, 5));
        assert_eq!(p.items, vec![8, 9]);
        assert!(!p.has_more);
        let p = Page::from_slice(&data, PageRequest::new(0, 5));
        assert!(p.has_more);
        let p = Page::from_slice(&data, PageRequest::new(50, 5));
        assert!(p.items.is_empty());
    }
}
