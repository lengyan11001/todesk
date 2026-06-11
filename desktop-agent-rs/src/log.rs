use std::{
    fs::{self, OpenOptions},
    io::Write,
    path::PathBuf,
    sync::OnceLock,
};

use crate::config::AgentConfig;

static LOG_PATH: OnceLock<PathBuf> = OnceLock::new();

pub fn init(config: &AgentConfig) {
    let path = config
        .path
        .parent()
        .map(|parent| parent.join("agent.log"))
        .unwrap_or_else(|| PathBuf::from("agent.log"));
    let _ = LOG_PATH.set(path);
    info(format!("agent started version={} id={} server={}", crate::AGENT_VERSION, config.device_id, config.server));
}

pub fn info(message: impl AsRef<str>) {
    write("INFO", message.as_ref());
}

pub fn warn(message: impl AsRef<str>) {
    write("WARN", message.as_ref());
}

fn write(level: &str, message: &str) {
    let Some(path) = LOG_PATH.get() else {
        return;
    };
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    if let Ok(metadata) = fs::metadata(path) {
        if metadata.len() > 2 * 1024 * 1024 {
            let rotated = path.with_extension("log.1");
            let _ = fs::remove_file(&rotated);
            let _ = fs::rename(path, rotated);
        }
    }
    if let Ok(mut file) = OpenOptions::new().create(true).append(true).open(path) {
        let _ = writeln!(file, "{} [{}] {}", timestamp(), level, message.replace('\n', " "));
    }
}

fn timestamp() -> String {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();
    now.to_string()
}
