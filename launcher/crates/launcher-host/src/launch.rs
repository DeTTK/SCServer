use anyhow::{Result, bail};
use bscp_launcher_core::{GameLayout, Settings, component_base, component_file};
use std::{
    collections::BTreeMap,
    path::{Path, PathBuf},
};
pub fn prop(s: impl AsRef<str>) -> String {
    let mut out = String::new();
    for c in s.as_ref().replace('\\', "/").chars() {
        if c as u32 > 127 {
            for x in c.encode_utf16(&mut [0; 2]) {
                out.push_str(&format!("\\u{x:04x}"))
            }
        } else {
            if ":=#!".contains(c) {
                out.push('\\')
            }
            out.push(c)
        }
    }
    out
}
pub fn path(p: &Path) -> String {
    p.to_string_lossy().into_owned()
}
pub fn need(p: &Path) -> Result<()> {
    if !p.exists() {
        bail!("BUNDLE_INCOMPLETE: {}", p.display())
    }
    Ok(())
}
pub fn java(root: &Path) -> Result<PathBuf> {
    need(&root.join("bin/java.exe"))?;
    need(&root.join("jmods/jdk.jfr.jmod")).or_else(|_| {
        let mut c = std::process::Command::new(root.join("bin/java.exe"));
        c.arg("--list-modules");
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            c.creation_flags(0x08000000);
        }
        let r = c.output()?;
        if !r.status.success() || !String::from_utf8_lossy(&r.stdout).contains("jdk.jfr@") {
            bail!("JAVA_JFR: Java 17 with JFR is required")
        }
        Ok(())
    })?;
    Ok(root.join("bin/java.exe"))
}
pub fn base(java: &Path, session: &Path) -> Result<tokio::process::Command> {
    std::fs::create_dir_all(session.join("temp"))?;
    let mut c = tokio::process::Command::new(java);
    c.current_dir(session)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .env("TEMP", session.join("temp"))
        .env("TMP", session.join("temp"));
    #[cfg(windows)]
    {
        c.creation_flags(0x08000000);
    }
    c.arg(format!("-Djava.io.tmpdir={}", path(&session.join("temp"))))
        .arg(format!(
            "-XX:ErrorFile={}",
            path(&session.join("hs_err_pid%p.log"))
        ))
        .arg("-Dfile.encoding=UTF-8");
    Ok(c)
}
pub fn server_config(source: &Path, map: &Path, data: &Path, s: &Settings) -> Result<String> {
    let template = std::fs::read_to_string(component_file(source, "server.template.properties")?)?;
    let automatic = crate::network::local_addresses()
        .into_iter()
        .next()
        .unwrap_or_else(|| "127.0.0.1".into());
    let host = if s.advertised_host.trim().is_empty() {
        &automatic
    } else {
        s.advertised_host.trim()
    };
    if host
        .chars()
        .any(|c| c.is_whitespace() || "/=,#\\".contains(c))
    {
        bail!("HOST_INVALID")
    }
    let mut text = template;
    for (k, v) in [
        ("@ROOT@", prop(path(&component_base(source)?))),
        ("@MAP@", prop(path(map))),
        ("@HOST@", prop(host)),
        ("@HTTP@", s.base_port.to_string()),
        ("@GAME@", (s.base_port + 1).to_string()),
        ("@WORLD@", (s.base_port + 2).to_string()),
        ("@ADMIN@", (s.base_port + 100).to_string()),
        ("@DEV@", (s.base_port + 104).to_string()),
    ] {
        text = text.replace(k, &v)
    }
    let mut values = BTreeMap::<String, String>::new();
    for l in text.lines() {
        let t = l.trim();
        if !t.starts_with('#') {
            if let Some((k, v)) = t.split_once('=') {
                values.insert(k.into(), v.into());
            }
        }
    }
    for (k, v) in [
        ("data.directory", prop(path(data))),
        (
            "client.runtime",
            prop(path(&component_file(source, "runtime")?)),
        ),
        ("world.regions", prop(path(map))),
        ("http.host", "127.0.0.1".into()),
        ("game.bindAddress", "0.0.0.0".into()),
        ("development.enabled", "false".into()),
        ("launcher.enabled", "false".into()),
        ("game.playerPermission", "stalker".into()),
        ("game.administratorCharacters", "".into()),
    ] {
        values.insert(k.into(), v);
    }
    Ok(values
        .into_iter()
        .map(|(k, v)| format!("{k}={v}\n"))
        .collect())
}
pub fn server(
    source: &Path,
    map: &Path,
    data: &Path,
    session: &Path,
    s: &Settings,
    agent: &Path,
    java: &Path,
) -> Result<tokio::process::Command> {
    for p in [
        component_file(source, "server.jar")?,
        component_file(source, "lib/gson-2.13.2.jar")?,
        component_file(source, "runtime/dependencies/zstd-jni-1.5.0-5.jar")?,
        agent.to_owned(),
    ] {
        need(&p)?
    }
    std::fs::create_dir_all(data)?;
    std::fs::write(
        session.join("server.properties"),
        server_config(source, map, data, s)?,
    )?;
    let mut c = base(java, session)?;
    let cp = [
        "server.jar",
        "lib/gson-2.13.2.jar",
        "runtime/dependencies/zstd-jni-1.5.0-5.jar",
    ]
    .into_iter()
    .map(|p| component_file(source, p).map(|p| path(&p)))
    .collect::<Result<Vec<_>>>()?
    .join(";");
    c.args(["-Djava.awt.headless=true", "-Xms256m"])
        .arg(format!("-Xmx{}m", s.server_memory_mb))
        .arg("--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED")
        .arg(format!(
            "-javaagent:{}={}",
            path(agent),
            path(&session.join("agent.properties"))
        ))
        .args(["-cp", &cp, "szserver.ServerMain"])
        .arg(session.join("server.properties"));
    Ok(c)
}
pub fn client(
    source: &Path,
    g: &GameLayout,
    session: &Path,
    profile: &Path,
    proxy: &str,
    nickname: &str,
    login: &str,
    s: &Settings,
    agent: &Path,
    java: &Path,
    qa: Option<&Path>,
) -> Result<tokio::process::Command> {
    for p in [
        "runtime/bootstrap/StandardJvmBootstrap.class",
        "runtime/classes",
        "runtime/dependencies",
        "runtime/libs",
        "runtime/natives",
        "compat/game-resource-compat.jar",
        "compat/startup-guard.jar",
        "compat/weapon-events.jar",
    ] {
        need(&component_file(source, p)?)?
    }
    std::fs::create_dir_all(profile.join("game"))?;
    let mut c = base(java, session)?;
    c.current_dir(profile.join("game"));
    if let Some(guard) = qa {
        need(guard)?;
        std::fs::write(
            session.join("launcher-next-qa.marker"),
            "isolated launcher integration",
        )?;
        c.args([
            "-Dstandard.jvm.headless.control=true",
            "-Dstandard.jvm.headless.hideWindow=true",
            "-Dstandard.jvm.headless.port=41780",
            "-Dbscp.qa.guard.port=41781",
            "-Dbscp.qa.passiveObserver=true",
        ])
        .arg(format!("-javaagent:{}={}", path(guard), path(session)));
    }
    let natives = component_file(source, "runtime/natives")?;
    let native_search = [
        natives.clone(),
        g.native_bin.clone().unwrap_or(natives.clone()),
        java.parent().unwrap().to_owned(),
        java.parent().unwrap().join("server"),
    ]
    .iter()
    .map(|p| path(p))
    .collect::<Vec<_>>()
    .join(";");
    c.arg(format!("-Xmx{}m", s.client_memory_mb))
        .arg("-XX:+UseG1GC")
        .args([
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.net=ALL-UNNAMED",
            "--add-opens=java.base/java.time=ALL-UNNAMED",
            "--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED",
        ]);
    for (k, v) in [
        ("sz.mods.dir", path(&profile.join("mods"))),
        ("java.library.path", native_search.clone()),
        ("org.lwjgl.librarypath", path(&natives)),
        ("stalcraft.native.dir", path(&natives)),
        (
            "stalcraft.effekseer.path",
            path(&natives.join("stalcraft-effekseer-jni64.dll")),
        ),
        ("imgui.library.path", path(&natives)),
        (
            "ZstdNativePath",
            path(&natives.join("libzstd-jni-1.5.0-5.dll")),
        ),
        (
            "bundle.backend_http_endpoint",
            proxy.trim_end_matches('/').into(),
        ),
        ("mod_assets_dir", path(&g.mod_assets)),
    ] {
        c.arg(format!("-D{k}={v}"));
    }
    if let Some(p) = &g.patch_assets {
        c.arg(format!("-Dpatch_assets_dir={}", path(p)));
    }
    for flag in [
        "show_globally_enabled=false",
        "read_derived=true",
        "CustomNpcsSoundCache=true",
        "load_dumped_event_classes=true",
        "usePrestitchedAtlas=true",
        "disable_item_atlas=true",
        "disable_mod_parsing=false",
        "use_system_class_loader=true",
    ] {
        c.arg(format!("-D{flag}"));
    }
    for p in [
        "game-resource-compat.jar",
        "weapon-events.jar",
        "startup-guard.jar",
        "character-scale.jar",
    ] {
        let p = component_file(source, &format!("compat/{p}"))?;
        if p.exists() {
            c.arg(format!("-javaagent:{}", path(&p)));
        }
    }
    c.arg(format!(
        "-javaagent:{}={}",
        path(agent),
        path(&session.join("agent.properties"))
    ));
    let mut cp = vec![
        component_file(source, "runtime/bootstrap")?,
        component_file(source, "runtime/classes")?,
        component_file(source, "runtime/imgui-compat")?,
    ];
    // Changed class directories precede their unchanged foundation directories.
    let foundation = component_base(source)?;
    if foundation != source {
        for relative in [
            "runtime/bootstrap",
            "runtime/classes",
            "runtime/imgui-compat",
        ] {
            let p = foundation.join(relative);
            if p.is_dir() && !cp.contains(&p) {
                cp.push(p);
            }
        }
    }
    for e in std::fs::read_dir(component_file(source, "runtime/dependencies")?)? {
        let p = e?.path();
        if p.extension().is_some_and(|x| x == "jar")
            && !p
                .file_name()
                .unwrap()
                .to_string_lossy()
                .starts_with("imgui-java-binding-")
        {
            cp.push(p)
        }
    }
    cp.push(component_file(source, "runtime/libs")?);
    cp.push(g.mod_assets.clone());
    if let Some(p) = &g.patch_assets {
        cp.push(p.clone())
    }
    c.args([
        "-cp",
        &cp.iter().map(|p| path(p)).collect::<Vec<_>>().join(";"),
        "StandardJvmBootstrap",
        "--username",
        login,
        "--session",
        "launcher-managed",
        "--gameDir",
        &path(&profile.join("game")),
        "--assetsDir",
        &path(&g.assets),
        "--dlogin",
        nickname,
    ]);
    c.env(
        "PATH",
        format!(
            "{native_search};{}",
            std::env::var("PATH").unwrap_or_default()
        ),
    );
    Ok(c)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn properties_escape_utf16_and_windows_paths() {
        assert_eq!(prop("X:\\Тест=a"), "X\\:/\\u0422\\u0435\\u0441\\u0442\\=a");
    }
    #[test]
    fn config_forces_managed_data_and_restricted_http() {
        let root = std::env::temp_dir().join(format!("bscp-config-test-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&root).unwrap();
        std::fs::write(root.join("server.template.properties"),"http.host=0.0.0.0\ngame.playerPermission=admin\nhttp.port=@HTTP@\ndata.directory=relative\n").unwrap();
        let config = server_config(
            &root,
            &root.join("map"),
            &root.join("managed"),
            &Settings::default(),
        )
        .unwrap();
        assert!(config.contains("http.host=127.0.0.1\n"));
        assert!(config.contains("game.playerPermission=stalker\n"));
        assert!(config.contains("http.port=29545\n"));
        assert!(config.contains("development.enabled=false\n"));
        assert!(config.contains(&format!(
            "data.directory={}\n",
            prop(path(&root.join("managed")))
        )));
        std::fs::remove_dir_all(root).unwrap();
    }
}
