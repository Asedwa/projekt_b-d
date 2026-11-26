//! Fallback artwork resolver using Deezer public search and iTunes Search APIs.

use std::time::Duration;

use serde::Deserialize;
use url::Url;

const DEEZER_EMPTY_HASH: &str = "d41d8cd98f00b204e9800998ecf8427e";
const ZERO_HASH: &str = "00000000000000000000000000000000";

pub fn is_placeholder_or_blank(url: Option<&str>) -> bool {
    let Some(u) = url.map(str::trim).filter(|s| !s.is_empty()) else {
        return true;
    };
    let lower = u.to_ascii_lowercase();
    lower.contains(DEEZER_EMPTY_HASH) || lower.contains(ZERO_HASH)
}

pub fn cache_key(title: &str, artist: Option<&str>) -> Option<String> {
    let t = title.trim().to_lowercase();
    if t.is_empty() {
        return None;
    }
    let a = artist.unwrap_or("").trim().to_lowercase();
    Some(format!("{a}|{t}"))
}

pub fn normalize_for_match(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    let mut paren_depth: usize = 0;
    let mut bracket_depth: usize = 0;
    let mut last_was_space = true;

    for ch in value.chars() {
        match ch {
            '(' => {
                paren_depth += 1;
                if !last_was_space {
                    out.push(' ');
                    last_was_space = true;
                }
            }
            ')' => {
                paren_depth = paren_depth.saturating_sub(1);
            }
            '[' => {
                bracket_depth += 1;
                if !last_was_space {
                    out.push(' ');
                    last_was_space = true;
                }
            }
            ']' => {
                bracket_depth = bracket_depth.saturating_sub(1);
            }
            _ if paren_depth > 0 || bracket_depth > 0 => {}
            _ if ch.is_alphanumeric() => {
                for lc in ch.to_lowercase() {
                    out.push(lc);
                }
                last_was_space = false;
            }
            _ => {
                if !last_was_space {
                    out.push(' ');
                    last_was_space = true;
                }
            }
        }
    }

    out.trim().to_string()
}

pub fn artist_tokens_match(expected_norm: &str, candidate_norm: &str) -> bool {
    if expected_norm.is_empty() {
        return true;
    }
    if candidate_norm.contains(expected_norm) || expected_norm.contains(candidate_norm) {
        return true;
    }
    let tokens: Vec<&str> = expected_norm
        .split(' ')
        .filter(|t| t.chars().count() >= 2)
        .collect();
    !tokens.is_empty() && tokens.iter().all(|t| candidate_norm.contains(t))
}

#[derive(Debug, Deserialize)]
struct DeezerSearchResponse {
    #[serde(default)]
    data: Vec<DeezerSearchItem>,
}

#[derive(Debug, Deserialize)]
struct DeezerSearchItem {
    #[serde(default)]
    title: String,
    artist: Option<DeezerSearchArtist>,
    album: Option<DeezerSearchAlbum>,
}

#[derive(Debug, Deserialize)]
struct DeezerSearchArtist {
    #[serde(default)]
    name: String,
}

#[derive(Debug, Deserialize)]
struct DeezerSearchAlbum {
    #[serde(default)]
    md5_image: String,
    cover_medium: Option<String>,
}

#[derive(Debug, Deserialize)]
struct ItunesSearchResponse {
    #[serde(default)]
    results: Vec<ItunesSearchItem>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ItunesSearchItem {
    #[serde(default)]
    track_name: String,
    #[serde(default)]
    artist_name: String,
    artwork_url100: Option<String>,
}

pub struct ArtworkClient {
    http: reqwest::Client,
}

impl Default for ArtworkClient {
    fn default() -> Self {
        Self::new()
    }
}

impl ArtworkClient {
    pub fn new() -> Self {
        let http = reqwest::Client::builder()
            .timeout(Duration::from_secs(4))
            .user_agent("PlasmaWave/1.0")
            .build()
            .unwrap_or_else(|_| reqwest::Client::new());
        Self { http }
    }

    pub async fn resolve(&self, title: &str, artist: Option<&str>) -> Option<String> {
        let clean_title = title.trim();
        if clean_title.is_empty() {
            return None;
        }
        if let Some(url) = self.query_deezer_search(clean_title, artist).await {
            return Some(url);
        }
        self.query_itunes_search(clean_title, artist).await
    }

    async fn query_deezer_search(&self, title: &str, artist: Option<&str>) -> Option<String> {
        let clean_artist = artist.unwrap_or("").trim();
        let query = if clean_artist.is_empty() {
            title.to_string()
        } else {
            format!("{title} {clean_artist}")
        };
        let url = Url::parse_with_params(
            "https://api.deezer.com/search",
            &[("q", query.as_str()), ("limit", "5")],
        )
        .ok()?;
        let body = self.get_ok_body(url).await?;
        parse_deezer_search_json(&body, title, clean_artist)
    }

