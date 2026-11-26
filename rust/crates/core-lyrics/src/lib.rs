//! LRCLIB lyrics client and LRC timestamp parser.

use std::time::Duration;

use serde::{Deserialize, Serialize};
use thiserror::Error;
use url::Url;

#[derive(Debug, Error)]
pub enum LyricsError {
    #[error("network error: {0}")]
    Network(String),
    #[error("invalid response: {0}")]
    InvalidResponse(String),
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct LyricLine {
    pub time_ms: u64,
    pub text: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum LyricsResult {
    Synced { lines: Vec<LyricLine> },
    Plain { text: String },
    Instrumental,
    NotFound,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct LrclibEntry {
    #[serde(default)]
    instrumental: bool,
    synced_lyrics: Option<String>,
    plain_lyrics: Option<String>,
}

pub struct LyricsClient {
    http: reqwest::Client,
}

impl Default for LyricsClient {
    fn default() -> Self {
        Self::new()
    }
}

impl LyricsClient {
    pub fn new() -> Self {
        let http = reqwest::Client::builder()
            .timeout(Duration::from_secs(7))
            .user_agent("PlasmaWave/1.0 (Android)")
            .build()
            .unwrap_or_else(|_| reqwest::Client::new());
        Self { http }
    }

    pub fn cache_key(title: &str, artist: &str) -> String {
        format!(
            "{}|{}",
            artist.trim().to_lowercase(),
            title.trim().to_lowercase()
        )
    }

    pub async fn fetch_lyrics(
        &self,
        title: &str,
        artist: &str,
        album: Option<&str>,
        duration_ms: u64,
    ) -> Result<LyricsResult, LyricsError> {
        let clean_title = title.trim();
        let clean_artist = artist.trim();
        if clean_title.is_empty() && clean_artist.is_empty() {
            return Ok(LyricsResult::NotFound);
        }

        let mut params = vec![
            ("track_name", clean_title.to_string()),
            ("artist_name", clean_artist.to_string()),
        ];
        if let Some(alb) = album.map(str::trim).filter(|s| !s.is_empty()) {
            params.push(("album_name", alb.to_string()));
        }
        let duration_sec = duration_ms / 1000;
        if duration_sec > 0 {
            params.push(("duration", duration_sec.to_string()));
        }

        let get_url = Url::parse_with_params("https://lrclib.net/api/get", &params)
            .map_err(|e| LyricsError::InvalidResponse(e.to_string()))?;

        if let Some(body) = self.get_ok_body(get_url).await? {
            if let Ok(entry) = serde_json::from_str::<LrclibEntry>(&body) {
                let parsed = parse_lrclib_entry(&entry);
                if parsed != LyricsResult::NotFound {
                    return Ok(parsed);
                }
            }
        }

        let search_url = Url::parse_with_params(
            "https://lrclib.net/api/search",
            &[("track_name", clean_title), ("artist_name", clean_artist)],
        )
        .map_err(|e| LyricsError::InvalidResponse(e.to_string()))?;

        if let Some(body) = self.get_ok_body(search_url).await? {
            if let Ok(entries) = serde_json::from_str::<Vec<LrclibEntry>>(&body) {
                let mut best_plain: Option<LyricsResult> = None;
                for entry in &entries {
                    let parsed = parse_lrclib_entry(entry);
                    if matches!(parsed, LyricsResult::Synced { .. }) {
                        return Ok(parsed);
                    }
                    if best_plain.is_none() && parsed != LyricsResult::NotFound {
                        best_plain = Some(parsed);
                    }
                }
                if let Some(found) = best_plain {
                    return Ok(found);
                }
            }
        }

        Ok(LyricsResult::NotFound)
    }

    async fn get_ok_body(&self, url: Url) -> Result<Option<String>, LyricsError> {
        let resp = self
            .http
            .get(url)
            .header("Accept", "application/json")
            .send()
            .await
            .map_err(|e| LyricsError::Network(e.to_string()))?;

        if resp.status().is_success() {
            let text = resp
                .text()
                .await
                .map_err(|e| LyricsError::Network(e.to_string()))?;
            Ok(Some(text))
        } else if resp.status().as_u16() == 404 {
            Ok(None)
        } else if resp.status().is_server_error() {
            Err(LyricsError::Network(format!(
                "LRCLIB server error ({})",
                resp.status().as_u16()
            )))
        } else {
            Ok(None)
        }
    }
}

fn parse_lrclib_entry(entry: &LrclibEntry) -> LyricsResult {
    if entry.instrumental {
        return LyricsResult::Instrumental;
    }
    if let Some(synced) = entry
        .synced_lyrics
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
    {
        let lines = parse_lrc(synced);
        if !lines.is_empty() {
            return LyricsResult::Synced { lines };
        }
    }
    if let Some(plain) = entry
        .plain_lyrics
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
    {
        return LyricsResult::Plain {
            text: plain.to_string(),
        };
    }
    LyricsResult::NotFound
}

/// Parses LRC-formatted lyrics into time-sorted `LyricLine`s.
/// Supports `[mm:ss]`, `[mm:ss.xx]`, `[mm:ss.xxx]`, `[mm:ss:xx]` and multiple tags per line.
pub fn parse_lrc(raw: &str) -> Vec<LyricLine> {
    let mut result = Vec::new();

    for line in raw.lines() {
        let mut rest = line.trim_start();
        let mut timestamps = Vec::new();

        loop {
            if let Some((ms, after)) = parse_leading_lrc_tag(rest) {
                timestamps.push(ms);
                rest = after.trim_start();
            } else {
                break;
            }
        }

        if timestamps.is_empty() {
            continue;
        }
        let text = strip_inline_lrc_tags(rest).trim().to_string();
        if text.is_empty() {
            continue;
        }
        for time_ms in timestamps {
            result.push(LyricLine {
                time_ms,
                text: text.clone(),
            });
        }
    }

    result.sort_by_key(|l| l.time_ms);
    result
}

fn parse_leading_lrc_tag(input: &str) -> Option<(u64, &str)> {
    let inner_start = input.strip_prefix('[')?;
    let close_idx = inner_start.find(']')?;
    let tag = &inner_start[..close_idx];
    let ms = parse_lrc_timestamp(tag)?;
    Some((ms, &inner_start[close_idx + 1..]))
}

fn strip_inline_lrc_tags(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    let mut cursor = input;
    while let Some(open) = cursor.find('[') {
        out.push_str(&cursor[..open]);
        let after_open = &cursor[open + 1..];
        if let Some(close) = after_open.find(']') {
            let tag = &after_open[..close];
            if parse_lrc_timestamp(tag).is_some() {
                cursor = &after_open[close + 1..];
                continue;
            }
        }
        out.push('[');
        cursor = after_open;
    }
    out.push_str(cursor);
    out
}

fn parse_lrc_timestamp(tag: &str) -> Option<u64> {
    let (min_str, rest) = tag.split_once(':')?;
    if min_str.is_empty() || min_str.len() > 3 || !min_str.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    let min: u64 = min_str.parse().ok()?;

    let (sec_str, frac_str) = match rest.split_once(['.', ':']) {
        Some((s, f)) => (s, Some(f)),
        None => (rest, None),
    };
    if sec_str.len() != 2 || !sec_str.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    let sec: u64 = sec_str.parse().ok()?;

    let ms_part = match frac_str {
        None => 0,
        Some(f) => {
            if f.is_empty() || f.len() > 3 || !f.bytes().all(|b| b.is_ascii_digit()) {
                return None;
            }
            let val: u64 = f.parse().ok()?;
            match f.len() {
                1 => val * 100,
                2 => val * 10,
                _ => val,
            }
        }
    };

    Some(min * 60_000 + sec * 1_000 + ms_part)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_lrc_timestamps_and_multi_tags() {
        let raw = "\
[ti:Test]
[00:12.34]First line
[00:45.67][01:10.5]Chorus
[00:05]Intro
[00:20:080]Colon millis
[00:30.00]
";
        let lines = parse_lrc(raw);
        assert_eq!(
            lines,
            vec![
                LyricLine {
                    time_ms: 5_000,
                    text: "Intro".into()
                },
                LyricLine {
                    time_ms: 12_340,
                    text: "First line".into()
                },
                LyricLine {
                    time_ms: 20_080,
                    text: "Colon millis".into()
                },
                LyricLine {
                    time_ms: 45_670,
                    text: "Chorus".into()
                },
                LyricLine {
                    time_ms: 70_500,
                    text: "Chorus".into()
                },
            ]
        );
    }

    #[test]
    fn parses_lrclib_entry_variants() {
        let inst = LrclibEntry {
            instrumental: true,
            synced_lyrics: Some("[00:01.00]x".into()),
            plain_lyrics: None,
        };
        assert_eq!(parse_lrclib_entry(&inst), LyricsResult::Instrumental);

        let synced = LrclibEntry {
            instrumental: false,
            synced_lyrics: Some("[00:01.50]Hello".into()),
            plain_lyrics: Some("Hello".into()),
        };
        assert_eq!(
            parse_lrclib_entry(&synced),
            LyricsResult::Synced {
                lines: vec![LyricLine {
                    time_ms: 1_500,
                    text: "Hello".into()
                }]
            }
        );

        let plain = LrclibEntry {
            instrumental: false,
            synced_lyrics: None,
            plain_lyrics: Some("  Plain text  ".into()),
        };
        assert_eq!(
            parse_lrclib_entry(&plain),
            LyricsResult::Plain {
                text: "Plain text".into()
            }
        );
    }
}
