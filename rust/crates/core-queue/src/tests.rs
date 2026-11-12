use super::*;
use core_domain::{DataOrigin, ProviderId, TrackId};

fn track(n: u32) -> Track {
    Track {
        id: TrackId::new(ProviderId::dev(), format!("t{n}")).unwrap(),
        title: format!("Track {n}"),
        artist: "Artist".into(),
        album: "Album".into(),
        duration_ms: 180_000,
        artwork_url: None,
        playback_uri: None,
        is_preview: false,
        origin: DataOrigin::DevelopmentFixture,
    }
}

fn tracks(n: u32) -> Vec<Track> {
    (0..n).map(track).collect()
}

fn titles(q: &Queue) -> Vec<String> {
    q.entries().iter().map(|e| e.track.title.clone()).collect()
}

fn cur_title(q: &Queue) -> Option<String> {
    q.current().map(|e| e.track.title.clone())
}

#[test]
fn replace_starts_at_index() {
    let mut q = Queue::default();
    q.replace(tracks(5), 2);
    assert_eq!(cur_title(&q).unwrap(), "Track 2");
    q.replace(tracks(3), 99);
    assert_eq!(cur_title(&q).unwrap(), "Track 2", "start index is clamped");
    q.replace(vec![], 0);
    assert!(q.current().is_none());
}

#[test]
fn advance_respects_repeat_modes() {
    let mut q = Queue::default();
    q.replace(tracks(2), 0);
    assert_eq!(
        q.advance(AdvanceReason::TrackEnded).unwrap().track.title,
        "Track 1"
    );
    assert!(
        q.advance(AdvanceReason::TrackEnded).is_none(),
        "repeat off stops"
    );

    q.set_repeat(RepeatMode::All);
    assert_eq!(
        q.advance(AdvanceReason::TrackEnded).unwrap().track.title,
        "Track 0"
    );

    q.set_repeat(RepeatMode::One);
    assert_eq!(
        q.advance(AdvanceReason::TrackEnded).unwrap().track.title,
        "Track 0",
        "repeat one replays on natural end"
    );
    assert_eq!(
        q.advance(AdvanceReason::UserSkip).unwrap().track.title,
        "Track 1",
        "explicit skip still advances in repeat one"
    );
}

#[test]
fn previous_restarts_after_threshold() {
    let mut q = Queue::default();
    q.replace(tracks(3), 1);
    assert_eq!(q.previous(10_000), PreviousOutcome::RestartCurrent);
    match q.previous(500) {
        PreviousOutcome::Moved(e) => assert_eq!(e.track.title, "Track 0"),
        other => panic!("unexpected {other:?}"),
    }
    assert_eq!(q.previous(0), PreviousOutcome::RestartCurrent, "at start");
    q.set_repeat(RepeatMode::All);
    assert!(matches!(q.previous(0), PreviousOutcome::Moved(e) if e.track.title == "Track 2"));
    assert_eq!(Queue::default().previous(0), PreviousOutcome::Nothing);
}

#[test]
fn play_next_inserts_after_current() {
    let mut q = Queue::default();
    q.replace(tracks(3), 0);
    q.play_next(vec![track(10)]);
    assert_eq!(
        titles(&q),
        vec!["Track 0", "Track 10", "Track 1", "Track 2"]
    );
    q.add_to_queue(vec![track(11)]);
    assert_eq!(titles(&q).last().unwrap(), "Track 11");
}

#[test]
fn adding_to_empty_queue_sets_current() {
    let mut q = Queue::default();
    q.add_to_queue(vec![track(1)]);
    assert_eq!(cur_title(&q).unwrap(), "Track 1");
    let mut q = Queue::default();
    q.play_next(vec![track(2)]);
    assert_eq!(cur_title(&q).unwrap(), "Track 2");
}

