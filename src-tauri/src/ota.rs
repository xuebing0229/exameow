use serde::{Deserialize, Serialize};
use std::borrow::Cow;
use std::path::{Component, Path, PathBuf};
use std::sync::{OnceLock, RwLock};
use tauri::utils::assets::{AssetKey, CspHash};
use tauri::{App, Assets, Manager, Runtime};

const MANIFEST_URL: &str =
    "https://github.com/xuebing0229/exameow/releases/latest/download/mobile-ota.json";
const MAX_DOWNLOAD_BYTES: u64 = 64 * 1024 * 1024;
const MAX_EXTRACT_BYTES: u64 = 256 * 1024 * 1024;

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct BundleRef {
    pub version: String,
    pub sha256: String,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
struct OtaState {
    #[serde(default)]
    shell_version: String,
    #[serde(default)]
    max_version_seen: String,
    #[serde(default)]
    staged: Option<BundleRef>,
    #[serde(default)]
    booting: Option<BundleRef>,
    #[serde(default)]
    committed: Option<BundleRef>,
    #[serde(default)]
    blacklist: Vec<String>,
}

pub struct OtaHandle {
    dir: PathBuf,
    state: OtaState,
    active: Option<BundleRef>,
}

static HANDLE: OnceLock<RwLock<Option<OtaHandle>>> = OnceLock::new();

fn handle_cell() -> &'static RwLock<Option<OtaHandle>> {
    HANDLE.get_or_init(|| RwLock::new(None))
}

fn state_path(dir: &Path) -> PathBuf {
    dir.join("state.json")
}

fn persist(dir: &Path, state: &OtaState) -> Result<(), String> {
    let tmp = dir.join("state.json.tmp");
    let data = serde_json::to_string(state).map_err(|e| format!("State encode error: {e}"))?;
    std::fs::write(&tmp, data).map_err(|e| format!("State write error: {e}"))?;
    std::fs::rename(&tmp, state_path(dir)).map_err(|e| format!("State rename error: {e}"))?;
    Ok(())
}

fn bundle_dir(dir: &Path, sha256: &str) -> PathBuf {
    dir.join("bundles").join(sha256)
}

pub fn boot(app_data_dir: PathBuf) {
    let dir = app_data_dir.join("ota");
    let _ = std::fs::create_dir_all(&dir);

    let mut state: OtaState = std::fs::read_to_string(state_path(&dir))
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default();

    let shell = env!("CARGO_PKG_VERSION").to_string();

    if state.shell_version != shell {
        let _ = std::fs::remove_dir_all(&dir);
        let _ = std::fs::create_dir_all(&dir);
        state = OtaState {
            shell_version: shell.clone(),
            max_version_seen: shell.clone(),
            ..Default::default()
        };
        let _ = persist(&dir, &state);
    }
    if state.max_version_seen.is_empty() {
        state.max_version_seen = shell.clone();
        let _ = persist(&dir, &state);
    }

    if let Some(b) = state.booting.take() {
        if !state.blacklist.contains(&b.sha256) {
            state.blacklist.push(b.sha256.clone());
        }
        let _ = std::fs::remove_dir_all(bundle_dir(&dir, &b.sha256));
        let _ = persist(&dir, &state);
    }

    if let Some(s) = state.staged.take() {
        if bundle_dir(&dir, &s.sha256).join("index.html").exists() {
            state.booting = Some(s);
        } else {
            let _ = std::fs::remove_dir_all(bundle_dir(&dir, &s.sha256));
        }
        let _ = persist(&dir, &state);
    }

    let active = state.booting.clone().or_else(|| state.committed.clone());
    if let Ok(mut guard) = handle_cell().write() {
        *guard = Some(OtaHandle { dir, state, active });
    }
}

fn snapshot() -> Option<(PathBuf, Option<BundleRef>)> {
    let guard = handle_cell().read().ok()?;
    let h = guard.as_ref()?;
    Some((h.dir.clone(), h.active.clone()))
}

fn is_safe_rel(rel: &str) -> bool {
    !rel.is_empty() && !rel.split('/').any(|c| c == ".." || c.is_empty())
}

pub struct OtaAssets<R: Runtime> {
    embedded: Option<Box<dyn Assets<R>>>,
}

