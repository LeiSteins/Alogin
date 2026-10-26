//! Current-user logon registration. The Run value is the sole source of truth;
//! reading it never creates or repairs a registration without a user action.
use super::wide;
use crate::strings;
use std::{os::windows::ffi::OsStrExt, path::Path, ptr};
use windows_sys::Win32::{Foundation::*, System::Registry::*};

const RUN_KEY: &str = r"Software\Microsoft\Windows\CurrentVersion\Run";
const VALUE_NAME: &str = "Alogin";

struct Key(HKEY);
impl Drop for Key {
    fn drop(&mut self) {
        unsafe { RegCloseKey(self.0) };
    }
}

fn command(executable: &Path) -> Result<Vec<u16>, String> {
    let path: Vec<u16> = executable.as_os_str().encode_wide().collect();
    if !executable.is_absolute() || path.iter().any(|c| *c == 0 || *c == b'"' as u16) {
        return Err(strings::STARTUP_PATH_FAILED.into());
    }
    let mut command = vec![b'"' as u16];
    command.extend(path);
    command.extend(wide("\" --background"));
    // Run commands are limited to 260 characters, excluding the terminator.
    if command.len() > 261 {
        return Err(strings::STARTUP_PATH_FAILED.into());
    }
    Ok(command)
}

fn read(key: &str) -> Result<Option<Vec<u16>>, String> {
    let mut value = vec![0u16; 261];
    let mut size = (value.len() * 2) as u32;
    let code = unsafe {
        RegGetValueW(
            HKEY_CURRENT_USER,
            wide(key).as_ptr(),
            wide(VALUE_NAME).as_ptr(),
            RRF_RT_REG_SZ,
            ptr::null_mut(),
            value.as_mut_ptr().cast(),
            &mut size,
        )
    };
    match code {
        ERROR_FILE_NOT_FOUND | ERROR_PATH_NOT_FOUND => Ok(None),
        ERROR_SUCCESS => {
            value.truncate(size as usize / 2);
            Ok(Some(value))
        }
        _ => Err(strings::STARTUP_READ_FAILED.into()),
    }
}

pub fn enabled() -> Result<bool, String> {
    let value = read(RUN_KEY)?;
    let executable = std::env::current_exe().map_err(|_| strings::STARTUP_PATH_FAILED)?;
    Ok(value.as_ref() == Some(&command(&executable)?))
}

pub fn set_enabled(enabled: bool) -> Result<(), String> {
    // Disabling must work even when the current path is too long for Run.
    let value = if enabled {
        let executable = std::env::current_exe().map_err(|_| strings::STARTUP_PATH_FAILED)?;
        Some(command(&executable)?)
    } else {
        None
    };
    write(RUN_KEY, value.as_deref())
}

fn write(key: &str, value: Option<&[u16]>) -> Result<(), String> {
    let mut handle = ptr::null_mut();
    let code = unsafe {
        if value.is_some() {
            RegCreateKeyExW(
                HKEY_CURRENT_USER,
                wide(key).as_ptr(),
                0,
                ptr::null(),
                REG_OPTION_NON_VOLATILE,
                KEY_SET_VALUE,
                ptr::null(),
                &mut handle,
                ptr::null_mut(),
            )
        } else {
            RegOpenKeyExW(
                HKEY_CURRENT_USER,
                wide(key).as_ptr(),
                0,
                KEY_SET_VALUE,
                &mut handle,
            )
        }
    };
    if value.is_none() && matches!(code, ERROR_FILE_NOT_FOUND | ERROR_PATH_NOT_FOUND) {
        return Ok(());
    }
    if code != ERROR_SUCCESS {
        return Err(strings::STARTUP_WRITE_FAILED.into());
    }
    let handle = Key(handle);
    let code = unsafe {
        match value {
            Some(value) => RegSetValueExW(
                handle.0,
                wide(VALUE_NAME).as_ptr(),
                0,
                REG_SZ,
                value.as_ptr().cast(),
                (value.len() * 2) as u32,
            ),
            None => RegDeleteValueW(handle.0, wide(VALUE_NAME).as_ptr()),
        }
    };
    if code == ERROR_SUCCESS || value.is_none() && code == ERROR_FILE_NOT_FOUND {
        Ok(())
    } else {
        Err(strings::STARTUP_WRITE_FAILED.into())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn startup_quotes_unicode_paths_and_rejects_unsupported_commands() {
        assert_eq!(
            String::from_utf16_lossy(&command(Path::new(r"C:\校园网 App\alogin.exe")).unwrap()),
            "\"C:\\校园网 App\\alogin.exe\" --background\0"
        );
        for path in [
            "relative.exe".to_owned(),
            "C:\\bad\"path.exe".to_owned(),
            "C:\\bad\0path.exe".to_owned(),
            format!("C:\\{}\\app.exe", "a".repeat(260)),
        ] {
            assert!(command(Path::new(&path)).is_err());
        }
    }

    #[test]
    fn startup_registration_roundtrips_and_removal_preserves_other_values() {
        // Never touch the real Run key in automated tests.
        let key = format!(r"Software\Alogin.Tests\Startup-{}", std::process::id());
        struct Cleanup(String);
        impl Drop for Cleanup {
            fn drop(&mut self) {
                unsafe { RegDeleteTreeW(HKEY_CURRENT_USER, wide(&self.0).as_ptr()) };
            }
        }
        let _cleanup = Cleanup(key.clone());
        assert_eq!(read(&key).unwrap(), None);
        write(&key, None).unwrap();
        let value = command(Path::new(r"C:\校园网 App\alogin.exe")).unwrap();
        write(&key, Some(&value)).unwrap();
        assert_eq!(read(&key).unwrap(), Some(value.clone()));
        let mut handle = ptr::null_mut();
        assert_eq!(
            unsafe {
                RegOpenKeyExW(
                    HKEY_CURRENT_USER,
                    wide(&key).as_ptr(),
                    0,
                    KEY_SET_VALUE | KEY_QUERY_VALUE,
                    &mut handle,
                )
            },
            ERROR_SUCCESS
        );
        let handle = Key(handle);
        assert_eq!(
            unsafe {
                RegSetValueExW(
                    handle.0,
                    wide("OtherApp").as_ptr(),
                    0,
                    REG_SZ,
                    value.as_ptr().cast(),
                    (value.len() * 2) as u32,
                )
            },
            ERROR_SUCCESS
        );
        write(&key, None).unwrap();
        write(&key, None).unwrap();
        assert_eq!(read(&key).unwrap(), None);
        let mut size = 0;
        assert_eq!(
            unsafe {
                RegQueryValueExW(
                    handle.0,
                    wide("OtherApp").as_ptr(),
                    ptr::null(),
                    ptr::null_mut(),
                    ptr::null_mut(),
                    &mut size,
                )
            },
            ERROR_SUCCESS
        );
        // An invalid registry type is an error, not a silently disabled switch.
        let number = 1u32;
        assert_eq!(
            unsafe {
                RegSetValueExW(
                    handle.0,
                    wide(VALUE_NAME).as_ptr(),
                    0,
                    REG_DWORD,
                    (&number as *const u32).cast(),
                    4,
                )
            },
            ERROR_SUCCESS
        );
        assert_eq!(read(&key).unwrap_err(), strings::STARTUP_READ_FAILED);
    }
}
