//! GitHub Releases update checker, semver comparison, and trusted URL validation.

use std::cmp::Ordering;
use std::time::Duration;

use serde::Deserialize;
use thiserror::Error;
use url::Url;

const REPO_OWNER: &str = "Asedwa";
const REPO_NAME: &str = "projekt_b-d";
pub const RELEASES_PAGE_URL: &str = "https://github.com/Asedwa/projekt_b-d/releases/latest";

const ALLOWED_DOWNLOAD_HOSTS: &[&str] = &[
    "github.com",
    "api.github.com",
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com",
    "github-releases.githubusercontent.com",
];

#[derive(Debug, Error)]
pub enum UpdaterError {
    #[error("{0}")]
    Message(String),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AppUpdateRelease {
    pub version: String,
    pub tag_name: String,
    pub title: String,
    pub notes: String,
    pub apk_url: String,
    pub apk_size_bytes: u64,
    pub html_url: String,
}

#[derive(Debug, Deserialize)]
struct GitHubRelease {
    #[serde(default)]
    draft: bool,
    #[serde(default)]
    tag_name: String,
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    body: Option<String>,
    #[serde(default)]
    html_url: Option<String>,
    #[serde(default)]
    assets: Vec<GitHubAsset>,
}

#[derive(Debug, Deserialize)]
struct GitHubAsset {
    #[serde(default)]
    name: String,
    #[serde(default)]
    browser_download_url: String,
    #[serde(default)]
    size: u64,
}

pub fn is_trusted_download_url(raw_url: &str) -> bool {
    let Ok(parsed) = Url::parse(raw_url.trim()) else {
        return false;
    };
    if !parsed.scheme().eq_ignore_ascii_case("https") {
        return false;
    }
    let Some(host) = parsed.host_str() else {
        return false;
    };
    let normalized = host.trim_end_matches('.').to_ascii_lowercase();
    ALLOWED_DOWNLOAD_HOSTS.contains(&normalized.as_str())
        || normalized.ends_with(".githubusercontent.com")
}

pub fn extract_version(candidates: &[Option<&str>]) -> Option<String> {
    for raw in candidates.iter().flatten() {
        let trimmed = raw.trim();
        if trimmed.is_empty() {
            continue;
        }
        if let Some(found) = find_version_token(trimmed) {
            return Some(found);
        }
    }
    None
}

fn find_version_token(input: &str) -> Option<String> {
    let bytes = input.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i].is_ascii_digit() {
            let prev_is_word = i > 0 && is_word_byte(bytes[i - 1]);
            // Allow 'v' or 'V' prefix immediately before digits at a word boundary
            let has_v_prefix = i > 0
                && (bytes[i - 1] == b'v' || bytes[i - 1] == b'V')
                && (i == 1 || !is_word_byte(bytes[i - 2]));
            if !prev_is_word || has_v_prefix {
                if let Some((end, token)) = parse_version_at(&input[i..]) {
                    let next_is_word = i + end < bytes.len() && is_word_byte(bytes[i + end]);
                    if !next_is_word {
                        return Some(token.to_string());
                    }
                }
            }
        }
        i += 1;
    }
    None
}

fn is_word_byte(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b == b'_'
}

fn parse_version_at(s: &str) -> Option<(usize, &str)> {
    let bytes = s.as_bytes();
    let mut pos = 0;
    let d1 = scan_digits(bytes, pos)?;
    pos = d1;
    if bytes.get(pos) != Some(&b'.') {
        return None;
    }
    pos += 1;
    let d2 = scan_digits(bytes, pos)?;
    pos = d2;

    if bytes.get(pos) == Some(&b'.') {
        if let Some(d3) = scan_digits(bytes, pos + 1) {
            pos = d3;
        }
    }

    if matches!(bytes.get(pos), Some(b'-' | b'+')) && pos + 1 < bytes.len() {
        let mut p = pos + 1;
        while p < bytes.len()
            && (bytes[p].is_ascii_alphanumeric() || bytes[p] == b'.' || bytes[p] == b'-')
        {
            p += 1;
        }
        if p > pos + 1 {
            pos = p;
        }
    }

    Some((pos, &s[..pos]))
}

fn scan_digits(bytes: &[u8], start: usize) -> Option<usize> {
    let mut p = start;
    while p < bytes.len() && bytes[p].is_ascii_digit() {
        p += 1;
    }
    (p > start).then_some(p)
}

pub fn compare_versions(remote: &str, current: &str) -> Ordering {
    let (r_nums, r_pre) = parse_version_parts(remote);
    let (c_nums, c_pre) = parse_version_parts(current);
    let len = r_nums.len().max(c_nums.len());
    for i in 0..len {
        let r = *r_nums.get(i).unwrap_or(&0);
        let c = *c_nums.get(i).unwrap_or(&0);
        match r.cmp(&c) {
            Ordering::Equal => continue,
            ord => return ord,
        }
    }
    match (r_pre.is_empty(), c_pre.is_empty()) {
        (true, false) => Ordering::Greater,
        (false, true) => Ordering::Less,
        _ => r_pre.cmp(&c_pre),
    }
}

fn parse_version_parts(v: &str) -> (Vec<u32>, String) {
    let cleaned = v.trim().trim_start_matches(['v', 'V']);
    let without_build = cleaned.split('+').next().unwrap_or(cleaned);
    let (core, pre) = match without_build.split_once('-') {
        Some((c, p)) => (c, p.to_string()),
        None => (without_build, String::new()),
    };
    let nums = core
        .split('.')
        .map(|part| part.parse::<u32>().unwrap_or(0))
        .collect();
    (nums, pre)
}