impl<R: Runtime> OtaAssets<R> {
    pub fn new(embedded: Box<dyn Assets<R>>) -> Self {
        Self {
            embedded: Some(embedded),
        }
    }

    pub fn uninit() -> Self {
        Self { embedded: None }
    }
}

impl<R: Runtime> Assets<R> for OtaAssets<R> {
    fn setup(&self, app: &App<R>) {
        if let Ok(dir) = app.path().app_data_dir() {
            boot(dir);
        }
    }

    fn get(&self, key: &AssetKey) -> Option<Cow<'_, [u8]>> {
        let rel = key.as_ref().trim_start_matches('/');
        if is_safe_rel(rel) {
            if let Some((dir, Some(active))) = snapshot() {
                if let Ok(bytes) = std::fs::read(bundle_dir(&dir, &active.sha256).join(rel)) {
                    return Some(Cow::Owned(bytes));
                }
            }
        }
        self.embedded.as_ref().and_then(|e| e.get(key))
    }

    fn iter(&self) -> Box<tauri::utils::assets::AssetsIter<'_>> {
        match self.embedded.as_ref() {
            Some(e) => e.iter(),
            None => Box::new(std::iter::empty()),
        }
    }

    fn csp_hashes(&self, html_path: &AssetKey) -> Box<dyn Iterator<Item = CspHash<'_>> + '_> {
        match self.embedded.as_ref() {
            Some(e) => e.csp_hashes(html_path),
            None => Box::new(std::iter::empty()),
        }
    }
}

#[derive(Debug, Deserialize)]
struct Manifest {
    version: String,
    #[serde(rename = "minShell")]
    min_shell: String,
    url: String,
    sha256: String,
    size: u64,
}

fn validate_manifest(m: &Manifest) -> Result<(), String> {
    if semver::Version::parse(&m.version).is_err() {
        return Err(format!("Invalid manifest version: {}", m.version));
    }
    if semver::Version::parse(&m.min_shell).is_err() {
        return Err(format!("Invalid manifest minShell: {}", m.min_shell));
    }
    if m.sha256.len() != 64 || !m.sha256.chars().all(|c| c.is_ascii_hexdigit()) {
        return Err("Invalid manifest sha256".to_string());
    }
    if !m.url.starts_with("https://") {
        return Err("Manifest url must be https".to_string());
    }
    if m.size == 0 || m.size > MAX_DOWNLOAD_BYTES {
        return Err(format!("Invalid manifest size: {}", m.size));
    }
    Ok(())
}

#[derive(Debug, PartialEq)]
enum Decision {
    Accept,
    UpToDate,
    ShellTooOld,
    Blacklisted,
    AlreadyStaged,
}

fn decide(
    shell: &str,
    max_seen: &str,
    blacklist: &[String],
    staged: Option<&BundleRef>,
    m: &Manifest,
) -> Decision {
    let shell_v = match semver::Version::parse(shell) {
        Ok(v) => v,
        Err(_) => return Decision::UpToDate,
    };
    let min_v = match semver::Version::parse(&m.min_shell) {
        Ok(v) => v,
        Err(_) => return Decision::UpToDate,
    };
    let m_v = match semver::Version::parse(&m.version) {
        Ok(v) => v,
        Err(_) => return Decision::UpToDate,
    };
    if blacklist.iter().any(|b| b == &m.sha256) {
        return Decision::Blacklisted;
    }
    if staged.map(|s| s.sha256.as_str()) == Some(m.sha256.as_str()) {
        return Decision::AlreadyStaged;
    }
    if shell_v < min_v {
        return Decision::ShellTooOld;
    }
    let seen_v = semver::Version::parse(max_seen).unwrap_or_else(|_| shell_v.clone());
    let threshold = if seen_v > shell_v { seen_v } else { shell_v };
    if m_v <= threshold {
        return Decision::UpToDate;
    }
    Decision::Accept
}

#[derive(Clone, Serialize)]
pub struct OtaStatus {
    pub status: String,
    pub version: Option<String>,
    pub error: Option<String>,
}

