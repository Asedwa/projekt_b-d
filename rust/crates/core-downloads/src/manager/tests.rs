use super::*;
use crate::{user_supplied_download, MemoryDownloadRepository};
use wiremock::matchers::{header, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

const BODY: &[u8] = b"0123456789abcdefghijklmnopqrstuvwxyz";

fn sha(data: &[u8]) -> String {
    hex::encode(Sha256::digest(data))
}

fn manager(dir: &Path) -> DownloadManager {
    let mut cfg = ManagerConfig::new(dir);
    cfg.allow_insecure_loopback = true;
    DownloadManager::new(Arc::new(MemoryDownloadRepository::default()), cfg).unwrap()
}

fn auth(server: &MockServer, p: &str, sum: Option<String>) -> AuthorizedDownload {
    AuthorizedDownload {
        track_id: TrackId::parse(&format!("local:test{p}")).unwrap(),
        url: format!("{}{}", server.uri(), p),
        file_name: "song.ogg".into(),
        expected_size: None,
        expected_sha256: sum,
        supports_resume: true,
    }
}

#[tokio::test]
async fn completes_and_verifies_checksum() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/a.ogg"))
        .respond_with(ResponseTemplate::new(200).set_body_bytes(BODY))
        .mount(&server)
        .await;
    let dir = tempfile::tempdir().unwrap();
    let m = manager(dir.path());
    let t = m.enqueue(auth(&server, "/a.ogg", Some(sha(BODY)))).unwrap();
    let t = m.run(t.id).await.unwrap();
    assert_eq!(t.state, DownloadState::Completed, "{:?}", t.error);
    assert_eq!(t.progress_percent(), Some(100));
    let p = m.offline_path(&t.track_id).unwrap().expect("offline");
    assert_eq!(std::fs::read(p).unwrap(), BODY);
    // Duplicate enqueue returns the completed task.
    assert_eq!(m.enqueue(auth(&server, "/a.ogg", None)).unwrap().id, t.id);
}

#[tokio::test]
async fn checksum_mismatch_fails_and_cleans_up() {
    let server = MockServer::start().await;
    Mock::given(path("/b.ogg"))
        .respond_with(ResponseTemplate::new(200).set_body_bytes(BODY))
        .mount(&server)
        .await;
    let dir = tempfile::tempdir().unwrap();
    let m = manager(dir.path());
    let t = m
        .enqueue(auth(&server, "/b.ogg", Some(sha(b"other"))))
        .unwrap();
    let t = m.run(t.id).await.unwrap();
    assert_eq!(t.state, DownloadState::Failed);
    assert!(t.error.unwrap().contains("integrity"));
    assert_eq!(m.used_bytes(), 0);
    assert!(m.offline_path(&t.track_id).unwrap().is_none());
}

#[tokio::test]
async fn resumes_with_range_request() {
    let server = MockServer::start().await;
    Mock::given(path("/c.ogg"))
        .and(header("range", "bytes=10-"))
        .respond_with(ResponseTemplate::new(206).set_body_bytes(&BODY[10..]))
        .mount(&server)
        .await;
    let dir = tempfile::tempdir().unwrap();
    let m = manager(dir.path());
    let t = m.enqueue(auth(&server, "/c.ogg", Some(sha(BODY)))).unwrap();
    std::fs::write(dir.path().join(format!("{}.part", t.id)), &BODY[..10]).unwrap();
    let t = m.run(t.id).await.unwrap();
    assert_eq!(t.state, DownloadState::Completed, "{:?}", t.error);
    assert_eq!(std::fs::read(m.completed_path(&t)).unwrap(), BODY);
}

#[tokio::test]
async fn http_errors_fail_and_can_be_retried() {
    let server = MockServer::start().await;
    Mock::given(path("/missing"))
        .respond_with(ResponseTemplate::new(404))
        .mount(&server)
        .await;
    let dir = tempfile::tempdir().unwrap();
    let m = manager(dir.path());
    let t = m.enqueue(auth(&server, "/missing", None)).unwrap();
    let t = m.run(t.id).await.unwrap();
    assert_eq!(t.state, DownloadState::Failed);
    assert!(t.error.as_deref().unwrap().contains("404"));
    assert_eq!(m.retry(t.id).unwrap().state, DownloadState::Queued);
}

#[tokio::test]
async fn storage_limit_is_enforced() {
    let server = MockServer::start().await;
    Mock::given(path("/big"))
        .respond_with(ResponseTemplate::new(200).set_body_bytes(BODY))
        .mount(&server)
        .await;
    let dir = tempfile::tempdir().unwrap();
    let mut cfg = ManagerConfig::new(dir.path());
    cfg.allow_insecure_loopback = true;
    cfg.storage_limit_bytes = Some(10);
    let m = DownloadManager::new(Arc::new(MemoryDownloadRepository::default()), cfg).unwrap();
    let t = m.enqueue(auth(&server, "/big", None)).unwrap();
    let t = m.run(t.id).await.unwrap();
    assert_eq!(t.state, DownloadState::Failed);
    assert!(t.error.unwrap().contains("storage limit"));
}

#[test]
fn rejects_insecure_and_lifecycle_transitions() {
    let dir = tempfile::tempdir().unwrap();
    let m = DownloadManager::new(
        Arc::new(MemoryDownloadRepository::default()),
        ManagerConfig::new(dir.path()),
    )
    .unwrap();
    let mut bad = user_supplied_download("https://x.example/a.ogg", None).unwrap();
    bad.url = "http://x.example/a.ogg".into();
    assert!(m.enqueue(bad).is_err());

    let t = m
        .enqueue(user_supplied_download("https://x.example/a.ogg", None).unwrap())
        .unwrap();
    assert_eq!(m.pause(t.id).unwrap().state, DownloadState::Paused);
    assert_eq!(m.require(t.id).unwrap().state, DownloadState::Paused);
    assert_eq!(m.resume(t.id).unwrap().state, DownloadState::Queued);
    assert_eq!(m.cancel(t.id).unwrap().state, DownloadState::Cancelled);
    m.remove(t.id).unwrap();
    assert!(m.list().unwrap().is_empty());
}

#[test]
fn recovers_running_tasks_after_restart() {
    let dir = tempfile::tempdir().unwrap();
    let repo = Arc::new(MemoryDownloadRepository::default());
    let m = DownloadManager::new(repo.clone(), ManagerConfig::new(dir.path())).unwrap();
    let mut t = m
        .enqueue(user_supplied_download("https://x.example/a.ogg", None).unwrap())
        .unwrap();
    t.state = DownloadState::Running;
    repo.update(&t).unwrap();
    assert_eq!(m.recover_after_restart().unwrap(), 1);
    assert_eq!(m.require(t.id).unwrap().state, DownloadState::Queued);
}