pub fn is_remote_version_newer(remote: &str, current: &str) -> bool {
    compare_versions(remote, current) == Ordering::Greater
}

pub struct UpdaterClient {
    http: reqwest::Client,
}

impl Default for UpdaterClient {
    fn default() -> Self {
        Self::new()
    }
}

impl UpdaterClient {
    pub fn new() -> Self {
        let http = reqwest::Client::builder()
            .timeout(Duration::from_secs(30))
            .build()
            .unwrap_or_else(|_| reqwest::Client::new());
        Self { http }
    }

    pub async fn fetch_latest_release(
        &self,
        current_version: &str,
    ) -> Result<Option<AppUpdateRelease>, UpdaterError> {
        let url = format!(
            "https://api.github.com/repos/{REPO_OWNER}/{REPO_NAME}/releases/latest"
        );
        let resp = self
            .http
            .get(&url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", format!("projekt_b-d/{current_version}"))
            .send()
            .await
            .map_err(|e| UpdaterError::Message(e.to_string()))?;

        let status = resp.status().as_u16();
        if status == 404 {
            return Err(UpdaterError::Message(
                "Yayın bulunamadı (depo gizli veya sürüm yok)".into(),
            ));
        }
        if status == 403 {
            return Err(UpdaterError::Message(
                "GitHub API hız sınırına ulaşıldı, biraz sonra tekrar dene".into(),
            ));
        }
        if !resp.status().is_success() {
            return Err(UpdaterError::Message(format!(
                "Sunucu hatası (HTTP {status})"
            )));
        }

        let body = resp
            .text()
            .await
            .map_err(|e| UpdaterError::Message(e.to_string()))?;
        if body.trim().is_empty() {
            return Err(UpdaterError::Message("Boş sunucu yanıtı".into()));
        }

        parse_latest_release_json(&body, current_version)
    }
}

pub fn parse_latest_release_json(
    body: &str,
    current_version: &str,
) -> Result<Option<AppUpdateRelease>, UpdaterError> {
    let json: GitHubRelease =
        serde_json::from_str(body).map_err(|e| UpdaterError::Message(e.to_string()))?;
    if json.draft {
        return Ok(None);
    }

    let tag_name = json.tag_name;
    let title = json
        .name
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .unwrap_or(&tag_name)
        .to_string();
    let notes = json.body.unwrap_or_default().trim().to_string();
    let html_url = json
        .html_url
        .filter(|s| !s.trim().is_empty())
        .unwrap_or_else(|| RELEASES_PAGE_URL.to_string());

    let mut chosen_apk: Option<(String, String, u64)> = None;
    for asset in json.assets {
        if asset.name.to_ascii_lowercase().ends_with(".apk")
            && is_trusted_download_url(&asset.browser_download_url)
        {
            chosen_apk = Some((asset.browser_download_url, asset.name, asset.size));
            break;
        }
    }

    let Some((apk_url, apk_name, apk_size_bytes)) = chosen_apk else {
        return Ok(None);
    };

    let Some(remote_version) = extract_version(&[
        Some(title.as_str()),
        Some(apk_name.as_str()),
        Some(tag_name.as_str()),
    ]) else {
        return Ok(None);
    };

    if !is_remote_version_newer(&remote_version, current_version) {
        return Ok(None);
    }

    Ok(Some(AppUpdateRelease {
        version: remote_version,
        tag_name,
        title,
        notes,
        apk_url,
        apk_size_bytes,
        html_url,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn trusted_url_validation() {
        assert!(is_trusted_download_url(
            "https://github.com/Asedwa/projekt_b-d/releases/download/v0.12.3/projekt_b-d-0.12.3.apk"
        ));
        assert!(is_trusted_download_url(
            "https://objects.githubusercontent.com/github-production-release-asset/foo.apk"
        ));
        assert!(!is_trusted_download_url(
            "http://github.com/Asedwa/projekt_b-d/app.apk"
        ));
        assert!(!is_trusted_download_url(
            "https://evil.example.com/app.apk"
        ));
    }

    #[test]
    fn semver_comparison_and_extraction() {
        assert!(is_remote_version_newer("0.12.4", "0.12.3"));
        assert!(is_remote_version_newer("v0.13.0", "0.12.9"));
        assert!(is_remote_version_newer("0.12.4", "0.12.4-beta.1"));
        assert!(!is_remote_version_newer("0.12.3", "0.12.3"));
        assert!(!is_remote_version_newer("0.12.2", "0.12.3"));

        assert_eq!(
            extract_version(&[Some("projekt_b-d 0.12.4"), Some("v0.12.4")]),
            Some("0.12.4".into())
        );
        assert_eq!(
            extract_version(&[None, Some("projekt_b-d-0.12.4.apk")]),
            Some("0.12.4".into())
        );
    }

    #[test]
    fn parses_github_release_json() {
        let json = r#"{
            "draft": false,
            "tag_name": "v0.12.4",
            "name": "projekt_b-d 0.12.4",
            "body": "Release notes",
            "html_url": "https://github.com/Asedwa/projekt_b-d/releases/tag/v0.12.4",
            "assets": [
                {
                    "name": "projekt_b-d-0.12.4.apk",
                    "browser_download_url": "https://github.com/Asedwa/projekt_b-d/releases/download/v0.12.4/projekt_b-d-0.12.4.apk",
                    "size": 123456
                }
            ]
        }"#;
        let parsed = parse_latest_release_json(json, "0.12.3")
            .unwrap()
            .expect("should find update");
        assert_eq!(parsed.version, "0.12.4");
        assert_eq!(parsed.apk_size_bytes, 123456);
        assert!(parse_latest_release_json(json, "0.12.4").unwrap().is_none());
    }
}
