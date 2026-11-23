//! One-time optional Windows shell shortcut. Launcher settings remain portable.
use anyhow::{Context, Result, bail};
use std::path::{Path, PathBuf};

// Windows Shell COM rejects verbatim filesystem prefixes produced by canonicalize().
#[cfg(windows)]
fn shell_path(path: &Path) -> PathBuf {
    use std::os::windows::ffi::{OsStrExt, OsStringExt};
    let wide: Vec<u16> = path.as_os_str().encode_wide().collect();
    let prefix: Vec<u16> = r"\\?\".encode_utf16().collect();
    if !wide.starts_with(&prefix) {
        return path.to_path_buf();
    }
    let unc: Vec<u16> = r"UNC\".encode_utf16().collect();
    let normalized = if wide[4..].starts_with(&unc) {
        let mut p = vec![92, 92];
        p.extend_from_slice(&wide[8..]);
        p
    } else {
        wide[4..].to_vec()
    };
    PathBuf::from(std::ffi::OsString::from_wide(&normalized))
}

#[cfg(windows)]
struct Apartment(bool);
#[cfg(windows)]
impl Apartment {
    fn enter() -> Result<Self> {
        use windows::Win32::System::Com::{COINIT_APARTMENTTHREADED, CoInitializeEx};
        let result = unsafe { CoInitializeEx(None, COINIT_APARTMENTTHREADED) };
        if result.0 == 0x80010106u32 as i32 {
            return Ok(Self(false));
        }
        result.ok().context("SHORTCUT_COM")?;
        Ok(Self(true))
    }
}
#[cfg(windows)]
impl Drop for Apartment {
    fn drop(&mut self) {
        if self.0 {
            unsafe { windows::Win32::System::Com::CoUninitialize() }
        }
    }
}
#[cfg(windows)]
fn wide(value: impl AsRef<std::ffi::OsStr>) -> Vec<u16> {
    use std::os::windows::ffi::OsStrExt;
    value.as_ref().encode_wide().chain(Some(0)).collect()
}

/// Optional desktop link. Uses the Unicode Shell API directly, independent of system ANSI locale.
pub fn create_launcher_shortcut(
    executable: &Path,
    root: &Path,
    destination: Option<&Path>,
) -> Result<PathBuf> {
    #[cfg(windows)]
    {
        use windows::{
            Win32::{
                System::Com::{
                    CLSCTX_INPROC_SERVER, CoCreateInstance, CoTaskMemFree, IPersistFile,
                },
                UI::Shell::{
                    FOLDERID_Desktop, IShellLinkW, KF_FLAG_DEFAULT, SHGetKnownFolderPath, ShellLink,
                },
            },
            core::{Interface, PCWSTR, w},
        };
        let _apartment = Apartment::enter()?;
        let executable = shell_path(&std::path::absolute(executable)?);
        let root = shell_path(&std::path::absolute(root)?);
        if !executable.is_file() {
            bail!("SHORTCUT_TARGET: launcher executable is missing")
        }
        let destination = match destination {
            Some(p) => shell_path(p),
            None => unsafe {
                let raw = SHGetKnownFolderPath(&FOLDERID_Desktop, KF_FLAG_DEFAULT, None)
                    .context("SHORTCUT_DESKTOP")?;
                let text = raw.to_string();
                CoTaskMemFree(Some(raw.0.cast()));
                PathBuf::from(text?).join("BSCPCRAFT.lnk")
            },
        };
        if destination.extension().and_then(|v| v.to_str()) != Some("lnk") {
            bail!("SHORTCUT_PATH: .lnk required")
        }
        let root_text = root.to_string_lossy();
        let trailing = root_text.chars().rev().take_while(|c| *c == '\\').count();
        let arguments = format!("--root \"{}{}\"", root_text, "\\".repeat(trailing));
        let executable_w = wide(executable.as_os_str());
        let arguments_w = wide(std::ffi::OsStr::new(&arguments));
        let working_w = wide(executable.parent().context("SHORTCUT_TARGET")?.as_os_str());
        let destination_w = wide(destination.as_os_str());
        unsafe {
            let link: IShellLinkW = CoCreateInstance(&ShellLink, None, CLSCTX_INPROC_SERVER)
                .context("SHORTCUT_CREATE")?;
            link.SetPath(PCWSTR(executable_w.as_ptr()))
                .context("SHORTCUT_TARGET")?;
            link.SetArguments(PCWSTR(arguments_w.as_ptr()))?;
            link.SetWorkingDirectory(PCWSTR(working_w.as_ptr()))?;
            link.SetIconLocation(PCWSTR(executable_w.as_ptr()), 0)?;
            link.SetDescription(w!("BSCPCRAFT / Builders SK"))?;
            let persist: IPersistFile = link.cast()?;
            persist
                .Save(PCWSTR(destination_w.as_ptr()), true)
                .context("SHORTCUT_SAVE")?;
        }
        if !destination.is_file() {
            bail!("SHORTCUT_CREATE: shortcut was not created")
        }
        Ok(destination)
    }
    #[cfg(not(windows))]
    {
        let _ = (executable, root, destination);
        bail!("SHORTCUT_PLATFORM: desktop shortcuts are supported on Windows")
    }
}

