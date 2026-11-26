//! Authenticated Deezer web-player (`gw-light.php` & `media.deezer.com`) client.

use std::sync::Mutex;
use std::time::Duration;

use serde_json::{json, Value};
use thiserror::Error;
use url::Url;

const GW_API_BASE: &str = "https://www.deezer.com/ajax/gw-light.php";
const MEDIA_GET_URL: &str = "https://media.deezer.com/v1/get_url";

#[derive(Debug, Error)]
pub enum GatewayError {
    #[error("{0}")]
    Message(String),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GatewayAccount {
    pub id: String,
    pub name: String,
    pub plan: String,
    pub high_quality: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GatewayMedia {
    pub url: String,
    pub track_id: String,
    pub format: String,
}

#[derive(Default)]
struct GatewaySessionState {
    sid: String,
    api_token: String,
    license_token: String,
    account: Option<GatewayAccount>,
}

pub struct DeezerGatewayClient {
    arl: String,
    http: reqwest::Client,
    state: Mutex<GatewaySessionState>,
}

impl DeezerGatewayClient {
    pub fn new(arl: String) -> Self {
        let http = reqwest::Client::builder()
            .timeout(Duration::from_secs(15))
            .user_agent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
            .build()
            .unwrap_or_else(|_| reqwest::Client::new());
        Self {
            arl,
            http,
            state: Mutex::new(GatewaySessionState::default()),
        }
    }

    pub async fn login(&self) -> Result<GatewayAccount, GatewayError> {
        let (result, sid_update) = self
            .post_gw("deezer.getUserData", json!({}), false)
            .await?;

        let user = result
            .get("USER")
            .and_then(|v| v.as_object())
            .ok_or_else(|| GatewayError::Message("Deezer oturumu doğrulanamadı".into()))?;

        let id = json_id_str(user.get("USER_ID"));
        let token = result
            .get("checkForm")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();

        if id.is_empty() || id == "0" || token.is_empty() {
            return Err(GatewayError::Message(
                "Deezer oturumu geçersiz veya süresi dolmuş".into(),
            ));
        }

        let options = user.get("OPTIONS").and_then(|v| v.as_object());
        let high_quality = options
            .map(|o| {
                o.get("web_hq").and_then(|v| v.as_bool()).unwrap_or(false)
                    || o.get("mobile_hq").and_then(|v| v.as_bool()).unwrap_or(false)
                    || o.get("web_lossless").and_then(|v| v.as_bool()).unwrap_or(false)
                    || o.get("mobile_lossless")
                        .and_then(|v| v.as_bool())
                        .unwrap_or(false)
            })
            .unwrap_or(false);

        let offer = result
            .get("OFFERS")
            .and_then(|v| v.as_array())
            .and_then(|a| a.first())
            .and_then(|o| o.get("title"))
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();

        let license_token = options
            .and_then(|o| o.get("license_token"))
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();

        let name = user
            .get("BLOG_NAME")
            .and_then(|v| v.as_str())
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .or_else(|| {
                user.get("FIRSTNAME")
                    .and_then(|v| v.as_str())
                    .map(str::trim)
                    .filter(|s| !s.is_empty())
            })
            .unwrap_or("Deezer")
            .to_string();

        let plan = if offer.is_empty() {
            if high_quality {
                "Deezer Premium".to_string()
            } else {
                "Deezer".to_string()
            }
        } else {
            offer
        };

        let account = GatewayAccount {
            id,
            name,
            plan,
            high_quality,
        };

        if let Ok(mut guard) = self.state.lock() {
            if let Some(sid) = sid_update {
                guard.sid = sid;
            }
            guard.api_token = token;
            guard.license_token = license_token;
            guard.account = Some(account.clone());
        }

        Ok(account)
    }

    async fn ensure_account(&self) -> Result<GatewayAccount, GatewayError> {
        if let Ok(guard) = self.state.lock() {
            if let Some(ref acc) = guard.account {
                return Ok(acc.clone());
            }
        }
        self.login().await
    }

    pub async fn set_favorite(&self, track_id: &str, favorite: bool) -> Result<(), GatewayError> {
        require_numeric_id(track_id)?;
        self.ensure_account().await?;
        let method = if favorite {
            "favorite_song.add"
        } else {
            "favorite_song.remove"
        };
        self.call_with_refresh(method, json!({ "SNG_ID": track_id }))
            .await?;
        Ok(())
    }

    pub async fn edit_playlist(
        &self,
        playlist_id: &str,
        track_id: &str,
        add: bool,
    ) -> Result<(), GatewayError> {
        require_numeric_id(playlist_id)?;
        require_numeric_id(track_id)?;
        self.ensure_account().await?;
        let mut body = json!({
            "playlist_id": playlist_id,
            "songs": [[track_id, 0]]
        });
        if add {
            if let Some(obj) = body.as_object_mut() {
                obj.insert("offset".into(), json!(-1));
            }
        }
        let method = if add {
            "playlist.addSongs"
        } else {
            "playlist.deleteSongs"
        };
        self.call_with_refresh(method, body).await?;
        Ok(())
    }

    pub async fn create_playlist(&self, name: &str) -> Result<String, GatewayError> {
        let title = name.trim();
        if title.is_empty() || title.chars().count() > 200 {
            return Err(GatewayError::Message("Geçersiz liste adı".into()));
        }
        self.ensure_account().await?;
        let result = self
            .call_with_refresh(
                "playlist.create",
                json!({
                    "title": title,
                    "description": "",
                    "songs": [],
                    "status": 1
                }),
            )
            .await?;
        let id = result
            .get("value")
            .map(|v| json_id_str(Some(v)))
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| json_id_str(result.get("PLAYLIST_ID")));
        require_numeric_id(&id)?;
        Ok(id)
    }

    pub async fn delete_playlist(&self, playlist_id: &str) -> Result<(), GatewayError> {
        require_numeric_id(playlist_id)?;
        self.ensure_account().await?;
        self.call_with_refresh("playlist.delete", json!({ "playlist_id": playlist_id }))
            .await?;
        Ok(())
    }

    pub async fn resolve_media(
        &self,
        track_id: &str,
        exact_format: Option<&str>,
    ) -> Result<GatewayMedia, GatewayError> {
        require_numeric_id(track_id)?;
        if let Some(fmt) = exact_format {
            if !matches!(fmt, "FLAC" | "MP3_320" | "MP3_128") {
                return Err(GatewayError::Message("Geçersiz ses biçimi".into()));
            }
        }
        let account = self.ensure_account().await?;

        for attempt in 0..2 {
            let track = self
                .call_with_refresh("song.getData", json!({ "sng_id": track_id }))
                .await?;

            let mut candidates: Vec<(String, String)> = Vec::new();
            if let Some(tok) = track.get("TRACK_TOKEN").and_then(|v| v.as_str()) {
                if !tok.is_empty() {
                    candidates.push((track_id.to_string(), tok.to_string()));
                }
            }
            if let Some(fb) = track.get("FALLBACK") {
                let fb_id = json_id_str(fb.get("SNG_ID"));
                let fb_tok = fb
                    .get("TRACK_TOKEN")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string();
                if !fb_id.is_empty() && fb_id.bytes().all(|b| b.is_ascii_digit()) && !fb_tok.is_empty()
                {
                    candidates.push((fb_id, fb_tok));
                }
            }

            let license_token = self
                .state
                .lock()
                .map(|g| g.license_token.clone())
                .unwrap_or_default();

            if !license_token.is_empty() {
                for (cid, ctok) in candidates {
                    if let Some(media) = self
                        .request_media_url(&cid, &ctok, &license_token, account.high_quality, exact_format)
                        .await?
                    {
                        return Ok(media);
                    }
                }
            }
            if attempt == 0 {
                let _ = self.login().await;
            }
        }

        Err(GatewayError::Message(match exact_format {
            None => "Bu şarkının tamamı hesapta kullanılamıyor".into(),
            Some(fmt) => format!("Bu şarkı seçilen {fmt} biçiminde hesabında kullanılamıyor"),
        }))
    }

    async fn request_media_url(
        &self,
        track_id: &str,
        track_token: &str,
        license_token: &str,
        high_quality: bool,
        exact_format: Option<&str>,
    ) -> Result<Option<GatewayMedia>, GatewayError> {
        let requested: Vec<&str> = match exact_format {
            Some(fmt) => vec![fmt],
            None => {
                let mut v = Vec::new();
                if high_quality {
                    v.push("MP3_320");
                }
                v.extend(["MP3_128", "MP3_64", "MP3_MISC"]);
                v
            }
        };
        let formats: Vec<Value> = requested
            .into_iter()
            .map(|f| json!({ "cipher": "BF_CBC_STRIPE", "format": f }))
            .collect();
        let payload = json!({
            "license_token": license_token,
            "media": [{ "type": "FULL", "formats": formats }],
            "track_tokens": [track_token]
        });

        let resp = self
            .http
            .post(MEDIA_GET_URL)
            .header("Accept", "application/json")
            .json(&payload)
            .send()
            .await
            .map_err(|e| GatewayError::Message(e.to_string()))?;

        let raw = resp
            .text()
            .await
            .map_err(|e| GatewayError::Message(e.to_string()))?;
        let parsed: Value =
            serde_json::from_str(&raw).map_err(|_| GatewayError::Message("Deezer yanıtı okunamadı".into()))?;
        parse_media_from_get_url(&parsed, track_id, exact_format)
    }

    async fn call_with_refresh(&self, method: &str, body: Value) -> Result<Value, GatewayError> {
        match self.post_gw(method, body.clone(), true).await {
            Ok((val, sid_update)) => {
                if let Some(sid) = sid_update {
                    if let Ok(mut g) = self.state.lock() {
                        g.sid = sid;
                    }
                }
                Ok(val)
            }
            Err(GatewayError::Message(msg)) if msg == "__TOKEN_EXPIRED__" => {
                self.login().await?;
                let (val, sid_update) = self.post_gw(method, body, true).await?;
                if let Some(sid) = sid_update {
                    if let Ok(mut g) = self.state.lock() {
                        g.sid = sid;
                    }
                }
                Ok(val)
            }
            Err(e) => Err(e),
        }
    }

    async fn post_gw(
        &self,
        method: &str,
        body: Value,
        authenticated: bool,
    ) -> Result<(Value, Option<String>), GatewayError> {
        let (token, sid) = self
            .state
            .lock()
            .map(|g| {
                let t = if authenticated && !g.api_token.is_empty() {
                    g.api_token.clone()
                } else {
                    "null".to_string()
                };
                (t, g.sid.clone())
            })
            .unwrap_or_else(|_| ("null".to_string(), String::new()));

        let url = Url::parse_with_params(
            GW_API_BASE,
            &[
                ("method", method),
                ("input", "3"),
                ("api_version", "1.0"),
                ("api_token", token.as_str()),
            ],
        )
        .map_err(|e| GatewayError::Message(e.to_string()))?;

        let cookie = if sid.is_empty() {
            format!("arl={}", self.arl)
        } else {
            format!("arl={}; sid={sid}", self.arl)
        };

        let resp = self
            .http
            .post(url)
            .header("Cookie", cookie)
            .header("Accept", "application/json")
            .json(&body)
            .send()
            .await
            .map_err(|e| GatewayError::Message(e.to_string()))?;

        let status = resp.status().as_u16();
        if status == 403 || status == 429 || status >= 500 {
            return Err(GatewayError::Message(format!(
                "Deezer bağlantı hatası (HTTP {status})"
            )));
        }

        let mut new_sid: Option<String> = None;
        for val in resp.headers().get_all("set-cookie") {
            if let Ok(s) = val.to_str() {
                let pair = s.split(';').next().unwrap_or("").trim();
                if let Some(v) = pair.strip_prefix("sid=") {
                    if !v.is_empty() {
                        new_sid = Some(v.to_string());
                    }
                }
            }
        }

        let text = resp
            .text()
            .await
            .map_err(|e| GatewayError::Message(e.to_string()))?;
        let envelope: Value = serde_json::from_str(&text)
            .map_err(|_| GatewayError::Message("Deezer yanıtı okunamadı".into()))?;

        if let Some(err) = envelope.get("error") {
            let is_empty = err.is_null()
                || err.as_object().is_some_and(|m| m.is_empty())
                || err.as_array().is_some_and(|a| a.is_empty());
            if !is_empty {
                let err_text = err.to_string().to_ascii_uppercase();
                let is_auth = authenticated
                    && (err_text.contains("TOKEN")
                        || err_text.contains("CSRF")
                        || err_text.contains("SESSION")
                        || err_text.contains("EXPIRED")
                        || err_text.contains("NEED_USER_AUTH")
                        || err_text.contains("VALID_TOKEN"));
                if is_auth {
                    return Err(GatewayError::Message("__TOKEN_EXPIRED__".into()));
                }
                return Err(GatewayError::Message("Deezer isteği başarısız oldu".into()));
            }
        }

        let result = match envelope.get("results") {
            Some(Value::Object(o)) => Value::Object(o.clone()),
            Some(Value::Bool(true)) => json!({ "success": true }),
            Some(Value::Number(n)) if n.as_i64() != Some(0) => json!({ "value": n }),
            Some(Value::String(s))
                if !s.trim().is_empty() && s != "0" && !s.eq_ignore_ascii_case("false") =>
            {
                json!({ "value": s })
            }
            _ => return Err(GatewayError::Message("Deezer işlemi reddetti".into())),
        };

        Ok((result, new_sid))
    }
}

pub fn is_trusted_media_url(raw: &str) -> bool {
    let Ok(parsed) = Url::parse(raw.trim()) else {
        return false;
    };
    if !parsed.scheme().eq_ignore_ascii_case("https") {
        return false;
    }
    let Some(host) = parsed.host_str() else {
        return false;
    };
    let h = host.trim_end_matches('.').to_ascii_lowercase();
    h == "dzcdn.net"
        || h.ends_with(".dzcdn.net")
        || h == "deezer.com"
        || h.ends_with(".deezer.com")
}

pub fn parse_media_from_get_url(
    parsed: &Value,
    track_id: &str,
    exact_format: Option<&str>,
) -> Result<Option<GatewayMedia>, GatewayError> {
    let Some(media) = parsed
        .get("data")
        .and_then(|v| v.as_array())
        .and_then(|a| a.first())
        .and_then(|i| i.get("media"))
        .and_then(|v| v.as_array())
        .and_then(|a| a.first())
    else {
        return Ok(None);
    };

    let format = media
        .get("format")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    if let Some(expected) = exact_format {
        if format != expected {
            return Ok(None);
        }
    }

    let source = media
        .get("sources")
        .and_then(|v| v.as_array())
        .and_then(|a| a.first())
        .and_then(|s| s.get("url"))
        .and_then(|v| v.as_str())
        .unwrap_or("");

    if source.is_empty() {
        return Ok(None);
    }
    if !is_trusted_media_url(source) {
        return Err(GatewayError::Message("Geçersiz Deezer ses kaynağı".into()));
    }

    Ok(Some(GatewayMedia {
        url: source.to_string(),
        track_id: track_id.to_string(),
        format,
    }))
}

fn require_numeric_id(id: &str) -> Result<(), GatewayError> {
    if id.is_empty() || !id.bytes().all(|b| b.is_ascii_digit()) {
        return Err(GatewayError::Message("Geçersiz kimlik".into()));
    }
    Ok(())
}

fn json_id_str(val: Option<&Value>) -> String {
    match val {
        Some(Value::String(s)) => s.trim().to_string(),
        Some(Value::Number(n)) => n.to_string(),
        _ => String::new(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn trusted_media_url_checks() {
        assert!(is_trusted_media_url("https://e-cdns-proxy-a.dzcdn.net/mobile/1/abc"));
        assert!(is_trusted_media_url("https://cdns-preview-1.dzcdn.net/stream/c.mp3"));
        assert!(!is_trusted_media_url("http://e-cdns-proxy-a.dzcdn.net/mobile/1/abc"));
        assert!(!is_trusted_media_url("https://evil.example.com/audio.mp3"));
    }

    #[test]
    fn parses_get_url_media_response() {
        let json: Value = serde_json::from_str(
            r#"{
                "data": [{
                    "media": [{
                        "format": "FLAC",
                        "sources": [{ "url": "https://e-cdns-proxy-1.dzcdn.net/mobile/1/xyz" }]
                    }]
                }]
            }"#,
        )
        .unwrap();
        let m = parse_media_from_get_url(&json, "123", Some("FLAC"))
            .unwrap()
            .unwrap();
        assert_eq!(m.format, "FLAC");
        assert_eq!(m.track_id, "123");
        assert!(parse_media_from_get_url(&json, "123", Some("MP3_320"))
            .unwrap()
            .is_none());
    }
}
