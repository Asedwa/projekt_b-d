use std::collections::HashMap;

use core_domain::{DomainError, DomainResult};
use rusqlite::params;
use serde::{Deserialize, Serialize};

use crate::user_data_repo::{StoredHistoryEntry, StoredTrackListeningStat, StoredTrackRecord};
use crate::Database;

const MS_PER_DAY: i64 = 86_400_000;
const MS_PER_HOUR: i64 = 3_600_000;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DailyListeningPoint {
    pub day_key: String,
    pub day_epoch: i64,
    pub play_count: u32,
    pub completed_count: u32,
    pub skip_count: u32,
    pub listened_ms: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct HourlyListeningBucket {
    pub hour: u32,
    pub play_count: u32,
    pub listened_ms: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ArtistStatSummary {
    pub artist: String,
    pub play_count: u32,
    pub total_listened_ms: u64,
    pub unique_tracks: u32,
    pub artwork_url: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AlbumStatSummary {
    pub album: String,
    pub artist: String,
    pub play_count: u32,
    pub total_listened_ms: u64,
    pub unique_tracks: u32,
    pub artwork_url: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ListeningAnalyticsSummary {
    pub period_id: String,
    pub total_listened_ms: u64,
    pub total_plays: u32,
    pub unique_tracks: u32,
    pub unique_artists: u32,
    pub unique_albums: u32,
    pub current_streak_days: u32,
    pub longest_streak_days: u32,
    pub daily_average_ms: u64,
    pub peak_day_key: Option<String>,
    pub peak_day_ms: u64,
    pub completed_plays: u32,
    pub skipped_plays: u32,
    pub completion_rate_pct: u32,
    pub listening_persona: String,
    pub peak_hour: u32,
    pub top_track_title: Option<String>,
    pub top_track_artist: Option<String>,
    pub top_track_plays: u32,
    pub top_artist_name: Option<String>,
    pub top_artist_plays: u32,
    pub top_album_name: Option<String>,
    pub top_album_plays: u32,
    pub daily_points: Vec<DailyListeningPoint>,
    pub hourly_buckets: Vec<HourlyListeningBucket>,
    pub top_tracks: Vec<StoredTrackListeningStat>,
    pub top_artists: Vec<ArtistStatSummary>,
    pub top_albums: Vec<AlbumStatSummary>,
    pub forgotten_favorites: Vec<StoredTrackListeningStat>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ImportBackupReport {
    pub imported_tracks: u32,
    pub imported_history: u32,
    pub imported_days: u32,
    pub imported_themes: u32,
}

fn map_err(op: &str, e: rusqlite::Error) -> DomainError {
    DomainError::Storage(format!("{op}: {e}"))
}

/// Converts `(timestamp_ms, tz_offset_ms)` into `(day_epoch, hour_of_day, YYYY-MM-DD)`.
pub fn local_calendar_parts(timestamp_ms: u64, tz_offset_ms: i64) -> (i64, u32, String) {
    let local_ms = (timestamp_ms as i64).saturating_add(tz_offset_ms);
    let day_epoch = local_ms.div_euclid(MS_PER_DAY);
    let ms_in_day = local_ms.rem_euclid(MS_PER_DAY);
    let hour = (ms_in_day / MS_PER_HOUR).clamp(0, 23) as u32;
    let day_key = format_day_epoch(day_epoch);
    (day_epoch, hour, day_key)
}

/// Howard Hinnant's civil-from-days algorithm (zero external chrono dependency).
pub fn format_day_epoch(days_since_unix: i64) -> String {
    let z = days_since_unix + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = (z - era * 146_097) as u64;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365;
    let y = (yoe as i64) + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = if m <= 2 { y + 1 } else { y };
    format!("{year:04}-{m:02}-{d:02}")
}

fn period_start_day(period_id: &str, today_epoch: i64) -> Option<i64> {
    match period_id {
        "today" => Some(today_epoch),
        "7d" => Some(today_epoch - 6),
        "30d" => Some(today_epoch - 29),
        "year" => Some(today_epoch - 364),
        _ => None, // "all"
    }
}

fn compute_streaks(day_epochs_desc: &[i64], today_epoch: i64) -> (u32, u32) {
    if day_epochs_desc.is_empty() {
        return (0, 0);
    }
    let mut sorted = day_epochs_desc.to_vec();
    sorted.sort_unstable();
    sorted.dedup();

    // Longest streak
    let mut longest = 1u32;
    let mut run = 1u32;
    for w in sorted.windows(2) {
        if w[1] == w[0] + 1 {
            run += 1;
            if run > longest {
                longest = run;
            }
        } else {
            run = 1;
        }
    }

    // Current streak (must end on today or yesterday)
    let last = *sorted.last().unwrap_or(&0);
    let current = if last == today_epoch || last == today_epoch - 1 {
        let mut cur = 1u32;
        for i in (1..sorted.len()).rev() {
            if sorted[i] == sorted[i - 1] + 1 {
                cur += 1;
            } else {
                break;
            }
        }
        cur
    } else {
        0
    };

    (current, longest)
}

fn compute_persona(hourly: &[HourlyListeningBucket]) -> (String, u32) {
    let mut morning = 0u64; // 06..11
    let mut afternoon = 0u64; // 12..17
    let mut evening = 0u64; // 18..22
    let mut night = 0u64; // 23..05
    let mut peak_hour = 0u32;
    let mut peak_val = 0u64;

    for b in hourly {
        let weight = b.listened_ms.max((b.play_count as u64) * 60_000);
        if weight > peak_val {
            peak_val = weight;
            peak_hour = b.hour;
        }
        match b.hour {
            6..=11 => morning += weight,
            12..=17 => afternoon += weight,
            18..=22 => evening += weight,
            _ => night += weight,
        }
    }

    if peak_val == 0 {
        return ("Yeni Keşifçi".into(), 0);
    }

    let max_bucket = morning.max(afternoon).max(evening).max(night);
    let label = if max_bucket == night {
        "Gece Kuşu"
    } else if max_bucket == morning {
        "Sabah Dinleyicisi"
    } else if max_bucket == afternoon {
        "Gündüz Enerjisi"
    } else {
        "Akşam Keyfi"
    };
    (label.into(), peak_hour)
}

impl Database {
    pub fn record_analytics_play(
        &self,
        track: &StoredTrackRecord,
        timestamp_ms: u64,
        tz_offset_ms: i64,
    ) -> DomainResult<()> {
        let (day_epoch, hour, day_key) = local_calendar_parts(timestamp_ms, tz_offset_ms);
        let track_json = serde_json::to_string(track)
            .map_err(|e| DomainError::Storage(format!("serialize track: {e}")))?;
        let mut conn = self.lock()?;
        let tx = conn.transaction().map_err(|e| map_err("analytics play tx", e))?;

        tx.execute(
            "INSERT INTO daily_listening_stats (day_key, day_epoch, play_count, completed_count, skip_count, listened_ms)
             VALUES (?1, ?2, 1, 0, 0, 0)
             ON CONFLICT(day_key) DO UPDATE SET play_count = play_count + 1",
            params![day_key, day_epoch],
        )
        .map_err(|e| map_err("daily play", e))?;

        tx.execute(
            "INSERT INTO hourly_listening_stats (hour_of_day, play_count, listened_ms)
             VALUES (?1, 1, 0)
             ON CONFLICT(hour_of_day) DO UPDATE SET play_count = play_count + 1",
            params![hour as i64],
        )
        .map_err(|e| map_err("hourly play", e))?;

        tx.execute(
            "INSERT INTO track_daily_plays (day_epoch, track_id, track_json, play_count, listened_ms, last_played_at_ms)
             VALUES (?1, ?2, ?3, 1, 0, ?4)
             ON CONFLICT(day_epoch, track_id) DO UPDATE SET
               track_json = excluded.track_json,
               play_count = play_count + 1,
               last_played_at_ms = excluded.last_played_at_ms",
            params![day_epoch, track.id, track_json, timestamp_ms as i64],
        )
        .map_err(|e| map_err("track daily play", e))?;

        tx.commit().map_err(|e| map_err("analytics play commit", e))?;
        Ok(())
    }

    pub fn record_analytics_tick(
        &self,
        track: &StoredTrackRecord,
        delta_ms: u64,
        timestamp_ms: u64,
        tz_offset_ms: i64,
    ) -> DomainResult<()> {
        if delta_ms == 0 {
            return Ok(());
        }
        let (day_epoch, hour, day_key) = local_calendar_parts(timestamp_ms, tz_offset_ms);
        let track_json = serde_json::to_string(track)
            .map_err(|e| DomainError::Storage(format!("serialize track: {e}")))?;
        let mut conn = self.lock()?;
        let tx = conn.transaction().map_err(|e| map_err("analytics tick tx", e))?;

        tx.execute(
            "INSERT INTO daily_listening_stats (day_key, day_epoch, play_count, completed_count, skip_count, listened_ms)
             VALUES (?1, ?2, 0, 0, 0, ?3)
             ON CONFLICT(day_key) DO UPDATE SET listened_ms = listened_ms + ?3",
            params![day_key, day_epoch, delta_ms as i64],
        )
        .map_err(|e| map_err("daily tick", e))?;

        tx.execute(
            "INSERT INTO hourly_listening_stats (hour_of_day, play_count, listened_ms)
             VALUES (?1, 0, ?2)
             ON CONFLICT(hour_of_day) DO UPDATE SET listened_ms = listened_ms + ?2",
            params![hour as i64, delta_ms as i64],
        )
        .map_err(|e| map_err("hourly tick", e))?;

        tx.execute(
            "INSERT INTO track_daily_plays (day_epoch, track_id, track_json, play_count, listened_ms, last_played_at_ms)
             VALUES (?1, ?2, ?3, 1, ?4, ?5)
             ON CONFLICT(day_epoch, track_id) DO UPDATE SET
               track_json = excluded.track_json,
               listened_ms = listened_ms + ?4,
               last_played_at_ms = excluded.last_played_at_ms",
            params![day_epoch, track.id, track_json, delta_ms as i64, timestamp_ms as i64],
        )
        .map_err(|e| map_err("track daily tick", e))?;

        tx.commit().map_err(|e| map_err("analytics tick commit", e))?;
        Ok(())
    }

    pub fn record_track_outcome(
        &self,
        completed: bool,
        timestamp_ms: u64,
        tz_offset_ms: i64,
    ) -> DomainResult<()> {
        let (day_epoch, _hour, day_key) = local_calendar_parts(timestamp_ms, tz_offset_ms);
        let conn = self.lock()?;
        let (c_inc, s_inc) = if completed { (1i64, 0i64) } else { (0i64, 1i64) };
        conn.execute(
            "INSERT INTO daily_listening_stats (day_key, day_epoch, play_count, completed_count, skip_count, listened_ms)
             VALUES (?1, ?2, 0, ?3, ?4, 0)
             ON CONFLICT(day_key) DO UPDATE SET
               completed_count = completed_count + ?3,
               skip_count = skip_count + ?4",
            params![day_key, day_epoch, c_inc, s_inc],
        )
        .map_err(|e| map_err("record_track_outcome", e))?;
        Ok(())
    }

    pub fn get_listening_analytics(
        &self,
        period_id: &str,
        sort_by: &str,
        now_ms: u64,
        tz_offset_ms: i64,
    ) -> DomainResult<ListeningAnalyticsSummary> {
        let (today_epoch, _, _) = local_calendar_parts(now_ms, tz_offset_ms);
        let min_day = period_start_day(period_id, today_epoch);

        // 1. Load daily points (all for streaks + chart window)
        let all_daily = self.load_daily_points(None)?;
        let active_epochs: Vec<i64> = all_daily
            .iter()
            .filter(|d| d.play_count > 0 || d.listened_ms > 0)
            .map(|d| d.day_epoch)
            .collect();
        let (current_streak_days, longest_streak_days) = compute_streaks(&active_epochs, today_epoch);

        let chart_days = match period_id {
            "today" => 7,
            "7d" => 7,
            "30d" => 30,
            _ => 14,
        };
        let chart_start = today_epoch - (chart_days - 1);
        let daily_map: HashMap<i64, DailyListeningPoint> = all_daily
            .iter()
            .cloned()
            .map(|d| (d.day_epoch, d))
            .collect();
        let mut daily_points = Vec::with_capacity(chart_days as usize);
        for d in chart_start..=today_epoch {
            if let Some(pt) = daily_map.get(&d) {
                daily_points.push(pt.clone());
            } else {
                daily_points.push(DailyListeningPoint {
                    day_key: format_day_epoch(d),
                    day_epoch: d,
                    play_count: 0,
                    completed_count: 0,
                    skip_count: 0,
                    listened_ms: 0,
                });
            }
        }

        let period_daily: Vec<&DailyListeningPoint> = all_daily
            .iter()
            .filter(|d| min_day.map(|m| d.day_epoch >= m).unwrap_or(true))
            .collect();

        let completed_plays: u32 = period_daily.iter().map(|d| d.completed_count).sum();
        let skipped_plays: u32 = period_daily.iter().map(|d| d.skip_count).sum();
        let outcome_total = completed_plays.saturating_add(skipped_plays);
        let completion_rate_pct = if outcome_total > 0 {
            ((completed_plays as u64 * 100) / outcome_total as u64) as u32
        } else {
            100
        };

        let peak_day = period_daily.iter().max_by_key(|d| d.listened_ms);
        let peak_day_key = peak_day.filter(|d| d.listened_ms > 0).map(|d| d.day_key.clone());
        let peak_day_ms = peak_day.map(|d| d.listened_ms).unwrap_or(0);

        // 2. Hourly buckets
        let hourly_buckets = self.load_hourly_buckets()?;
        let (listening_persona, peak_hour) = compute_persona(&hourly_buckets);

        // 3. Tracks for selected period
        let mut top_tracks = if let Some(min_d) = min_day {
            self.load_period_track_stats(min_d)?
        } else {
            self.get_listening_stats(500)?
        };

        // Sort tracks according to sort_by ("plays", "duration", "recent")
        match sort_by {
            "duration" => top_tracks.sort_by(|a, b| {
                b.total_listened_ms
                    .cmp(&a.total_listened_ms)
                    .then_with(|| b.play_count.cmp(&a.play_count))
            }),
            "recent" => top_tracks.sort_by(|a, b| b.last_played_at_ms.cmp(&a.last_played_at_ms)),
            _ => top_tracks.sort_by(|a, b| {
                b.play_count
                    .cmp(&a.play_count)
                    .then_with(|| b.total_listened_ms.cmp(&a.total_listened_ms))
            }),
        }

        let total_plays: u32 = top_tracks.iter().map(|t| t.play_count).sum();
        let total_listened_ms: u64 = if min_day.is_some() {
            let daily_sum: u64 = period_daily.iter().map(|d| d.listened_ms).sum();
            daily_sum.max(top_tracks.iter().map(|t| t.total_listened_ms).sum())
        } else {
            self.get_total_listening_time_ms()
                .unwrap_or(0)
                .max(top_tracks.iter().map(|t| t.total_listened_ms).sum())
        };

        let active_days_in_period = period_daily
            .iter()
            .filter(|d| d.listened_ms > 0 || d.play_count > 0)
            .count() as u64;
        let daily_average_ms = if active_days_in_period > 0 {
            total_listened_ms / active_days_in_period
        } else {
            0
        };

        // 4. Aggregate artists & albums in Rust
        let mut artist_map: HashMap<String, ArtistStatSummary> = HashMap::new();
        let mut album_map: HashMap<String, AlbumStatSummary> = HashMap::new();

        for st in &top_tracks {
            let artist_name = if st.track.artist.trim().is_empty() {
                "Bilinmeyen Sanatçı".to_string()
            } else {
                st.track.artist.trim().to_string()
            };
            let a_key = artist_name.to_lowercase();
            let a_entry = artist_map.entry(a_key).or_insert_with(|| ArtistStatSummary {
                artist: artist_name.clone(),
                play_count: 0,
                total_listened_ms: 0,
                unique_tracks: 0,
                artwork_url: None,
            });
            a_entry.play_count = a_entry.play_count.saturating_add(st.play_count);
            a_entry.total_listened_ms = a_entry.total_listened_ms.saturating_add(st.total_listened_ms);
            a_entry.unique_tracks += 1;
            if a_entry.artwork_url.is_none() {
                a_entry.artwork_url = st.track.artwork_url.clone();
            }

            let album_name = if st.track.album.trim().is_empty() {
                "Bilinmeyen Albüm".to_string()
            } else {
                st.track.album.trim().to_string()
            };
            let alb_key = format!("{}::{}", album_name.to_lowercase(), artist_name.to_lowercase());
            let alb_entry = album_map.entry(alb_key).or_insert_with(|| AlbumStatSummary {
                album: album_name,
                artist: artist_name,
                play_count: 0,
                total_listened_ms: 0,
                unique_tracks: 0,
                artwork_url: None,
            });
            alb_entry.play_count = alb_entry.play_count.saturating_add(st.play_count);
            alb_entry.total_listened_ms = alb_entry.total_listened_ms.saturating_add(st.total_listened_ms);
            alb_entry.unique_tracks += 1;
            if alb_entry.artwork_url.is_none() {
                alb_entry.artwork_url = st.track.artwork_url.clone();
            }
        }

        let mut top_artists: Vec<ArtistStatSummary> = artist_map.into_values().collect();
        let mut top_albums: Vec<AlbumStatSummary> = album_map.into_values().collect();
        match sort_by {
            "duration" => {
                top_artists.sort_by(|a, b| b.total_listened_ms.cmp(&a.total_listened_ms).then_with(|| b.play_count.cmp(&a.play_count)));
                top_albums.sort_by(|a, b| b.total_listened_ms.cmp(&a.total_listened_ms).then_with(|| b.play_count.cmp(&a.play_count)));
            }
            _ => {
                top_artists.sort_by(|a, b| b.play_count.cmp(&a.play_count).then_with(|| b.total_listened_ms.cmp(&a.total_listened_ms)));
                top_albums.sort_by(|a, b| b.play_count.cmp(&a.play_count).then_with(|| b.total_listened_ms.cmp(&a.total_listened_ms)));
            }
        }

        let unique_tracks = top_tracks.len() as u32;
        let unique_artists = top_artists.len() as u32;
        let unique_albums = top_albums.len() as u32;

        // 5. Forgotten favorites (all-time plays >= 3, not played in last 21 days)
        let cutoff_ms = now_ms.saturating_sub(21 * MS_PER_DAY as u64);
        let all_time = if min_day.is_some() {
            self.get_listening_stats(500)?
        } else {
            top_tracks.clone()
        };
        let mut forgotten_favorites: Vec<StoredTrackListeningStat> = all_time
            .into_iter()
            .filter(|t| t.play_count >= 3 && t.last_played_at_ms > 0 && t.last_played_at_ms < cutoff_ms)
            .collect();
        forgotten_favorites.sort_by(|a, b| b.play_count.cmp(&a.play_count));
        forgotten_favorites.truncate(30);

        let top_track = top_tracks.first();
        let top_artist = top_artists.first();
        let top_album = top_albums.first();

        Ok(ListeningAnalyticsSummary {
            period_id: period_id.to_string(),
            total_listened_ms,
            total_plays,
            unique_tracks,
            unique_artists,
            unique_albums,
            current_streak_days,
            longest_streak_days,
            daily_average_ms,
            peak_day_key,
            peak_day_ms,
            completed_plays,
            skipped_plays,
            completion_rate_pct,
            listening_persona,
            peak_hour,
            top_track_title: top_track.map(|t| t.track.title.clone()),
            top_track_artist: top_track.map(|t| t.track.artist.clone()),
            top_track_plays: top_track.map(|t| t.play_count).unwrap_or(0),
            top_artist_name: top_artist.map(|a| a.artist.clone()),
            top_artist_plays: top_artist.map(|a| a.play_count).unwrap_or(0),
            top_album_name: top_album.map(|a| a.album.clone()),
            top_album_plays: top_album.map(|a| a.play_count).unwrap_or(0),
            daily_points,
            hourly_buckets,
            top_tracks: top_tracks.into_iter().take(200).collect(),
            top_artists: top_artists.into_iter().take(100).collect(),
            top_albums: top_albums.into_iter().take(100).collect(),
            forgotten_favorites,
        })
    }

    pub fn load_daily_points(&self, min_epoch: Option<i64>) -> DomainResult<Vec<DailyListeningPoint>> {
        let conn = self.lock()?;
        let sql = if min_epoch.is_some() {
            "SELECT day_key, day_epoch, play_count, completed_count, skip_count, listened_ms
             FROM daily_listening_stats WHERE day_epoch >= ?1 ORDER BY day_epoch ASC"
        } else {
            "SELECT day_key, day_epoch, play_count, completed_count, skip_count, listened_ms
             FROM daily_listening_stats ORDER BY day_epoch ASC"
        };
        let mut stmt = conn.prepare(sql).map_err(|e| map_err("load_daily_points prepare", e))?;
        let mut out = Vec::new();
        let mapper = |r: &rusqlite::Row<'_>| -> rusqlite::Result<DailyListeningPoint> {
            Ok(DailyListeningPoint {
                day_key: r.get(0)?,
                day_epoch: r.get(1)?,
                play_count: r.get::<_, i64>(2)?.max(0) as u32,
                completed_count: r.get::<_, i64>(3)?.max(0) as u32,
                skip_count: r.get::<_, i64>(4)?.max(0) as u32,
                listened_ms: r.get::<_, i64>(5)?.max(0) as u64,
            })
        };
        if let Some(m) = min_epoch {
            for r in stmt.query_map(params![m], mapper).map_err(|e| map_err("load_daily_points query", e))? {
                out.push(r.map_err(|e| map_err("load_daily_points row", e))?);
            }
        } else {
            for r in stmt.query_map([], mapper).map_err(|e| map_err("load_daily_points query", e))? {
                out.push(r.map_err(|e| map_err("load_daily_points row", e))?);
            }
        }
        Ok(out)
    }

    pub fn load_hourly_buckets(&self) -> DomainResult<Vec<HourlyListeningBucket>> {
        let conn = self.lock()?;
        let mut map: HashMap<u32, HourlyListeningBucket> = HashMap::new();
        let mut stmt = conn
            .prepare("SELECT hour_of_day, play_count, listened_ms FROM hourly_listening_stats")
            .map_err(|e| map_err("load_hourly_buckets prepare", e))?;
        let rows = stmt
            .query_map([], |r| {
                Ok(HourlyListeningBucket {
                    hour: r.get::<_, i64>(0)?.clamp(0, 23) as u32,
                    play_count: r.get::<_, i64>(1)?.max(0) as u32,
                    listened_ms: r.get::<_, i64>(2)?.max(0) as u64,
                })
            })
            .map_err(|e| map_err("load_hourly_buckets query", e))?;
        for r in rows {
            let b = r.map_err(|e| map_err("load_hourly_buckets row", e))?;
            map.insert(b.hour, b);
        }
        let mut out = Vec::with_capacity(24);
        for h in 0..24u32 {
            out.push(map.remove(&h).unwrap_or(HourlyListeningBucket {
                hour: h,
                play_count: 0,
                listened_ms: 0,
            }));
        }
        Ok(out)
    }

    fn load_period_track_stats(&self, min_day_epoch: i64) -> DomainResult<Vec<StoredTrackListeningStat>> {
        let conn = self.lock()?;
        let mut stmt = conn
            .prepare(
                "SELECT track_id, MAX(track_json), SUM(play_count), SUM(listened_ms), MAX(last_played_at_ms)
                 FROM track_daily_plays
                 WHERE day_epoch >= ?1
                 GROUP BY track_id",
            )
            .map_err(|e| map_err("load_period_track_stats prepare", e))?;
        let rows = stmt
            .query_map(params![min_day_epoch], |r| {
                Ok((
                    r.get::<_, String>(1)?,
                    r.get::<_, i64>(2)?.max(0) as u32,
                    r.get::<_, i64>(3)?.max(0) as u64,
                    r.get::<_, i64>(4)?.max(0) as u64,
                ))
            })
            .map_err(|e| map_err("load_period_track_stats query", e))?;
        let mut out = Vec::new();
        for r in rows {
            let (raw, play_count, total_listened_ms, last_played_at_ms) =
                r.map_err(|e| map_err("load_period_track_stats row", e))?;
            if let Ok(track) = serde_json::from_str::<StoredTrackRecord>(&raw) {
                out.push(StoredTrackListeningStat {
                    track,
                    play_count,
                    total_listened_ms,
                    last_played_at_ms,
                });
            }
        }
        Ok(out)
    }

    // -------------------------------------------------------------------------
    // Granular Deletion & CSV Export & JSON Backup Restore
    // -------------------------------------------------------------------------

    pub fn clear_listening_history_only(&self) -> DomainResult<()> {
        let conn = self.lock()?;
        conn.execute("DELETE FROM listening_history", [])
            .map_err(|e| map_err("clear_listening_history_only", e))?;
        Ok(())
    }

    pub fn clear_listening_stats_only(&self) -> DomainResult<()> {
        let mut conn = self.lock()?;
        let tx = conn.transaction().map_err(|e| map_err("clear_stats tx", e))?;
        tx.execute("DELETE FROM listening_stats", []).map_err(|e| map_err("del stats", e))?;
        tx.execute("DELETE FROM daily_listening_stats", []).map_err(|e| map_err("del daily", e))?;
        tx.execute("DELETE FROM hourly_listening_stats", []).map_err(|e| map_err("del hourly", e))?;
        tx.execute("DELETE FROM track_daily_plays", []).map_err(|e| map_err("del track_daily", e))?;
        tx.execute("DELETE FROM settings WHERE key = 'stats.total_listening_time_ms'", [])
            .map_err(|e| map_err("del total", e))?;
        tx.commit().map_err(|e| map_err("clear_stats commit", e))?;
        Ok(())
    }

    pub fn clear_last_24h_data(&self, now_ms: u64, tz_offset_ms: i64) -> DomainResult<()> {
        let cutoff_ms = now_ms.saturating_sub(MS_PER_DAY as u64) as i64;
        let (today_epoch, _, _) = local_calendar_parts(now_ms, tz_offset_ms);
        let mut conn = self.lock()?;
        let tx = conn.transaction().map_err(|e| map_err("clear_24h tx", e))?;
        tx.execute("DELETE FROM listening_history WHERE played_at_ms >= ?1", params![cutoff_ms])
            .map_err(|e| map_err("del 24h history", e))?;
        tx.execute("DELETE FROM track_daily_plays WHERE last_played_at_ms >= ?1", params![cutoff_ms])
            .map_err(|e| map_err("del 24h track_daily", e))?;
        tx.execute("DELETE FROM daily_listening_stats WHERE day_epoch >= ?1", params![today_epoch])
            .map_err(|e| map_err("del today daily", e))?;
        tx.commit().map_err(|e| map_err("clear_24h commit", e))?;
        Ok(())
    }

    pub fn delete_single_track_stat(&self, track_id: &str) -> DomainResult<()> {
        let mut conn = self.lock()?;
        let tx = conn.transaction().map_err(|e| map_err("del_single tx", e))?;
        tx.execute("DELETE FROM listening_stats WHERE track_id = ?1", params![track_id])
            .map_err(|e| map_err("del single stat", e))?;
        tx.execute("DELETE FROM track_daily_plays WHERE track_id = ?1", params![track_id])
            .map_err(|e| map_err("del single daily", e))?;
        tx.execute("DELETE FROM listening_history WHERE track_id = ?1", params![track_id])
            .map_err(|e| map_err("del single history", e))?;
        tx.commit().map_err(|e| map_err("del_single commit", e))?;
        Ok(())
    }

    pub fn build_listening_stats_csv(&self) -> DomainResult<String> {
        let stats = self.get_listening_stats(5000)?;
        let mut out = String::from("Sira,Sarki,Sanatci,Album,DinlenmeSayisi,ToplamSureSaniye,SonDinlenmeMs\n");
        for (i, st) in stats.iter().enumerate() {
            let secs = st.total_listened_ms / 1000;
            out.push_str(&format!(
                "{},{},{},{},{},{},{}\n",
                i + 1,
                csv_escape(&st.track.title),
                csv_escape(&st.track.artist),
                csv_escape(&st.track.album),
                st.play_count,
                secs,
                st.last_played_at_ms
            ));
        }
        Ok(out)
    }

    pub fn import_user_data_json(&self, raw_json: &str, merge: bool) -> DomainResult<ImportBackupReport> {
        let root: serde_json::Value = serde_json::from_str(raw_json)
            .map_err(|e| DomainError::InvalidInput(format!("Geçersiz JSON yedek dosyası: {e}")))?;

        if !merge {
            self.clear_all_collected_user_data()?;
        }

        let mut imported_tracks = 0u32;
        let mut imported_history = 0u32;
        let mut imported_days = 0u32;
        let mut imported_themes = 0u32;

        let stats_arr = root
            .get("listeningStats")
            .and_then(|v| v.as_array())
            .or_else(|| root.get("tracks").and_then(|v| v.as_array()));

        if let Some(arr) = stats_arr {
            let mut batch = Vec::new();
            for item in arr {
                let id = item
                    .get("trackId")
                    .and_then(|v| v.as_str())
                    .or_else(|| item.get("id").and_then(|v| v.as_str()))
                    .unwrap_or("");
                let title = item.get("title").and_then(|v| v.as_str()).unwrap_or("");
                if id.is_empty() || title.is_empty() {
                    continue;
                }
                let inferred_provider = id.split_once(':').map(|(p, _)| p).unwrap_or("deezer");
                let track = StoredTrackRecord {
                    id: id.to_string(),
                    provider: item
                        .get("provider")
                        .and_then(|v| v.as_str())
                        .unwrap_or(inferred_provider)
                        .to_string(),
                    title: title.to_string(),
                    artist: item.get("artist").and_then(|v| v.as_str()).unwrap_or("").to_string(),
                    album: item.get("album").and_then(|v| v.as_str()).unwrap_or("").to_string(),
                    duration_ms: item.get("durationMs").and_then(|v| v.as_u64()).unwrap_or(0),
                    artwork_url: item.get("artworkUrl").and_then(|v| v.as_str()).map(ToOwned::to_owned),
                    playback_uri: None,
                    is_preview: false,
                    origin: "real".to_string(),
                };
                batch.push(StoredTrackListeningStat {
                    track,
                    play_count: item.get("playCount").and_then(|v| v.as_u64()).unwrap_or(1).max(1) as u32,
                    total_listened_ms: item.get("totalListenedMs").and_then(|v| v.as_u64()).unwrap_or(0),
                    last_played_at_ms: item.get("lastPlayedAtMs").and_then(|v| v.as_u64()).unwrap_or(0),
                });
            }
            imported_tracks = batch.len() as u32;
            self.upsert_listening_stats_batch(&batch)?;
        }

        let total_ms_opt = root
            .get("totalListeningTimeMs")
            .and_then(|v| v.as_u64())
            .or_else(|| {
                root.get("summary")
                    .and_then(|s| s.get("totalListeningTimeMs"))
                    .and_then(|v| v.as_u64())
            });
        if let Some(total_ms) = total_ms_opt {
            let existing = self.get_total_listening_time_ms().unwrap_or(0);
            let next = if merge { existing.max(total_ms) } else { total_ms };
            self.set_total_listening_time_ms(next)?;
        }

        if let Some(arr) = root.get("dailyStats").and_then(|v| v.as_array()) {
            let mut conn = self.lock()?;
            let tx = conn.transaction().map_err(|e| map_err("import daily tx", e))?;
            for item in arr {
                if let Ok(pt) = serde_json::from_value::<DailyListeningPoint>(item.clone()) {
                    tx.execute(
                        "INSERT INTO daily_listening_stats (day_key, day_epoch, play_count, completed_count, skip_count, listened_ms)
                         VALUES (?1, ?2, ?3, ?4, ?5, ?6)
                         ON CONFLICT(day_key) DO UPDATE SET
                           play_count = MAX(play_count, excluded.play_count),
                           completed_count = MAX(completed_count, excluded.completed_count),
                           skip_count = MAX(skip_count, excluded.skip_count),
                           listened_ms = MAX(listened_ms, excluded.listened_ms)",
                        params![
                            pt.day_key,
                            pt.day_epoch,
                            pt.play_count as i64,
                            pt.completed_count as i64,
                            pt.skip_count as i64,
                            pt.listened_ms as i64,
                        ],
                    )
                    .map_err(|e| map_err("import daily row", e))?;
                    imported_days += 1;
                }
            }
            tx.commit().map_err(|e| map_err("import daily commit", e))?;
        }

        if let Some(arr) = root.get("hourlyStats").and_then(|v| v.as_array()) {
            let mut conn = self.lock()?;
            let tx = conn.transaction().map_err(|e| map_err("import hourly tx", e))?;
            for item in arr {
                if let Ok(hb) = serde_json::from_value::<HourlyListeningBucket>(item.clone()) {
                    tx.execute(
                        "INSERT INTO hourly_listening_stats (hour_of_day, play_count, listened_ms)
                         VALUES (?1, ?2, ?3)
                         ON CONFLICT(hour_of_day) DO UPDATE SET
                           play_count = MAX(play_count, excluded.play_count),
                           listened_ms = MAX(listened_ms, excluded.listened_ms)",
                        params![hb.hour as i64, hb.play_count as i64, hb.listened_ms as i64],
                    )
                    .map_err(|e| map_err("import hourly row", e))?;
                }
            }
            tx.commit().map_err(|e| map_err("import hourly commit", e))?;
        }

        if let Some(arr) = root.get("recentHistory").and_then(|v| v.as_array()) {
            let mut hist = Vec::new();
            let base = crate::user_data_repo::now_epoch_ms();
            for (idx, item) in arr.iter().enumerate() {
                let id = item.get("id").and_then(|v| v.as_str()).unwrap_or("");
                let title = item.get("title").and_then(|v| v.as_str()).unwrap_or("");
                if id.is_empty() || title.is_empty() {
                    continue;
                }
                let inferred_provider = id.split_once(':').map(|(p, _)| p).unwrap_or("deezer");
                hist.push(StoredHistoryEntry {
                    track: StoredTrackRecord {
                        id: id.to_string(),
                        provider: item
                            .get("provider")
                            .and_then(|v| v.as_str())
                            .unwrap_or(inferred_provider)
                            .to_string(),
                        title: title.to_string(),
                        artist: item.get("artist").and_then(|v| v.as_str()).unwrap_or("").to_string(),
                        album: item.get("album").and_then(|v| v.as_str()).unwrap_or("").to_string(),
                        duration_ms: item.get("durationMs").and_then(|v| v.as_u64()).unwrap_or(0),
                        artwork_url: item.get("artworkUrl").and_then(|v| v.as_str()).map(ToOwned::to_owned),
                        playback_uri: None,
                        is_preview: false,
                        origin: "real".to_string(),
                    },
                    play_count: 1,
                    played_at_ms: base.saturating_sub((idx as u64) * 1000),
                });
            }
            imported_history = hist.len() as u32;
            self.upsert_history_batch(&hist)?;
        }

        if let Some(arr) = root.get("customThemes").and_then(|v| v.as_array()) {
            for item in arr {
                if let Ok(spec) = serde_json::from_value::<core_themes::CustomThemeSpec>(item.clone()) {
                    if self.save_user_custom_theme(&spec, crate::user_data_repo::now_epoch_ms()).is_ok() {
                        imported_themes += 1;
                    }
                }
            }
        }

        Ok(ImportBackupReport {
            imported_tracks,
            imported_history,
            imported_days,
            imported_themes,
        })
    }
}

fn csv_escape(value: &str) -> String {
    if value.contains(',') || value.contains('"') || value.contains('\n') {
        format!("\"{}\"", value.replace('"', "\"\""))
    } else {
        value.to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_track(id: &str, title: &str, artist: &str, album: &str) -> StoredTrackRecord {
        StoredTrackRecord {
            id: id.into(),
            provider: "deezer".into(),
            title: title.into(),
            artist: artist.into(),
            album: album.into(),
            duration_ms: 210_000,
            artwork_url: None,
            playback_uri: None,
            is_preview: false,
            origin: "real".into(),
        }
    }

    #[test]
    fn streaks_personas_period_and_csv_import_work() {
        let db = Database::in_memory().unwrap();
        let tz = 3 * MS_PER_HOUR; // UTC+3
        let day1_ms = 1_760_000_000_000u64;
        let day2_ms = day1_ms + MS_PER_DAY as u64;
        let t1 = sample_track("deezer:1", "Time", "Pink Floyd", "Dark Side");
        let t2 = sample_track("deezer:2", "Money", "Pink Floyd", "Dark Side");

        db.record_track_play_stat(&t1, day1_ms).unwrap();
        db.record_analytics_play(&t1, day1_ms, tz).unwrap();
        db.record_listening_tick(&t1, 180_000, day1_ms).unwrap();
        db.record_analytics_tick(&t1, 180_000, day1_ms, tz).unwrap();
        db.record_track_outcome(true, day1_ms, tz).unwrap();

        db.record_track_play_stat(&t2, day2_ms).unwrap();
        db.record_analytics_play(&t2, day2_ms, tz).unwrap();
        db.record_listening_tick(&t2, 120_000, day2_ms).unwrap();
        db.record_analytics_tick(&t2, 120_000, day2_ms, tz).unwrap();
        db.record_track_outcome(false, day2_ms, tz).unwrap();

        let summary = db.get_listening_analytics("7d", "plays", day2_ms, tz).unwrap();
        assert_eq!(summary.current_streak_days, 2);
        assert_eq!(summary.longest_streak_days, 2);
        assert_eq!(summary.completed_plays, 1);
        assert_eq!(summary.skipped_plays, 1);
        assert_eq!(summary.completion_rate_pct, 50);
        assert_eq!(summary.top_albums.len(), 1);
        assert_eq!(summary.top_artists.len(), 1);

        let csv = db.build_listening_stats_csv().unwrap();
        assert!(csv.contains("Pink Floyd"));
        assert!(csv.contains("Dark Side"));

        // Delete single track
        db.delete_single_track_stat("deezer:2").unwrap();
        let after = db.get_listening_analytics("all", "plays", day2_ms, tz).unwrap();
        assert_eq!(after.unique_tracks, 1);
    }
}
