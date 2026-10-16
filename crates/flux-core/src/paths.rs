//! Hostile-input-safe handling of file names received from another device.
//!
//! Policy: *structural* attacks (empty names, `.`/`..`, absolute paths) are rejected outright;
//! *cosmetic* problems (characters that are illegal on Windows, reserved device names,
//! trailing dots/spaces) are repaired so legitimate phone files still arrive.

use crate::error::{FluxError, Result};
use std::fs::{self, OpenOptions};
use std::io;
use std::path::{Path, PathBuf};

pub const MAX_COMPONENT_BYTES: usize = 200;
pub const MAX_DEPTH: usize = 32;
pub const MAX_TOTAL_BYTES: usize = 1024;

const RESERVED: &[&str] = &[
    "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8",
    "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
];

/// A validated, normalised relative path made of safe components.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SafeRelPath {
    components: Vec<String>,
}

impl SafeRelPath {
    /// Parse a '/'-separated relative path supplied by an untrusted peer.
    pub fn parse(raw: &str) -> Result<Self> {
        if raw.is_empty() || raw.len() > MAX_TOTAL_BYTES {
            return Err(FluxError::InvalidPath("empty or too long".into()));
        }
        let mut components = Vec::new();
        for part in raw.split('/') {
            let trimmed = part.trim();
            // Structural attacks: reject the whole request.
            if trimmed.is_empty() || trimmed == "." || trimmed == ".." {
                return Err(FluxError::InvalidPath(
                    "empty, '.' or '..' component, or absolute path".into(),
                ));
            }
            components.push(sanitize_component(part)?);
            if components.len() > MAX_DEPTH {
                return Err(FluxError::InvalidPath("path too deep".into()));
            }
        }
        Ok(Self { components })
    }

    pub fn components(&self) -> &[String] {
        &self.components
    }
    pub fn file_name(&self) -> &str {
        self.components.last().expect("non-empty by construction")
    }
    pub fn display(&self) -> String {
        self.components.join("/")
    }
}

fn sanitize_component(part: &str) -> Result<String> {
    let mut out: String = part
        .chars()
        .map(|c| match c {
            '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|' | '/' => '_',
            c if c.is_control() => '_',
            c => c,
        })
        .collect();
    // Windows silently strips trailing dots/spaces, which could re-create "." / ".." or collide.
    while out.ends_with('.') || out.ends_with(' ') {
        out.pop();
    }
    let out = out.trim_start().to_string();
    if out.is_empty() {
        return Err(FluxError::InvalidPath("component empty after sanitising".into()));
    }
    // Reserved device names are reserved even with an extension ("con.txt").
    let stem = out.split('.').next().unwrap_or("").to_ascii_uppercase();
    let out = if RESERVED.contains(&stem.as_str()) { format!("_{out}") } else { out };
    if out.len() > MAX_COMPONENT_BYTES {
        return Err(FluxError::InvalidPath("file name too long".into()));
    }
    Ok(out)
}


/// Create (or validate) every directory component under `root` without following symlinks.
fn ensure_dirs(root: &Path, dirs: &[String]) -> Result<PathBuf> {
    let mut cur = root.to_path_buf();
    for comp in dirs {
        cur.push(comp);
        match fs::symlink_metadata(&cur) {
            Ok(m) if m.file_type().is_symlink() => {
                return Err(FluxError::InvalidPath("destination contains a symlink".into()))
            }
            Ok(m) if m.is_dir() => {}
            Ok(_) => {
                return Err(FluxError::InvalidPath(
                    "a file with the same name as a folder already exists".into(),
                ))
            }
            Err(e) if e.kind() == io::ErrorKind::NotFound => match fs::create_dir(&cur) {
                Ok(()) => {}
                Err(e) if e.kind() == io::ErrorKind::AlreadyExists => {}
                Err(e) => return Err(e.into()),
            },
            Err(e) => return Err(e.into()),
        }
        // Belt and braces (junctions, mount tricks): resolved path must stay inside root.
        let real = fs::canonicalize(&cur)?;
        if !real.starts_with(root) {
            return Err(FluxError::InvalidPath("path escapes the receive folder".into()));
        }
    }
    Ok(cur)
}

fn split_name(name: &str) -> (&str, &str) {
    match name.rfind('.') {
        Some(i) if i > 0 => (&name[..i], &name[i..]),
        _ => (name, ""),
    }
}

