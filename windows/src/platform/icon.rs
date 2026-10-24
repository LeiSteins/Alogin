use std::ptr;
use windows_sys::Win32::{
    Foundation::HWND, System::LibraryLoader::GetModuleHandleW, UI::WindowsAndMessaging::*,
};

const ICON_ID: *const u16 = 101usize as *const u16;

// LR_SHARED icons belong to the module and must not be destroyed by the caller.
pub fn load(small: bool) -> HICON {
    unsafe {
        LoadImageW(
            GetModuleHandleW(ptr::null()),
            ICON_ID,
            IMAGE_ICON,
            GetSystemMetrics(if small { SM_CXSMICON } else { SM_CXICON }),
            GetSystemMetrics(if small { SM_CYSMICON } else { SM_CYICON }),
            LR_SHARED,
        )
        .cast()
    }
}

pub fn apply(window: HWND) {
    unsafe {
        SendMessageW(window, WM_SETICON, ICON_BIG as usize, load(false) as isize);
        SendMessageW(window, WM_SETICON, ICON_SMALL as usize, load(true) as isize);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn application_icon_resource_is_embedded_for_title_bar_and_tray() {
        assert!(!load(false).is_null());
        assert!(!load(true).is_null());
    }
}