impl OtaStatus {
    fn new(status: &str, version: Option<String>) -> Self {
        Self {
            status: status.to_string(),
            version,
            error: None,
        }
    }
    fn err(e: &str) -> Self {
        Self {
            status: "error".to_string(),
            version: None,
            error: Some(e.to_string()),
        }
    }
}

fn http_client() -> Result<reqwest::Client, String> {
    reqwest::Client::builder()
        .use_rustls_tls()
        .connect_timeout(std::time::Duration::from_secs(10))
        .timeout(std::time::Duration::from_secs(120))
        .build()
        .map_err(|e| format!("HTTP client error: {e}"))
}

async fn fetch_manifest(client: &reqwest::Client) -> Result<Manifest, String> {
    let resp = client
        .get(MANIFEST_URL)
        .send()
        .await
        .map_err(|e| format!("Manifest fetch error: {e}"))?;
    if !resp.status().is_success() {
        return Err(format!("Manifest HTTP {}", resp.status()));
    }
    let m: Manifest = resp
        .json()
        .await
        .map_err(|e| format!("Manifest parse error: {e}"))?;
    validate_manifest(&m)?;
    Ok(m)
}

fn decision_status(d: Decision, m: &Manifest) -> OtaStatus {
    let v = Some(m.version.clone());
    match d {
        Decision::Accept => OtaStatus::new("available", v),
        Decision::UpToDate => OtaStatus::new("upToDate", None),
        Decision::ShellTooOld => OtaStatus::new("shellTooOld", v),
        Decision::Blacklisted => OtaStatus::new("upToDate", None),
        Decision::AlreadyStaged => OtaStatus::new("alreadyStaged", v),
    }
}

fn with_handle<R>(f: impl FnOnce(&mut OtaHandle) -> R) -> Result<R, String> {
    let mut guard = handle_cell()
        .write()
        .map_err(|_| "OTA lock poisoned".to_string())?;
    let h = guard.as_mut().ok_or("OTA not initialized".to_string())?;
    Ok(f(h))
}

fn sha256_hex(bytes: &[u8]) -> String {
    use sha2::Digest;
    let mut h = sha2::Sha256::new();
    h.update(bytes);
    h.finalize().iter().map(|b| format!("{b:02x}")).collect()
}

fn safe_entry_rel(path: &Path) -> Option<PathBuf> {
    let mut out = PathBuf::new();
    for c in path.components() {
        match c {
            Component::Normal(s) => out.push(s),
            Component::CurDir => {}
            _ => return None,
        }
    }
    if out.as_os_str().is_empty() {
        None
    } else {
        Some(out)
    }
}

fn extract_bundle(tar_gz: &[u8], dest: &Path) -> Result<(), String> {
    let gz = flate2::read::GzDecoder::new(tar_gz);
    let mut archive = tar::Archive::new(gz);
    let mut total: u64 = 0;
    let entries = archive
        .entries()
        .map_err(|e| format!("Tar read error: {e}"))?;
    for entry in entries {
        let mut entry = entry.map_err(|e| format!("Tar entry error: {e}"))?;
        let raw_path = entry
            .path()
            .map_err(|e| format!("Tar path error: {e}"))?
            .into_owned();
        let rel = match safe_entry_rel(&raw_path) {
            Some(r) => r,
            None => continue,
        };
        let ty = entry.header().entry_type();
        let target = dest.join(&rel);
        if ty.is_dir() {
            std::fs::create_dir_all(&target).map_err(|e| format!("Extract mkdir error: {e}"))?;
        } else if ty.is_file() {
            total = total.saturating_add(entry.size());
            if total > MAX_EXTRACT_BYTES {
                return Err("Extracted size limit exceeded".to_string());
            }
            if let Some(parent) = target.parent() {
                std::fs::create_dir_all(parent)
                    .map_err(|e| format!("Extract mkdir error: {e}"))?;
            }
            let mut out =
                std::fs::File::create(&target).map_err(|e| format!("Extract create error: {e}"))?;
            std::io::copy(&mut entry, &mut out).map_err(|e| format!("Extract write error: {e}"))?;
        }
    }
    Ok(())
}

static DOWNLOADING: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