/// Reserve a final destination: an empty placeholder is created with O_EXCL (so a pre-existing
/// file can never be overwritten; duplicates become "name (1).ext"), and the caller atomically
/// renames the verified data over it. `root` must already be canonical (see `canonical_root`).
pub fn reserve_destination(root: &Path, rel: &SafeRelPath) -> Result<PathBuf> {
    let comps = rel.components();
    let parent = ensure_dirs(root, &comps[..comps.len() - 1])?;
    let name = rel.file_name();
    let (stem, ext) = split_name(name);
    for n in 0..10_000u32 {
        let candidate =
            if n == 0 { name.to_string() } else { format!("{stem} ({n}){ext}") };
        let final_path = parent.join(&candidate);
        match OpenOptions::new().write(true).create_new(true).open(&final_path) {
            Ok(_) => return Ok(final_path),
            Err(e) if e.kind() == io::ErrorKind::AlreadyExists => continue,
            Err(e) => return Err(e.into()),
        }
    }
    Err(FluxError::Limit("too many files with the same name".into()))
}

/// Create the receive folder if needed and return its canonical form.
pub fn canonical_root(dir: &Path) -> Result<PathBuf> {
    fs::create_dir_all(dir)?;
    Ok(fs::canonicalize(dir)?)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_traversal_and_absolute() {
        for bad in ["../x", "a/../../x", "/etc/passwd", "", ".", "a//b", "a/./b", "..", "a/.. /b"] {
            assert!(SafeRelPath::parse(bad).is_err(), "should reject {bad:?}");
        }
    }

    #[test]
    fn neutralises_windows_tricks() {
        let p = SafeRelPath::parse("C:\\Windows\\System32\\evil.dll").unwrap();
        assert_eq!(p.components().len(), 1);
        assert!(!p.file_name().contains('\\') && !p.file_name().contains(':'));
        assert_eq!(SafeRelPath::parse("CON.txt").unwrap().file_name(), "_CON.txt");
        assert_eq!(SafeRelPath::parse("nul").unwrap().file_name(), "_nul");
        assert_eq!(SafeRelPath::parse("a<b>c?.jpg").unwrap().file_name(), "a_b_c_.jpg");
        assert_eq!(SafeRelPath::parse("report. . .").unwrap().file_name(), "report");
        assert!(SafeRelPath::parse("...").is_err());
        assert!(SafeRelPath::parse("file\0name").unwrap().file_name().contains('_'));
        assert!(SafeRelPath::parse("stream.txt:hidden").unwrap().file_name().contains('_'));
    }

    #[test]
    fn limits_enforced() {
        assert!(SafeRelPath::parse(&"a".repeat(300)).is_err());
        assert!(SafeRelPath::parse(&vec!["d"; 40].join("/")).is_err());
        assert!(SafeRelPath::parse(&"a/".repeat(600)).is_err());
    }

    #[test]
    fn keeps_unicode_and_folders() {
        let p = SafeRelPath::parse("Holiday 📸/IMG_001 – café.jpg").unwrap();
        assert_eq!(p.display(), "Holiday 📸/IMG_001 – café.jpg");
    }

    #[test]
    fn duplicate_names_never_overwrite() {
        let d = tempfile::tempdir().unwrap();
        let root = canonical_root(d.path()).unwrap();
        fs::write(root.join("a.txt"), b"original").unwrap();
        let rel = SafeRelPath::parse("a.txt").unwrap();
        let r1 = reserve_destination(&root, &rel).unwrap();
        let r2 = reserve_destination(&root, &rel).unwrap();
        assert_eq!(r1.file_name().unwrap(), "a (1).txt");
        assert_eq!(r2.file_name().unwrap(), "a (2).txt");
        assert_eq!(fs::read(root.join("a.txt")).unwrap(), b"original");
    }

    #[cfg(unix)]
    #[test]
    fn refuses_symlinked_directories() {
        let d = tempfile::tempdir().unwrap();
        let outside = tempfile::tempdir().unwrap();
        let root = canonical_root(d.path()).unwrap();
        std::os::unix::fs::symlink(outside.path(), root.join("link")).unwrap();
        let rel = SafeRelPath::parse("link/pwn.txt").unwrap();
        assert!(reserve_destination(&root, &rel).is_err());
        assert!(!outside.path().join("pwn.txt").exists());
    }
}
