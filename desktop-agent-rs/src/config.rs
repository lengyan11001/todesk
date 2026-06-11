use std::{fs, path::PathBuf};

use anyhow::{Context, Result};
use directories::ProjectDirs;
use rand::{distr::Alphanumeric, Rng};
use serde::{Deserialize, Serialize};
use url::Url;

use crate::secure;

const DEFAULT_SERVER_KEY: u8 = 0x5A;
const DEFAULT_SERVER_DATA: &[u8] = &[
    50, 46, 46, 42, 41, 96, 117, 117, 46, 53, 62, 63, 41, 49, 116, 56, 50, 32, 52, 116, 46, 53, 42,
];

#[derive(Clone, Debug)]
pub struct AgentConfig {
    pub path: PathBuf,
    pub server: String,
    pub device_id: String,
    pub verification_code: String,
    pub name: String,
}

#[derive(Debug, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct ConfigFile {
    server: Option<String>,
    device_id: Option<String>,
    verification_code: Option<String>,
    name: Option<String>,
    protected_server: Option<String>,
    protected_device_id: Option<String>,
    protected_verification_code: Option<String>,
    protected_name: Option<String>,
}

impl AgentConfig {
    pub fn load(custom_path: Option<&str>) -> Result<Self> {
        let path = match custom_path {
            Some(value) if !value.trim().is_empty() => PathBuf::from(value),
            _ => config_path()?,
        };
        let file = if path.exists() {
            let text = fs::read_to_string(&path).context("read config")?;
            serde_json::from_str::<ConfigFile>(&text).unwrap_or_default()
        } else {
            ConfigFile::default()
        };

        let config = Self {
            path,
            server: normalize_server(
                unprotect_value(file.protected_server.as_deref())
                    .or(file.server)
                    .unwrap_or_else(default_server_url)
                    .as_str(),
            ),
            device_id: normalize_device_id(
                unprotect_value(file.protected_device_id.as_deref())
                    .or(file.device_id)
                    .unwrap_or_default()
                    .as_str(),
            ),
            verification_code: normalize_code(
                unprotect_value(file.protected_verification_code.as_deref())
                    .or(file.verification_code)
                    .unwrap_or_default()
                    .as_str(),
            ),
            name: unprotect_value(file.protected_name.as_deref()).or(file.name).unwrap_or_else(default_device_name),
        };
        config.save()?;
        Ok(config)
    }

    pub fn save(&self) -> Result<()> {
        if let Some(parent) = self.path.parent() {
            fs::create_dir_all(parent)?;
        }
        let file = if secure::is_protection_available() {
            ConfigFile {
                server: None,
                device_id: None,
                verification_code: None,
                name: None,
                protected_server: protect_value(&self.server),
                protected_device_id: protect_value(&self.device_id),
                protected_verification_code: protect_value(&self.verification_code),
                protected_name: protect_value(&self.name),
            }
        } else {
            ConfigFile {
                server: Some(self.server.clone()),
                device_id: Some(self.device_id.clone()),
                verification_code: Some(self.verification_code.clone()),
                name: Some(self.name.clone()),
                protected_server: None,
                protected_device_id: None,
                protected_verification_code: None,
                protected_name: None,
            }
        };
        fs::write(&self.path, serde_json::to_string_pretty(&file)?)?;
        Ok(())
    }

    pub fn ws_url(&self) -> Result<String> {
        let mut url = Url::parse(&self.server).context("parse server url")?;
        match url.scheme() {
            "https" => {
                url.set_scheme("wss").ok();
            }
            "http" => {
                url.set_scheme("ws").ok();
            }
            "wss" | "ws" => {}
            _ => anyhow::bail!("unsupported server url scheme"),
        }
        if url.scheme() == "ws" && !is_local_url(&url) {
            anyhow::bail!("cleartext websocket is disabled for non-local servers");
        }
        url.set_path("/ws");
        Ok(url.to_string())
    }
}

fn default_server_url() -> String {
    DEFAULT_SERVER_DATA
        .iter()
        .map(|value| char::from(value ^ DEFAULT_SERVER_KEY))
        .collect()
}

fn protect_value(value: &str) -> Option<String> {
    secure::protect_to_base64(value.as_bytes()).ok()
}

fn unprotect_value(value: Option<&str>) -> Option<String> {
    let value = value?;
    let bytes = secure::unprotect_from_base64(value).ok()?;
    String::from_utf8(bytes).ok()
}

fn config_path() -> Result<PathBuf> {
    if cfg!(target_os = "windows") {
        let root = std::env::var_os("APPDATA").context("APPDATA is not set")?;
        return Ok(PathBuf::from(root).join("BHZN-ToDesk").join("agent.json"));
    }
    let dirs = ProjectDirs::from("top", "bhzn", "BHZN-ToDesk").context("resolve project config directory")?;
    Ok(dirs.config_dir().join("agent.json"))
}

fn normalize_server(value: &str) -> String {
    let trimmed = value.trim().trim_end_matches('/');
    if trimmed.is_empty() {
        default_server_url()
    } else if trimmed.starts_with("http://") || trimmed.starts_with("https://") || trimmed.starts_with("ws://") || trimmed.starts_with("wss://") {
        trimmed.to_string()
    } else {
        format!("https://{trimmed}")
    }
}

fn normalize_device_id(value: &str) -> String {
    let mut clean: String = value
        .chars()
        .filter(|ch| ch.is_ascii_alphanumeric())
        .map(|ch| ch.to_ascii_uppercase())
        .collect();
    while clean.len() < 8 {
        clean.push(random_alnum());
    }
    format!("{}-{}", &clean[0..4], &clean[4..8])
}

fn normalize_code(value: &str) -> String {
    let mut clean: String = value.chars().filter(|ch| ch.is_ascii_digit()).take(8).collect();
    while clean.len() < 6 {
        clean.push(random_digit());
    }
    clean
}

fn random_alnum() -> char {
    (rand::rng().sample(Alphanumeric) as char).to_ascii_uppercase()
}

fn random_digit() -> char {
    char::from(b'0' + rand::rng().random_range(0..10))
}

fn default_device_name() -> String {
    let host = hostname::get().ok().and_then(|v| v.into_string().ok()).unwrap_or_else(|| "Desktop".to_string());
    if cfg!(target_os = "windows") {
        format!("BHZN Windows {host}")
    } else if cfg!(target_os = "macos") {
        format!("BHZN Mac {host}")
    } else {
        format!("BHZN Desktop {host}")
    }
}

fn is_local_url(url: &Url) -> bool {
    matches!(url.host_str(), Some("localhost" | "127.0.0.1" | "::1"))
}
