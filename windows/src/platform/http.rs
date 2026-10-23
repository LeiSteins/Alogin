use crate::{platform::wide, strings};
use std::{
    ffi::c_void,
    ptr,
    time::{Duration, Instant},
};
use windows_sys::Win32::Networking::WinHttp::*;
use zeroize::Zeroizing;

#[derive(Clone, Copy)]
pub enum Host {
    Eportal,
    Wlgn,
}

impl Host {
    pub fn name(self) -> &'static str {
        match self {
            Self::Eportal => "10.21.221.98",
            Self::Wlgn => "wlgn.bjut.edu.cn",
        }
    }
    pub fn secure(self) -> bool {
        !matches!(self, Self::Eportal)
    }
    pub fn port(self, api: bool) -> u16 {
        match (self.secure(), api) {
            (false, false) => 80,
            (false, true) => 801,
            (true, false) => 443,
            (true, true) => 802,
        }
    }
}

struct Handle(*mut c_void);
impl Drop for Handle {
    fn drop(&mut self) {
        unsafe {
            WinHttpCloseHandle(self.0);
        }
    }
}
impl Handle {
    fn new(value: *mut c_void) -> Result<Self, String> {
        if value.is_null() {
            Err(last_error())
        } else {
            Ok(Self(value))
        }
    }
}

pub struct Response {
    pub status: u32,
    pub location: String,
    pub body: Vec<u8>,
}
impl Response {
    pub fn text(&self) -> String {
        if let Ok(text) = std::str::from_utf8(&self.body) {
            text.to_owned()
        } else {
            encoding_rs::GBK.decode(&self.body).0.into_owned()
        }
    }
    pub fn success(&self) -> bool {
        (200..300).contains(&self.status)
    }
}

pub struct Http {
    session: Handle,
}
impl Http {
    pub fn new() -> Result<Self, String> {
        let session = Handle::new(unsafe {
            WinHttpOpen(
                wide("Alogin-Windows/0.1").as_ptr(),
                WINHTTP_ACCESS_TYPE_NO_PROXY,
                ptr::null(),
                ptr::null(),
                0,
            )
        })?;
        if unsafe { WinHttpSetTimeouts(session.0, 3000, 3000, 3000, 3000) } == 0 {
            return Err(last_error());
        }
        Ok(Self { session })
    }

    pub fn get(&self, host: Host, api: bool, path: &str) -> Result<Response, String> {
        let start = Instant::now();
        let connection = Handle::new(unsafe {
            WinHttpConnect(
                self.session.0,
                wide(host.name()).as_ptr(),
                host.port(api),
                0,
            )
        })?;
        let path = Zeroizing::new(wide(path));
        let request = Handle::new(unsafe {
            WinHttpOpenRequest(
                connection.0,
                wide("GET").as_ptr(),
                path.as_ptr(),
                ptr::null(),
                ptr::null(),
                ptr::null(),
                if host.secure() {
                    WINHTTP_FLAG_SECURE
                } else {
                    0
                },
            )
        })?;
        set_option(
            &request,
            WINHTTP_OPTION_DISABLE_FEATURE,
            WINHTTP_DISABLE_REDIRECTS,
        )?;
        set_option(
            &request,
            WINHTTP_OPTION_AUTOLOGON_POLICY,
            WINHTTP_AUTOLOGON_SECURITY_LEVEL_HIGH,
        )?;
        // Fixed campus hosts only. Preserve hostname, expiry and usage validation.
        if host.secure() {
            set_option(
                &request,
                WINHTTP_OPTION_SECURITY_FLAGS,
                SECURITY_FLAG_IGNORE_UNKNOWN_CA,
            )?;
        }
        let headers = wide(&format!(
            "Accept: */*\r\nCache-Control: no-cache\r\nReferer: {}://{}/\r\n",
            if host.secure() { "https" } else { "http" },
            host.name()
        ));
        if unsafe {
            WinHttpSendRequest(
                request.0,
                headers.as_ptr(),
                (headers.len() - 1) as u32,
                ptr::null(),
                0,
                0,
                0,
            )
        } == 0
        {
            return Err(last_error());
        }
        if unsafe { WinHttpReceiveResponse(request.0, ptr::null_mut()) } == 0 {
            return Err(last_error());
        }
        let mut status: u32 = 0;
        let mut size = 4;
        if unsafe {
            WinHttpQueryHeaders(
                request.0,
                WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                ptr::null(),
                (&mut status as *mut u32).cast(),
                &mut size,
                ptr::null_mut(),
            )
        } == 0
        {
            return Err(last_error());
        }
        let mut location = [0u16; 2048];
        size = std::mem::size_of_val(&location) as u32;
        let location = if unsafe {
            WinHttpQueryHeaders(
                request.0,
                WINHTTP_QUERY_LOCATION,
                ptr::null(),
                location.as_mut_ptr().cast(),
                &mut size,
                ptr::null_mut(),
            )
        } != 0
        {
            String::from_utf16_lossy(&location[..size as usize / 2])
                .trim_end_matches('\0')
                .to_owned()
        } else {
            String::new()
        };
        let mut body = Vec::new();
        loop {
            if start.elapsed() > Duration::from_secs(12) {
                return Err(strings::REQUEST_TIMEOUT.into());
            }
            let mut chunk = [0u8; 8192];
            let mut read = 0;
            if unsafe {
                WinHttpReadData(
                    request.0,
                    chunk.as_mut_ptr().cast(),
                    chunk.len() as u32,
                    &mut read,
                )
            } == 0
            {
                return Err(last_error());
            }
            if read == 0 {
                break;
            }
            if body.len() + read as usize > 512 * 1024 {
                return Err(strings::RESPONSE_TOO_LARGE.into());
            }
            body.extend_from_slice(&chunk[..read as usize]);
        }
        Ok(Response {
            status,
            location,
            body,
        })
    }
}

fn set_option(handle: &Handle, option: u32, value: u32) -> Result<(), String> {
    if unsafe { WinHttpSetOption(handle.0, option, (&value as *const u32).cast(), 4) } == 0 {
        Err(last_error())
    } else {
        Ok(())
    }
}

fn last_error() -> String {
    // Never include request paths, response bodies or credentials in diagnostics.
    strings::request_error(unsafe { windows_sys::Win32::Foundation::GetLastError() })
}
