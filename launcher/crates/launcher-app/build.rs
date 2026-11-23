use std::{env, fs, path::PathBuf};

// Embed the supplied multi-size ICO as Win32 RT_ICON + RT_GROUP_ICON resources.
// https://learn.microsoft.com/windows/win32/menurc/resource-file-formats
fn resource(out: &mut Vec<u8>, kind: u16, id: u16, data: &[u8]) {
    out.extend_from_slice(&(data.len() as u32).to_le_bytes());
    out.extend_from_slice(&32u32.to_le_bytes());
    for word in [0xffff, kind, 0xffff, id] {
        out.extend_from_slice(&word.to_le_bytes());
    }
    out.extend_from_slice(&0u32.to_le_bytes());
    out.extend_from_slice(&0x1010u16.to_le_bytes());
    out.extend_from_slice(&0u16.to_le_bytes());
    out.extend_from_slice(&0u32.to_le_bytes());
    out.extend_from_slice(&0u32.to_le_bytes());
    out.extend_from_slice(data);
    while out.len() % 4 != 0 {
        out.push(0);
    }
}
fn main() {
    slint_build::compile("ui/main.slint").expect("Slint design must compile");
    println!("cargo:rerun-if-changed=ui/assets/launcher.ico");
    if env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("windows")
        || env::var("CARGO_CFG_TARGET_ENV").as_deref() != Ok("msvc")
    {
        return;
    }
    let ico = fs::read("ui/assets/launcher.ico").expect("launcher icon");
    assert_eq!(&ico[..4], &[0, 0, 1, 0]);
    let count = u16::from_le_bytes(ico[4..6].try_into().unwrap());
    let mut resources = Vec::new();
    resource(&mut resources, 0, 0, &[]);
    let mut group = ico[..6].to_vec();
    for index in 0..count as usize {
        let entry = &ico[6 + index * 16..6 + (index + 1) * 16];
        let size = u32::from_le_bytes(entry[8..12].try_into().unwrap()) as usize;
        let offset = u32::from_le_bytes(entry[12..16].try_into().unwrap()) as usize;
        let id = index as u16 + 1;
        resource(&mut resources, 3, id, &ico[offset..offset + size]);
        group.extend_from_slice(&entry[..12]);
        group.extend_from_slice(&id.to_le_bytes());
    }
    resource(&mut resources, 14, 1, &group);
    let output = PathBuf::from(env::var_os("OUT_DIR").unwrap()).join("launcher-icon.res");
    fs::write(&output, resources).expect("Windows icon resource");
    println!("cargo:rustc-link-arg-bin=BSCPCRAFT={}", output.display());
}