async fn run_check() -> Result<(Manifest, Decision), String> {
    let (shell, max_seen, blacklist, staged) = with_handle(|h| {
        (
            h.state.shell_version.clone(),
            h.state.max_version_seen.clone(),
            h.state.blacklist.clone(),
            h.state.staged.clone(),
        )
    })?;
    let client = http_client()?;
    let m = fetch_manifest(&client).await?;
    let d = decide(&shell, &max_seen, &blacklist, staged.as_ref(), &m);
    Ok((m, d))
}

#[tauri::command]
pub async fn ota_check() -> OtaStatus {
    match run_check().await {
        Ok((m, d)) => decision_status(d, &m),
        Err(e) => OtaStatus::err(&e),
    }
}

#[tauri::command]
pub async fn ota_download() -> OtaStatus {
    if DOWNLOADING
        .compare_exchange(
            false,
            true,
            std::sync::atomic::Ordering::SeqCst,
            std::sync::atomic::Ordering::SeqCst,
        )
        .is_err()
    {
        return OtaStatus::new("downloading", None);
    }
    let result = ota_download_inner().await;
    DOWNLOADING.store(false, std::sync::atomic::Ordering::SeqCst);
    result
}

async fn ota_download_inner() -> OtaStatus {
    let (m, d) = match run_check().await {
        Ok(r) => r,
        Err(e) => return OtaStatus::err(&e),
    };
    if d != Decision::Accept {
        return decision_status(d, &m);
    }

    let client = match http_client() {
        Ok(c) => c,
        Err(e) => return OtaStatus::err(&e),
    };
    let bytes = match async {
        let resp = client
            .get(&m.url)
            .send()
            .await
            .map_err(|e| format!("Bundle fetch error: {e}"))?;
        if !resp.status().is_success() {
            return Err(format!("Bundle HTTP {}", resp.status()));
        }
        resp.bytes()
            .await
            .map_err(|e| format!("Bundle read error: {e}"))
    }
    .await
    {
        Ok(b) => b,
        Err(e) => return OtaStatus::err(&e),
    };

    if bytes.len() as u64 != m.size {
        return OtaStatus::err(&format!(
            "Bundle size mismatch: expected {}, got {}",
            m.size,
            bytes.len()
        ));
    }
    if sha256_hex(&bytes) != m.sha256.to_lowercase() {
        return OtaStatus::err("Bundle sha256 mismatch");
    }

    match with_handle(|h| -> Result<(), String> {
        let tmp = h.dir.join("tmp").join(&m.sha256);
        let _ = std::fs::remove_dir_all(&tmp);
        std::fs::create_dir_all(&tmp).map_err(|e| format!("Tmp dir error: {e}"))?;
        let r = extract_bundle(&bytes, &tmp);
        if r.is_err() {
            let _ = std::fs::remove_dir_all(&tmp);
            return r;
        }
        if !tmp.join("index.html").exists() {
            let _ = std::fs::remove_dir_all(&tmp);
            return Err("Bundle missing index.html".to_string());
        }
        let dest = bundle_dir(&h.dir, &m.sha256);
        let _ = std::fs::remove_dir_all(&dest);
        if let Some(parent) = dest.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        std::fs::rename(&tmp, &dest).map_err(|e| format!("Bundle rename error: {e}"))?;
        h.state.staged = Some(BundleRef {
            version: m.version.clone(),
            sha256: m.sha256.clone(),
        });
        h.state.max_version_seen = m.version.clone();
        persist(&h.dir, &h.state)?;
        Ok(())
    }) {
        Ok(Ok(())) => {}
        Ok(Err(e)) => return OtaStatus::err(&e),
        Err(e) => return OtaStatus::err(&e),
    }

    OtaStatus::new("staged", Some(m.version.clone()))
}

#[tauri::command]
pub fn ota_notify_ready() -> Result<(), String> {
    with_handle(|h| -> Result<(), String> {
        if let Some(b) = h.state.booting.take() {
            if let Some(old) = h.state.committed.replace(b.clone()) {
                if old.sha256 != b.sha256 {
                    let _ = std::fs::remove_dir_all(bundle_dir(&h.dir, &old.sha256));
                }
            }
            persist(&h.dir, &h.state)?;
        }
        Ok(())
    })?
}

