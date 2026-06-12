use std::{
    env, fs,
    ffi::OsStr,
    path::{Path, PathBuf},
    process::{Command, Stdio},
};

use anyhow::{Context, Result};
#[cfg(windows)]
use std::os::windows::process::CommandExt;

#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x08000000;

const APP_DIR_NAME: &str = "BHZN-ToDesk";
const EXE_NAME: &str = "BHZN-ToDesk-Agent.exe";
const RUN_VALUE: &str = "BHZN ToDesk Agent";
const UPDATE_TASK: &str = "BHZN-ToDesk-Agent-Update";

pub fn is_setup_invocation(args: &[String]) -> bool {
    if args.iter().any(|item| item == "--no-auto-install" || item == "--headless" || item == "--check-update") {
        return false;
    }
    current_exe()
        .ok()
        .and_then(|path| path.file_name().map(|name| name.to_string_lossy().to_ascii_lowercase()))
        .map(|name| name.contains("setup") || name.contains("installer"))
        .unwrap_or(false)
}

pub fn install_self(start_after: bool) -> Result<()> {
    let source = current_exe()?;
    let target = installed_exe_path()?;
    if source != target {
        if let Some(parent) = target.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::copy(&source, &target).with_context(|| format!("copy {} to {}", source.display(), target.display()))?;
    }
    register_startup(&target)?;
    register_update_task(&target)?;
    if start_after {
        let _ = hidden_command(&target).arg("--no-auto-install").spawn();
    }
    println!("BHZN ToDesk Agent installed: {}", target.display());
    Ok(())
}

pub fn uninstall_self() -> Result<()> {
    unregister_startup()?;
    unregister_update_task();
    println!("BHZN ToDesk Agent startup/update entries removed.");
    Ok(())
}

pub fn installed_exe_path() -> Result<PathBuf> {
    let base = env::var_os("LOCALAPPDATA").context("LOCALAPPDATA is not set")?;
    Ok(PathBuf::from(base).join(APP_DIR_NAME).join(EXE_NAME))
}

pub fn register_startup(exe: &Path) -> Result<()> {
    let command = format!("\"{}\" --headless --no-auto-install", exe.display());
    let status = hidden_command("reg")
        .args([
            "add",
            r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run",
            "/v",
            RUN_VALUE,
            "/t",
            "REG_SZ",
            "/d",
            &command,
            "/f",
        ])
        .status()
        .context("run reg add")?;
    if !status.success() {
        anyhow::bail!("reg add failed");
    }
    Ok(())
}

pub fn unregister_startup() -> Result<()> {
    let _ = hidden_command("reg")
        .args([
            "delete",
            r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run",
            "/v",
            RUN_VALUE,
            "/f",
        ])
        .status();
    Ok(())
}

fn register_update_task(exe: &Path) -> Result<()> {
    let task_command = format!("\"{}\" --check-update --headless --no-auto-install", exe.display());
    let status = hidden_command("schtasks")
        .args([
            "/Create",
            "/TN",
            UPDATE_TASK,
            "/SC",
            "HOURLY",
            "/MO",
            "4",
            "/TR",
            &task_command,
            "/F",
        ])
        .status()
        .context("run schtasks create")?;
    if !status.success() {
        anyhow::bail!("schtasks create failed");
    }
    Ok(())
}

fn unregister_update_task() {
    let _ = hidden_command("schtasks").args(["/Delete", "/TN", UPDATE_TASK, "/F"]).status();
}

pub fn current_exe() -> Result<PathBuf> {
    Ok(env::current_exe()?.canonicalize()?)
}

fn hidden_command<S: AsRef<OsStr>>(program: S) -> Command {
    let mut command = Command::new(program);
    command.stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null());
    #[cfg(windows)]
    {
        command.creation_flags(CREATE_NO_WINDOW);
    }
    command
}
