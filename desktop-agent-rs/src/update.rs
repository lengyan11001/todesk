use std::{fs, path::PathBuf, process::Command, thread, time::Duration};

use anyhow::{Context, Result};
use serde::Deserialize;
use sha2::{Digest, Sha256};

use crate::{config::AgentConfig, log};

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct UpdateManifest {
    version: String,
    url: String,
    sha256: String,
}

pub fn spawn_auto_update(config: AgentConfig, current_version: &'static str) {
    thread::spawn(move || {
        thread::sleep(Duration::from_secs(20));
        match tokio::runtime::Builder::new_current_thread().enable_all().build() {
            Ok(runtime) => {
                if let Err(error) = runtime.block_on(check_and_apply(&config, current_version, false)) {
                    log::warn(format!("auto update failed: {error:#}"));
                }
            }
            Err(error) => log::warn(format!("auto update runtime failed: {error:#}")),
        }
    });
}

pub async fn check_and_apply(config: &AgentConfig, current_version: &str, verbose: bool) -> Result<()> {
    let manifest_url = format!("{}/api/releases/windows-agent", config.server.trim_end_matches('/'));
    let manifest = reqwest::get(&manifest_url).await?.error_for_status()?.json::<UpdateManifest>().await?;
    if !is_newer(&manifest.version, current_version) {
        if verbose {
            println!("BHZN ToDesk Agent is up to date: {current_version}");
        }
        return Ok(());
    }
    log::info(format!("update available current={} latest={}", current_version, manifest.version));
    let bytes = reqwest::get(&manifest.url).await?.error_for_status()?.bytes().await?;
    let actual = format!("{:x}", Sha256::digest(&bytes));
    if actual.to_ascii_lowercase() != manifest.sha256.to_ascii_lowercase() {
        anyhow::bail!("update sha256 mismatch expected={} actual={}", manifest.sha256, actual);
    }
    let current_exe = std::env::current_exe()?.canonicalize()?;
    let update_path = current_exe.with_extension("exe.update");
    fs::write(&update_path, &bytes).with_context(|| format!("write {}", update_path.display()))?;
    schedule_replace(&current_exe, &update_path)?;
    if verbose {
        println!("Update downloaded. Agent will restart.");
    }
    std::process::exit(0);
}

fn is_newer(latest: &str, current: &str) -> bool {
    version_tuple(latest) > version_tuple(current)
}

fn version_tuple(value: &str) -> Vec<u32> {
    value
        .split(|ch: char| !ch.is_ascii_digit())
        .filter(|part| !part.is_empty())
        .take(4)
        .map(|part| part.parse::<u32>().unwrap_or(0))
        .collect()
}

fn schedule_replace(current_exe: &PathBuf, update_path: &PathBuf) -> Result<()> {
    let script = current_exe.with_extension("update.cmd");
    let body = format!(
        "@echo off\r\n\
         timeout /t 2 /nobreak >nul\r\n\
         copy /y \"{}\" \"{}\" >nul\r\n\
         del \"{}\" >nul 2>nul\r\n\
         start \"\" \"{}\" --no-auto-install\r\n\
         del \"%~f0\" >nul 2>nul\r\n",
        update_path.display(),
        current_exe.display(),
        update_path.display(),
        current_exe.display()
    );
    fs::write(&script, body)?;
    Command::new("cmd").args(["/C", "start", "", &script.display().to_string()]).spawn()?;
    Ok(())
}