#[tauri::command]
pub fn ota_current() -> OtaStatus {
    match snapshot() {
        Some((_, Some(active))) => OtaStatus::new("ota", Some(active.version)),
        _ => OtaStatus::new("embedded", None),
    }
}

#[tauri::command]
pub fn ota_reset() -> Result<(), String> {
    let mut guard = handle_cell()
        .write()
        .map_err(|_| "OTA lock poisoned".to_string())?;
    if let Some(h) = guard.as_ref() {
        let _ = std::fs::remove_dir_all(&h.dir);
    }
    *guard = None;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn manifest(version: &str, min_shell: &str, sha: &str) -> Manifest {
        Manifest {
            version: version.to_string(),
            min_shell: min_shell.to_string(),
            url: "https://example.com/b.tar.gz".to_string(),
            sha256: sha.to_string(),
            size: 1024,
        }
    }

    #[test]
    fn accept_newer_version() {
        let m = manifest("1.3.0", "1.2.0", &"a".repeat(64));
        assert_eq!(
            decide("1.2.2", "1.2.2", &[], None, &m),
            Decision::Accept
        );
    }

    #[test]
    fn reject_same_or_older() {
        let m = manifest("1.2.2", "1.2.0", &"a".repeat(64));
        assert_eq!(decide("1.2.2", "1.2.2", &[], None, &m), Decision::UpToDate);
        let m2 = manifest("1.2.1", "1.2.0", &"b".repeat(64));
        assert_eq!(decide("1.2.2", "1.2.2", &[], None, &m2), Decision::UpToDate);
    }

    #[test]
    fn reject_replay_of_seen_version() {
        let m = manifest("1.2.5", "1.2.0", &"a".repeat(64));
        assert_eq!(
            decide("1.2.2", "1.2.5", &[], None, &m),
            Decision::UpToDate
        );
    }

    #[test]
    fn reject_when_shell_too_old() {
        let m = manifest("1.3.0", "1.2.5", &"a".repeat(64));
        assert_eq!(
            decide("1.2.2", "1.2.2", &[], None, &m),
            Decision::ShellTooOld
        );
    }

    #[test]
    fn reject_blacklisted_hash() {
        let sha = "c".repeat(64);
        let m = manifest("1.3.0", "1.2.0", &sha);
        assert_eq!(
            decide("1.2.2", "1.2.2", &[sha], None, &m),
            Decision::Blacklisted
        );
    }

    #[test]
    fn detect_already_staged() {
        let sha = "d".repeat(64);
        let m = manifest("1.3.0", "1.2.0", &sha);
        let staged = BundleRef {
            version: "1.3.0".to_string(),
            sha256: sha,
        };
        assert_eq!(
            decide("1.2.2", "1.2.2", &[], Some(&staged), &m),
            Decision::AlreadyStaged
        );
    }

    #[test]
    fn safe_entry_rel_rejects_traversal() {
        assert!(safe_entry_rel(Path::new("assets/index.js")).is_some());
        assert!(safe_entry_rel(Path::new("./index.html")).is_some());
        assert!(safe_entry_rel(Path::new("../evil")).is_none());
        assert!(safe_entry_rel(Path::new("/abs/path")).is_none());
        assert!(safe_entry_rel(Path::new("a/../../b")).is_none());
        assert!(safe_entry_rel(Path::new("")).is_none());
    }

    #[test]
    fn asset_rel_safety() {
        assert!(is_safe_rel("index.html"));
        assert!(is_safe_rel("assets/index-abc.js"));
        assert!(!is_safe_rel(""));
        assert!(!is_safe_rel("../secret"));
        assert!(!is_safe_rel("a//b"));
    }

    #[test]
    fn manifest_validation() {
        assert!(validate_manifest(&manifest("1.3.0", "1.2.0", &"a".repeat(64))).is_ok());
        assert!(validate_manifest(&manifest("x.y", "1.2.0", &"a".repeat(64))).is_err());
        assert!(validate_manifest(&manifest("1.3.0", "1.2.0", "short")).is_err());
        let mut m = manifest("1.3.0", "1.2.0", &"a".repeat(64));
        m.url = "http://insecure/b.tar.gz".to_string();
        assert!(validate_manifest(&m).is_err());
    }
}
