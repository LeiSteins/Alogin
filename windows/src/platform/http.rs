use crate::{platform::wide, strings};
use std::{
    ffi::c_void,
    ptr,
    time::{Duration, Instant},
};
use windows_sys::Win32::Networking::WinHttp::*;
use zeroize::Zeroizing;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Host {
    Eportal,
    Wlgn,
    Lgn,
    SelfService,
}

impl Host {
    pub fn name(self) -> &'static str {
        match self {
            Self::Eportal => "10.21.221.98",
            Self::Wlgn => "wlgn.bjut.edu.cn",
            Self::Lgn => "lgn.bjut.edu.cn",
            Self::SelfService => "jfself.bjut.edu.cn",
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
        let generation = crate::http_log::generation();
        if generation.is_none() {
            return self.get_inner(host, api, path, Instant::now());
        }
        static REQUEST_ID: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);
        let request_id = REQUEST_ID.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let start = Instant::now();
        let result = self.get_inner(host, api, path, start);
        let outcome = match &result {
            Ok(response) => format!(
                "HTTP {}，响应 {} 字节，{}",
                response.status,
                response.body.len(),
                response_kind(&response.body)
            ),
            Err(error) => format!("请求失败：{error}"),
        };
        // Query strings and response bodies can contain credentials and identifiers.
        crate::http_log::record(
            generation,
            crate::http_log::Category::Http,
            format!(
                "请求 {request_id}：GET {}://{}:{}{} {} ({} ms)",
                if host.secure() { "https" } else { "http" },
                host.name(),
                host.port(api),
                safe_path(path),
                outcome,
                start.elapsed().as_millis()
            ),
        );
        result
    }

    fn get_inner(
        &self,
        host: Host,
        api: bool,
        path: &str,
        start: Instant,
    ) -> Result<Response, String> {
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
            "Accept: */*\r\nCache-Control: no-cache\r\nReferer: {}://{}{}\r\n",
            if host.secure() { "https" } else { "http" },
            host.name(),
            if host == Host::SelfService {
                "/Self/"
            } else {
                "/"
            }
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

// Only known static endpoint names are displayed. Redirect targets and all
// query parameters can contain identifiers or credentials.
fn response_kind(body: &[u8]) -> &'static str {
    let prefix = body
        .iter()
        .copied()
        .skip_while(u8::is_ascii_whitespace)
        .take(32)
        .collect::<Vec<_>>();
    if prefix.is_empty() {
        "空响应"
    } else if prefix.starts_with(b"{") || prefix.starts_with(b"[") {
        "JSON 格式"
    } else if prefix.starts_with(b"<") {
        "HTML 或 XML 格式"
    } else if prefix
        .iter()
        .all(|byte| byte.is_ascii_graphic() || byte.is_ascii_whitespace())
    {
        "文本或 JSONP 格式"
    } else {
        "其他格式"
    }
}

fn safe_path(path: &str) -> &'static str {
    match path.split('?').next().unwrap_or("") {
        "/" => "/",
        "/a79.htm" => "/a79.htm",
        "/drcom/chkstatus" => "/drcom/chkstatus",
        "/drcom/login" => "/drcom/login",
        "/eportal/portal/page/loadConfig" => "/eportal/portal/page/loadConfig",
        "/eportal/portal/online_list" => "/eportal/portal/online_list",
        "/eportal/portal/login" => "/eportal/portal/login",
        "/eportal/portal/self" => "/eportal/portal/self",
        "/Self/dashboard" => "/Self/dashboard",
        "/Self/dashboard/getOnlineList" => "/Self/dashboard/getOnlineList",
        "/Self/dashboard/tooffline" => "/Self/dashboard/tooffline",
        _ => "/[已隐藏]",
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

#[cfg(test)]
mod log_tests {
    use super::{response_kind, safe_path};
    #[test]
    fn request_paths_never_include_query_or_untrusted_redirect_paths() {
        assert_eq!(safe_path("/drcom/login?upass=secret"), "/drcom/login");
        assert_eq!(safe_path("/Self/private/secret?token=secret"), "/[已隐藏]");
        assert_eq!(safe_path("/drcom/login%3Fpassword=secret"), "/[已隐藏]");
    }

    #[test]
    fn response_summary_describes_only_format() {
        assert_eq!(response_kind(b"  {\"password\":\"secret\"}"), "JSON 格式");
        assert_eq!(response_kind(b"<html>private</html>"), "HTML 或 XML 格式");
        assert_eq!(response_kind(&[0xff, 0x00]), "其他格式");
        assert_eq!(response_kind(b""), "空响应");
    }
}