#[cfg(all(test, windows))]
mod tests {
    use super::*;
    #[test]
    fn shell_paths_accept_verbatim_drive_and_unc_without_losing_unicode() {
        assert_eq!(
            shell_path(Path::new(r"\\?\C:\Игрок\BSCPCRAFT.exe")),
            PathBuf::from(r"C:\Игрок\BSCPCRAFT.exe")
        );
        assert_eq!(
            shell_path(Path::new(r"\\?\UNC\server\Игрок\BSCPCRAFT.exe")),
            PathBuf::from(r"\\server\Игрок\BSCPCRAFT.exe")
        );
    }
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
        create_launcher_shortcut(
            &std::fs::canonicalize(&executable).unwrap(),
            &std::fs::canonicalize(&root).unwrap(),
            Some(&std::fs::canonicalize(temp.path()).unwrap().join("Тест.lnk")),
        )
        .unwrap();
        use windows::{
            Win32::{
                System::Com::{CLSCTX_INPROC_SERVER, CoCreateInstance, IPersistFile, STGM_READ},
                UI::Shell::{IShellLinkW, ShellLink},
            },
            core::{Interface, PCWSTR},
        };
        let _apartment = Apartment::enter().unwrap();
        let mut target = vec![0u16; 32768];
        let mut arguments = vec![0u16; 32768];
        let mut icon = vec![0u16; 32768];
        let mut working = vec![0u16; 32768];
        let mut icon_index = 0;
        unsafe {
            let shell: IShellLinkW =
                CoCreateInstance(&ShellLink, None, CLSCTX_INPROC_SERVER).unwrap();
            let persist: IPersistFile = shell.cast().unwrap();
            persist
                .Load(PCWSTR(wide(shell_path(&link)).as_ptr()), STGM_READ)
                .unwrap();
            shell.GetPath(&mut target, std::ptr::null_mut(), 0).unwrap();
            shell.GetArguments(&mut arguments).unwrap();
            shell.GetWorkingDirectory(&mut working).unwrap();
            shell.GetIconLocation(&mut icon, &mut icon_index).unwrap();
        }
        let text =
            |s: &[u16]| String::from_utf16(&s[..s.iter().position(|x| *x == 0).unwrap()]).unwrap();
        assert_eq!(Path::new(&text(&target)), shell_path(&executable));
        assert_eq!(
            text(&arguments),
            format!("--root \"{}\"", shell_path(&root).display())
        );
        assert_eq!(Path::new(&text(&icon)), shell_path(&executable));
        assert_eq!(icon_index, 0);
        assert_eq!(Path::new(&text(&working)), shell_path(&folder));
    }
}
