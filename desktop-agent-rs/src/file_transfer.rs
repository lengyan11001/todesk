use std::{
    fs,
    io::Write,
    path::{Path, PathBuf},
};

use anyhow::{Context, Result};
use directories::UserDirs;
use sha2::{Digest, Sha256};

use crate::protocol::{FileTransferRequest, FileTransferStatus};

pub async fn receive(request: FileTransferRequest) -> FileTransferStatus {
    match receive_inner(&request).await {
        Ok((path, bytes)) => FileTransferStatus {
            transfer_id: request.transfer_id,
            status: "saved".to_string(),
            path: path.display().to_string(),
            bytes,
            error: String::new(),
        },
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

async fn receive_inner(request: &FileTransferRequest) -> Result<(PathBuf, u64)> {
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
    Ok((path, bytes.len() as u64))
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

fn receive_dir() -> Result<PathBuf> {
    let base = UserDirs::new()
        .and_then(|dirs| dirs.download_dir().map(Path::to_path_buf))
        .or_else(|| std::env::var_os("USERPROFILE").map(|path| PathBuf::from(path).join("Downloads")))
        .context("resolve downloads directory")?;
    Ok(base.join("BHZN-ToDesk"))
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