    async fn query_itunes_search(&self, title: &str, artist: Option<&str>) -> Option<String> {
        let clean_artist = artist.unwrap_or("").trim();
        let query = if clean_artist.is_empty() {
            title.to_string()
        } else {
            format!("{title} {clean_artist}")
        };
        let url = Url::parse_with_params(
            "https://itunes.apple.com/search",
            &[
                ("term", query.as_str()),
                ("entity", "song"),
                ("limit", "3"),
            ],
        )
        .ok()?;
        let body = self.get_ok_body(url).await?;
        parse_itunes_search_json(&body, title, clean_artist)
    }

    async fn get_ok_body(&self, url: Url) -> Option<String> {
        let resp = self.http.get(url).send().await.ok()?;
        if !resp.status().is_success() {
            return None;
        }
        resp.text().await.ok()
    }
}

pub fn parse_deezer_search_json(body: &str, title: &str, clean_artist: &str) -> Option<String> {
    let parsed: DeezerSearchResponse = serde_json::from_str(body).ok()?;
    let title_norm = normalize_for_match(title);
    let artist_norm = normalize_for_match(clean_artist);

    let mut first_artist_match: Option<String> = None;
    for item in parsed.data {
        let Some(album) = item.album else { continue };
        let md5 = album.md5_image.trim();
        if md5.is_empty()
            || md5.eq_ignore_ascii_case(DEEZER_EMPTY_HASH)
            || md5 == ZERO_HASH
        {
            continue;
        }
        let cover = album
            .cover_medium
            .filter(|c| !is_placeholder_or_blank(Some(c)))
            .unwrap_or_else(|| {
                format!("https://e-cdns-images.dzcdn.net/images/cover/{md5}/250x250-000000-80-0-0.jpg")
            });

        let item_title = normalize_for_match(&item.title);
        let item_artist = normalize_for_match(
            item.artist
                .as_ref()
                .map(|a| a.name.as_str())
                .unwrap_or(""),
        );
        let title_matches = item_title.contains(&title_norm) || title_norm.contains(&item_title);
        let artist_matches = artist_tokens_match(&artist_norm, &item_artist);
        if title_matches && artist_matches {
            return Some(cover);
        }
        if first_artist_match.is_none() && artist_matches {
            first_artist_match = Some(cover);
        }
    }
    first_artist_match
}

pub fn parse_itunes_search_json(body: &str, title: &str, clean_artist: &str) -> Option<String> {
    let parsed: ItunesSearchResponse = serde_json::from_str(body).ok()?;
    let title_norm = normalize_for_match(title);
    let artist_norm = normalize_for_match(clean_artist);

    for item in parsed.results {
        let Some(art) = item
            .artwork_url100
            .filter(|u| u.starts_with("https://"))
        else {
            continue;
        };
        let item_title = normalize_for_match(&item.track_name);
        let item_artist = normalize_for_match(&item.artist_name);
        let title_matches = item_title.contains(&title_norm) || title_norm.contains(&item_title);
        let artist_matches = artist_tokens_match(&artist_norm, &item_artist);
        if title_matches && artist_matches {
            return Some(art.replace("100x100bb", "300x300bb"));
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn placeholder_and_normalization_work() {
        assert!(is_placeholder_or_blank(None));
        assert!(is_placeholder_or_blank(Some("")));
        assert!(is_placeholder_or_blank(Some(
            "https://e-cdns-images.dzcdn.net/images/cover/d41d8cd98f00b204e9800998ecf8427e/250x250.jpg"
        )));
        assert!(!is_placeholder_or_blank(Some(
            "https://e-cdns-images.dzcdn.net/images/cover/abcdef123456/250x250.jpg"
        )));

        assert_eq!(
            normalize_for_match("Make Believe (Remastered) [Live]"),
            "make believe"
        );
        assert!(artist_tokens_match("mother mother", "mother mother band"));
    }

    #[test]
    fn parses_deezer_and_itunes_responses() {
        let deezer_json = r#"{
            "data": [
                {
                    "title": "Make Believe",
                    "artist": { "name": "Mother Mother" },
                    "album": {
                        "md5_image": "11223344556677889900aabbccddeeff",
                        "cover_medium": "https://e-cdns-images.dzcdn.net/images/cover/11223344556677889900aabbccddeeff/250x250-000000-80-0-0.jpg"
                    }
                }
            ]
        }"#;
        assert_eq!(
            parse_deezer_search_json(deezer_json, "Make Believe", "Mother Mother"),
            Some("https://e-cdns-images.dzcdn.net/images/cover/11223344556677889900aabbccddeeff/250x250-000000-80-0-0.jpg".into())
        );

        let itunes_json = r#"{
            "results": [
                {
                    "trackName": "Make Believe",
                    "artistName": "Mother Mother",
                    "artworkUrl100": "https://is1-ssl.mzstatic.com/image/thumb/Music/100x100bb.jpg"
                }
            ]
        }"#;
        assert_eq!(
            parse_itunes_search_json(itunes_json, "Make Believe", "Mother Mother"),
            Some("https://is1-ssl.mzstatic.com/image/thumb/Music/300x300bb.jpg".into())
        );
    }
}
