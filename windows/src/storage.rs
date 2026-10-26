use crate::{domain::Preferences, platform::wide, strings};
use serde::{Deserialize, Serialize};
use std::{
    fs::{self, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
    ptr,
};
use windows_sys::Win32::{
    Foundation::LocalFree, Security::Cryptography::*, Storage::FileSystem::*,
};
use zeroize::{Zeroize, Zeroizing};

#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Account {
    username: String,
    protected_password: Vec<u8>,
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Settings {
    version: u32,
    accounts: Vec<Account>,
    pub selected: Option<String>,
    pub preferences: Preferences,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            version: 1,
            accounts: Vec::new(),
            selected: None,
            preferences: Preferences::default(),
        }
    }
}

impl Settings {
    pub fn usernames(&self) -> Vec<String> {
        self.accounts.iter().map(|a| a.username.clone()).collect()
    }

    pub fn password(&self, username: &str) -> Result<Zeroizing<String>, String> {
        let account = self
            .accounts
            .iter()
            .find(|a| a.username == username)
            .ok_or(strings::NO_ACCOUNT)?;
        let clear = protect(&account.protected_password, false)?;
        let text = std::str::from_utf8(&clear).map_err(|_| strings::DECRYPT_FAILED)?;
        Ok(Zeroizing::new(text.to_owned()))
    }

    pub fn upsert(&mut self, username: String, password: &str) -> Result<(), String> {
        if username.is_empty() || username.contains('\0') || password.contains('\0') {
            return Err(strings::INVALID_ACCOUNT.into());
        }
        let existing = self.accounts.iter().position(|a| a.username == username);
        if password.is_empty() {
            if existing.is_none() {
                return Err(strings::INVALID_ACCOUNT.into());
            }
            // Do not report success for an unreadable saved credential.
            self.password(&username)?;
        } else {
            let account = Account {
                username: username.clone(),
                protected_password: protect(password.as_bytes(), true)?.to_vec(),
            };
            if let Some(index) = existing {
                self.accounts[index] = account;
            } else {
                self.accounts.push(account);
            }
        }
        self.selected = Some(username);
        Ok(())
    }

    pub fn select(&mut self, username: String) -> Result<(), String> {
        if !self.accounts.iter().any(|a| a.username == username) {
            return Err(strings::NO_ACCOUNT.into());
        }
        self.selected = Some(username);
        Ok(())
    }

    pub fn delete(&mut self, username: &str) {
        self.accounts.retain(|a| a.username != username);
        if self.selected.as_deref() == Some(username) {
            self.selected = self.accounts.first().map(|a| a.username.clone());
        }
    }

    fn validate(&self) -> bool {
        self.version == 1
            && self.accounts.len() <= 100
            && self.accounts.iter().enumerate().all(|(i, a)| {
                !a.username.is_empty()
                    && !a.protected_password.is_empty()
                    && !self.accounts[..i]
                        .iter()
                        .any(|other| a.username == other.username)
            })
            && self
                .selected
                .as_ref()
                .is_none_or(|name| self.accounts.iter().any(|a| &a.username == name))
    }
}

pub struct Store {
    path: PathBuf,
}

impl Store {
    pub fn open() -> Result<(Self, Settings), String> {
        let root = std::env::var_os("LOCALAPPDATA").ok_or(strings::APP_DATA_FAILED)?;
        Self::at(Path::new(&root).join("Alogin").join("settings.json"))
    }

    pub(crate) fn at(path: PathBuf) -> Result<(Self, Settings), String> {
        let settings = match fs::read(&path) {
            Ok(bytes) => {
                if bytes.len() > 1024 * 1024 {
                    return Err(strings::BAD_FILE.into());
                }
                let settings: Settings =
                    serde_json::from_slice(&bytes).map_err(|_| strings::BAD_FILE)?;
                if !settings.validate() {
                    return Err(strings::BAD_FILE.into());
                }
                settings
            }
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Settings::default(),
            Err(_) => return Err(strings::READ_FAILED.into()),
        };
        Ok((Self { path }, settings))
    }