#[test]
fn duplicate_tracks_have_distinct_entries() {
    let mut q = Queue::default();
    q.replace(vec![track(1), track(1)], 0);
    let ids: Vec<u64> = q.entries().iter().map(|e| e.entry_id).collect();
    assert_ne!(ids[0], ids[1]);
    q.remove(ids[1]).unwrap();
    assert_eq!(q.len(), 1);
    assert_eq!(q.entries()[0].entry_id, ids[0]);
}

#[test]
fn remove_adjusts_current() {
    let mut q = Queue::default();
    q.replace(tracks(4), 2);
    let first = q.entries()[0].entry_id;
    assert_eq!(q.remove(first), Some(false));
    assert_eq!(cur_title(&q).unwrap(), "Track 2");

    let cur = q.current().unwrap().entry_id;
    assert_eq!(q.remove(cur), Some(true));
    assert_eq!(
        cur_title(&q).unwrap(),
        "Track 3",
        "next entry becomes current"
    );

    let cur = q.current().unwrap().entry_id;
    assert_eq!(q.remove(cur), Some(true));
    assert_eq!(
        cur_title(&q).unwrap(),
        "Track 1",
        "falls back to last entry"
    );

    let cur = q.current().unwrap().entry_id;
    assert_eq!(q.remove(cur), Some(true));
    assert!(q.current().is_none());
    assert_eq!(q.remove(12345), None);
}

#[test]
fn move_keeps_current_entry() {
    let mut q = Queue::default();
    q.replace(tracks(4), 1);
    assert!(q.move_entry(3, 0));
    assert_eq!(titles(&q), vec!["Track 3", "Track 0", "Track 1", "Track 2"]);
    assert_eq!(cur_title(&q).unwrap(), "Track 1");
    assert_eq!(q.current_index(), Some(2));
    assert!(!q.move_entry(0, 10));
}

#[test]
fn shuffle_keeps_current_and_is_reversible() {
    let mut q = Queue::new(42);
    q.replace(tracks(20), 5);
    let before = titles(&q);
    q.set_shuffle(true);
    assert!(q.is_shuffled());
    assert_eq!(q.current_index(), Some(0));
    assert_eq!(cur_title(&q).unwrap(), "Track 5");
    let shuffled = titles(&q);
    assert_ne!(shuffled, before, "20 items should not shuffle to identity");
    let mut sorted = shuffled.clone();
    sorted.sort();
    let mut sorted_before = before.clone();
    sorted_before.sort();
    assert_eq!(sorted, sorted_before, "shuffle is a permutation");

    q.set_shuffle(false);
    assert_eq!(titles(&q), before);
    assert_eq!(cur_title(&q).unwrap(), "Track 5");
}

#[test]
fn items_added_during_shuffle_survive_unshuffle() {
    let mut q = Queue::new(7);
    q.replace(tracks(5), 0);
    q.set_shuffle(true);
    q.play_next(vec![track(100)]);
    q.add_to_queue(vec![track(200)]);
    q.set_shuffle(false);
    assert_eq!(
        titles(&q),
        vec![
            "Track 0",
            "Track 100",
            "Track 1",
            "Track 2",
            "Track 3",
            "Track 4",
            "Track 200"
        ]
    );
}

#[test]
fn snapshot_roundtrip() {
    let mut q = Queue::new(1);
    q.replace(tracks(6), 3);
    q.set_repeat(RepeatMode::All);
    q.set_shuffle(true);
    let snap = q.snapshot();
    let json = serde_json::to_string(&snap).unwrap();
    let restored = Queue::restore(serde_json::from_str(&json).unwrap(), 1);
    assert_eq!(restored.snapshot(), snap);
    let mut restored = restored;
    let new_ids = restored.add_to_queue(vec![track(9)]);
    assert!(snap.entries.iter().all(|e| e.entry_id != new_ids[0]));
}

#[test]
fn repeat_mode_cycles() {
    assert_eq!(RepeatMode::Off.cycled(), RepeatMode::All);
    assert_eq!(RepeatMode::All.cycled(), RepeatMode::One);
    assert_eq!(RepeatMode::One.cycled(), RepeatMode::Off);
}
