//! One-time optional Windows shell shortcut. Launcher settings remain portable.
use anyhow::{Context, Result, bail};
use std::path::{Path, PathBuf};

/// `destination` is supplied only by diagnostics/tests; None uses the Windows Desktop folder.
pub fn create_launcher_shortcut(
    executable: &Path,
    root: &Path,
    destination: Option<&Path>,
) -> Result<PathBuf> {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        use std::process::{Command, Stdio};
        let executable = std::path::absolute(executable)?;
        let root = std::path::absolute(root)?;
        if !executable.is_file() {
            bail!("SHORTCUT_TARGET: launcher executable is missing");
        }
        if let Some(path) = destination {
            if path.extension().and_then(|v| v.to_str()) != Some("lnk") {
                bail!("SHORTCUT_PATH: .lnk required");
            }
        }
        let script = r#"
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$link=$env:BSCP_SHORTCUT_FILE
if(-not $link){$link=Join-Path ([Environment]::GetFolderPath('Desktop')) 'BSCPCRAFT.lnk'}
$shell=New-Object -ComObject WScript.Shell
$shortcut=$shell.CreateShortcut($link)
$shortcut.TargetPath=$env:BSCP_SHORTCUT_EXE
$quotedRoot=[regex]::Replace($env:BSCP_SHORTCUT_ROOT,'(\\+)$','$1$1')
$shortcut.Arguments='--root "'+$quotedRoot+'"'
$shortcut.WorkingDirectory=[IO.Path]::GetDirectoryName($env:BSCP_SHORTCUT_EXE)
$shortcut.IconLocation=$env:BSCP_SHORTCUT_EXE+',0'
$shortcut.Description='BSCPCRAFT / Builders SK'
$shortcut.Save()
[Console]::Write($link)
"#;
        let powershell =
            PathBuf::from(std::env::var_os("SystemRoot").context("Windows directory unavailable")?)
                .join("System32/WindowsPowerShell/v1.0/powershell.exe");
        let mut child = Command::new(powershell)
            .args([
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                script,
            ])
            .env("BSCP_SHORTCUT_EXE", &executable)
            .env("BSCP_SHORTCUT_ROOT", &root)
            .env("BSCP_SHORTCUT_FILE", destination.unwrap_or(Path::new("")))
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .creation_flags(0x08000000)
            .spawn()
            .context("SHORTCUT_CREATE: Windows shell unavailable")?;
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(15);
        while child.try_wait()?.is_none() {
            if std::time::Instant::now() >= deadline {
                let _ = child.kill();
                let _ = child.wait();
                bail!("SHORTCUT_CREATE: Windows shell timed out");
            }
            std::thread::sleep(std::time::Duration::from_millis(50));
        }
        let output = child.wait_with_output()?;
        if !output.status.success() {
            bail!(
                "SHORTCUT_CREATE: {}",
                String::from_utf8_lossy(&output.stderr).trim()
            );
        }
        let path = PathBuf::from(String::from_utf8(output.stdout)?.trim());
        if !path.is_file() {
            bail!("SHORTCUT_CREATE: shortcut was not created");
        }
        Ok(path)
    }
    #[cfg(not(windows))]
    {
        let _ = (executable, root, destination);
        bail!("SHORTCUT_PLATFORM: desktop shortcuts are supported on Windows");
    }
}

#[cfg(all(test, windows))]
mod tests {
    use super::*;
    #[test]
    fn shell_link_preserves_unicode_paths_profile_and_icon() {
        let temp = tempfile::tempdir().unwrap();
        let root = temp.path().join("Профиль игрока");
        let folder = temp.path().join("Лаунчер с пробелами");
        std::fs::create_dir_all(&folder).unwrap();
        std::fs::create_dir_all(&root).unwrap();
        let executable = folder.join("BSCPCRAFT.exe");
        std::fs::copy(std::env::current_exe().unwrap(), &executable).unwrap();
        let link = temp.path().join("Тест.lnk");
        create_launcher_shortcut(&executable, &root, Some(&link)).unwrap();
        use std::os::windows::process::CommandExt;
        let output = std::process::Command::new("powershell.exe").args(["-NoProfile", "-NonInteractive", "-Command",
            "[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false);$s=(New-Object -ComObject WScript.Shell).CreateShortcut($env:BSCP_TEST_LINK);@{target=$s.TargetPath;arguments=$s.Arguments;icon=$s.IconLocation;working=$s.WorkingDirectory}|ConvertTo-Json -Compress"])
            .env("BSCP_TEST_LINK", &link).creation_flags(0x08000000).output().unwrap();
        assert!(output.status.success());
        let value: serde_json::Value = serde_json::from_slice(&output.stdout).unwrap();
        assert_eq!(Path::new(value["target"].as_str().unwrap()), executable);
        assert_eq!(value["arguments"], format!("--root \"{}\"", root.display()));
        assert!(value["icon"].as_str().unwrap().contains("BSCPCRAFT.exe"));
        assert_eq!(Path::new(value["working"].as_str().unwrap()), folder);
    }
}
