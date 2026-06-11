use std::{
    fs,
    io::Write,
    path::{Path, PathBuf},
};

use anyhow::{Context, Result};
use directories::UserDirs;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::protocol::{FileTransferRequest, FileTransferStatus};

const MAX_RECORDS: usize = 100;

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FileTransferRecord {
    pub file_name: String,
    pub path: String,
    pub bytes: u64,
    pub received_at_ms: u64,
}

pub async fn receive(request: FileTransferRequest) -> FileTransferStatus {
    match receive_inner(&request).await {
        Ok(record) => {
            if let Err(error) = add_record(&record) {
                crate::log::warn(format!("file transfer record failed: {error:#}"));
            }
            FileTransferStatus {
            transfer_id: request.transfer_id,
            status: "saved".to_string(),
                path: record.path,
                bytes: record.bytes,
            error: String::new(),
            }
        }
        Err(error) => FileTransferStatus {
            transfer_id: request.transfer_id,
            status: "failed".to_string(),
            path: String::new(),
            bytes: 0,
            error: error.to_string(),
        },
    }
}

pub fn downloading_status(request: &FileTransferRequest) -> FileTransferStatus {
    FileTransferStatus {
        transfer_id: request.transfer_id.clone(),
        status: "downloading".to_string(),
        path: String::new(),
        bytes: 0,
        error: String::new(),
    }
}

pub fn recent_records(limit: usize) -> Vec<FileTransferRecord> {
    read_records().unwrap_or_default().into_iter().take(limit).collect()
}

pub fn receive_dir() -> Result<PathBuf> {
    let base = UserDirs::new()
        .and_then(|dirs| dirs.download_dir().map(Path::to_path_buf))
        .or_else(|| std::env::var_os("USERPROFILE").map(|path| PathBuf::from(path).join("Downloads")))
        .context("resolve downloads directory")?;
    Ok(base.join("BHZN-ToDesk"))
}

pub fn format_bytes(bytes: u64) -> String {
    if bytes < 1024 {
        format!("{bytes} B")
    } else if bytes < 1024 * 1024 {
        format!("{:.1} KB", bytes as f64 / 1024.0)
    } else {
        format!("{:.1} MB", bytes as f64 / 1024.0 / 1024.0)
    }
}

pub fn received_label(received_at_ms: u64) -> String {
    let now = now_ms();
    let elapsed = now.saturating_sub(received_at_ms) / 1000;
    if elapsed < 60 {
        "刚刚".to_string()
    } else if elapsed < 3600 {
        format!("{} 分钟前", elapsed / 60)
    } else if elapsed < 86_400 {
        format!("{} 小时前", elapsed / 3600)
    } else {
        format!("{} 天前", elapsed / 86_400)
    }
}

async fn receive_inner(request: &FileTransferRequest) -> Result<FileTransferRecord> {
    validate_request(request)?;
    let response = reqwest::get(&request.url).await?.error_for_status()?;
    let bytes = response.bytes().await?;
    if bytes.len() as u64 != request.size {
        anyhow::bail!("file size mismatch expected={} actual={}", request.size, bytes.len());
    }
    let actual = format!("{:x}", Sha256::digest(&bytes));
    if actual.to_ascii_lowercase() != request.sha256.to_ascii_lowercase() {
        anyhow::bail!("file sha256 mismatch");
    }

    let dir = receive_dir()?;
    fs::create_dir_all(&dir)?;
    let path = unique_path(&dir, &request.file_name);
    let temp_path = path.with_extension("download");
    {
        let mut file = fs::File::create(&temp_path).with_context(|| format!("create {}", temp_path.display()))?;
        file.write_all(&bytes)?;
        file.sync_all().ok();
    }
    fs::rename(&temp_path, &path).with_context(|| format!("save {}", path.display()))?;
    let file_name = path.file_name().and_then(|value| value.to_str()).unwrap_or("file.bin").to_string();
    Ok(FileTransferRecord {
        file_name,
        path: path.display().to_string(),
        bytes: bytes.len() as u64,
        received_at_ms: now_ms(),
    })
}

fn validate_request(request: &FileTransferRequest) -> Result<()> {
    let url = url::Url::parse(&request.url).context("parse file url")?;
    if url.scheme() != "https" && !is_local_http(&url) {
        anyhow::bail!("file transfer requires https url");
    }
    if request.size == 0 {
        anyhow::bail!("empty file");
    }
    if request.size > 100 * 1024 * 1024 {
        anyhow::bail!("file too large");
    }
    if request.sha256.len() != 64 || !request.sha256.chars().all(|ch| ch.is_ascii_hexdigit()) {
        anyhow::bail!("bad sha256");
    }
    Ok(())
}

fn unique_path(dir: &Path, file_name: &str) -> PathBuf {
    let sanitized = sanitize_filename::sanitize(file_name.trim());
    let name = if sanitized.is_empty() { "file.bin".to_string() } else { sanitized };
    let path = dir.join(&name);
    if !path.exists() {
        return path;
    }
    let stem = Path::new(&name).file_stem().and_then(|value| value.to_str()).unwrap_or("file");
    let ext = Path::new(&name).extension().and_then(|value| value.to_str()).unwrap_or("");
    for index in 1..1000 {
        let candidate = if ext.is_empty() {
            dir.join(format!("{stem} ({index})"))
        } else {
            dir.join(format!("{stem} ({index}).{ext}"))
        };
        if !candidate.exists() {
            return candidate;
        }
    }
    dir.join(format!("{}-{}", chrono_like_timestamp(), name))
}

fn chrono_like_timestamp() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

fn is_local_http(url: &url::Url) -> bool {
    url.scheme() == "http" && matches!(url.host_str(), Some("localhost" | "127.0.0.1" | "::1"))
}

fn add_record(record: &FileTransferRecord) -> Result<()> {
    let mut records = read_records().unwrap_or_default();
    records.insert(0, record.clone());
    records.truncate(MAX_RECORDS);
    write_records(&records)
}

fn read_records() -> Result<Vec<FileTransferRecord>> {
    let path = records_path()?;
    if !path.exists() {
        return Ok(Vec::new());
    }
    let text = fs::read_to_string(path)?;
    Ok(serde_json::from_str(&text).unwrap_or_default())
}

fn write_records(records: &[FileTransferRecord]) -> Result<()> {
    let path = records_path()?;
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let temp = path.with_extension("json.tmp");
    fs::write(&temp, serde_json::to_string_pretty(records)?)?;
    fs::rename(temp, path)?;
    Ok(())
}

fn records_path() -> Result<PathBuf> {
    if cfg!(target_os = "windows") {
        let root = std::env::var_os("APPDATA").context("APPDATA is not set")?;
        return Ok(PathBuf::from(root).join("BHZN-ToDesk").join("received-files.json"));
    }
    let home = std::env::var_os("HOME").context("HOME is not set")?;
    Ok(PathBuf::from(home).join(".bhzn-todesk").join("received-files.json"))
}

fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