    // Caller commits its in-memory candidate only after durable replacement succeeds.
    pub fn save(&self, settings: &Settings) -> Result<(), String> {
        if !settings.validate() {
            return Err(strings::BAD_FILE.into());
        }
        let parent = self.path.parent().ok_or(strings::STORAGE_FAILED)?;
        fs::create_dir_all(parent).map_err(|_| strings::STORAGE_FAILED)?;
        let bytes = serde_json::to_vec_pretty(settings).map_err(|_| strings::STORAGE_FAILED)?;
        let temp = self.path.with_extension("json.tmp");
        let result = (|| {
            let mut file = OpenOptions::new()
                .create(true)
                .truncate(true)
                .write(true)
                .open(&temp)?;
            file.write_all(&bytes)?;
            file.sync_all()?;
            drop(file);
            // Same-directory rename; write-through and replacement preserve the old file on failure.
            let ok = unsafe {
                MoveFileExW(
                    wide(&temp.to_string_lossy()).as_ptr(),
                    wide(&self.path.to_string_lossy()).as_ptr(),
                    MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
                )
            };
            if ok == 0 {
                return Err(std::io::Error::last_os_error());
            }
            Ok::<(), std::io::Error>(())
        })();
        if result.is_err() {
            let _ = fs::remove_file(&temp);
        }
        result.map_err(|_| strings::STORAGE_FAILED.to_owned())
    }
}

fn protect(bytes: &[u8], encrypt: bool) -> Result<Zeroizing<Vec<u8>>, String> {
    let input = CRYPT_INTEGER_BLOB {
        cbData: bytes
            .len()
            .try_into()
            .map_err(|_| strings::ENCRYPT_FAILED)?,
        pbData: bytes.as_ptr().cast_mut(),
    };
    let mut output: CRYPT_INTEGER_BLOB = unsafe { std::mem::zeroed() };
    let ok = unsafe {
        if encrypt {
            CryptProtectData(
                &input,
                ptr::null(),
                ptr::null(),
                ptr::null(),
                ptr::null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
        } else {
            CryptUnprotectData(
                &input,
                ptr::null_mut(),
                ptr::null(),
                ptr::null(),
                ptr::null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
        }
    };
    if ok == 0 {
        return Err(if encrypt {
            strings::ENCRYPT_FAILED
        } else {
            strings::DECRYPT_FAILED
        }
        .into());
    }
    let result = unsafe {
        let buffer = std::slice::from_raw_parts_mut(output.pbData, output.cbData as usize);
        let result = Zeroizing::new(buffer.to_vec());
        buffer.zeroize();
        LocalFree(output.pbData.cast());
        result
    };
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn temp_path() -> PathBuf {
        std::env::temp_dir()
            .join(format!(
                "alogin-test-{}-{}",
                std::process::id(),
                std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap()
                    .as_nanos()
            ))
            .join("settings.json")
    }

    #[test]
    fn credentials_roundtrip_encrypted_and_selected_account_survives_restart() {
        let path = temp_path();
        let (store, mut settings) = Store::at(path.clone()).unwrap();
        settings.upsert("first".into(), "secret-&-中文").unwrap();
        settings.upsert("second".into(), "other").unwrap();
        settings.select("first".into()).unwrap();
        store.save(&settings).unwrap();
        assert!(!fs::read_to_string(&path).unwrap().contains("secret-&-中文"));
        let (_, restored) = Store::at(path.clone()).unwrap();
        assert_eq!(
            restored.password("first").unwrap().as_str(),
            "secret-&-中文"
        );
        assert_eq!(restored.selected.as_deref(), Some("first"));
        fs::remove_file(path).unwrap();
    }

    #[test]
    fn malformed_settings_are_not_overwritten() {
        let path = temp_path();
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(&path, "damaged").unwrap();
        assert!(Store::at(path.clone()).is_err());
        assert_eq!(fs::read_to_string(&path).unwrap(), "damaged");
        fs::remove_file(path).unwrap();
    }

    #[test]
    fn legacy_settings_default_to_system_and_appearance_survives_restart() {
        let path = temp_path();
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(&path, r#"{"version":1,"accounts":[],"selected":null,"preferences":{"target_ssids":["custom"],"auto_login":true}}"#).unwrap();
        let (store, mut settings) = Store::at(path.clone()).unwrap();
        assert_eq!(
            settings.preferences.appearance,
            crate::domain::Appearance::System
        );
        settings.preferences.appearance = crate::domain::Appearance::Dark;
        store.save(&settings).unwrap();
        let (_, restored) = Store::at(path.clone()).unwrap();
        assert_eq!(
            restored.preferences.appearance,
            crate::domain::Appearance::Dark
        );
        assert!(restored.preferences.auto_login);
        assert_eq!(restored.preferences.target_ssids, ["custom"]);
        fs::remove_file(path).unwrap();
    }

    #[test]
    fn missing_password_requires_an_existing_decryptable_account() {
        let mut settings = Settings::default();
        assert!(settings.upsert("first".into(), "").is_err());
        settings.upsert("first".into(), "secret").unwrap();
        settings.upsert("first".into(), "").unwrap();
        assert_eq!(settings.password("first").unwrap().as_str(), "secret");
        settings.delete("first");
        assert!(settings.selected.is_none());
    }
}
