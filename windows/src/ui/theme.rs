//! Native theme painting. Theme state belongs to the visible HWND, never to the
//! mutable application/session state, so synchronous paint messages can reenter.
use crate::platform::wide;
use std::{mem, ptr};
use windows_sys::Win32::{
    Foundation::*,
    Graphics::{Dwm::*, Gdi::*},
    System::Registry::*,
    UI::{
        Accessibility::*,
        Controls::*,
        HiDpi::GetDpiForWindow,
        Input::KeyboardAndMouse::{GetFocus, IsWindowEnabled},
        Shell::*,
        WindowsAndMessaging::*,
    },
};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Palette {
    dark: bool,
    high_contrast: bool,
    background: COLORREF,
    surface: COLORREF,
    text: COLORREF,
    disabled: COLORREF,
    border: COLORREF,
    button: COLORREF,
    hover: COLORREF,
    pressed: COLORREF,
    accent: COLORREF,
}

const fn rgb(r: u8, g: u8, b: u8) -> COLORREF {
    r as u32 | ((g as u32) << 8) | ((b as u32) << 16)
}

fn use_dark(apps_use_light: Option<u32>, high_contrast: bool) -> bool {
    !high_contrast && apps_use_light == Some(0)
}

impl Palette {
    fn system() -> Self {
        let mut contrast = HIGHCONTRASTW {
            cbSize: mem::size_of::<HIGHCONTRASTW>() as u32,
            ..Default::default()
        };
        let high_contrast = unsafe {
            SystemParametersInfoW(
                SPI_GETHIGHCONTRAST,
                contrast.cbSize,
                (&mut contrast as *mut HIGHCONTRASTW).cast(),
                0,
            )
        } != 0
            && contrast.dwFlags & HCF_HIGHCONTRASTON != 0;
        let mut value = 1u32;
        let mut size = mem::size_of_val(&value) as u32;
        let code = unsafe {
            RegGetValueW(
                HKEY_CURRENT_USER,
                wide("Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize").as_ptr(),
                wide("AppsUseLightTheme").as_ptr(),
                RRF_RT_REG_DWORD,
                ptr::null_mut(),
                (&mut value as *mut u32).cast(),
                &mut size,
            )
        };
        if use_dark((code == ERROR_SUCCESS).then_some(value), high_contrast) {
            Self::dark()
        } else {
            unsafe {
                Self {
                    dark: false,
                    high_contrast,
                    background: GetSysColor(COLOR_WINDOW),
                    surface: GetSysColor(COLOR_WINDOW),
                    text: GetSysColor(COLOR_WINDOWTEXT),
                    disabled: GetSysColor(COLOR_GRAYTEXT),
                    border: GetSysColor(COLOR_WINDOWFRAME),
                    button: GetSysColor(COLOR_BTNFACE),
                    hover: GetSysColor(COLOR_HIGHLIGHT),
                    pressed: GetSysColor(COLOR_HIGHLIGHT),
                    accent: GetSysColor(COLOR_HIGHLIGHT),
                }
            }
        }
    }
    fn dark() -> Self {
        Self {
            dark: true,
            high_contrast: false,
            background: rgb(32, 32, 32),
            surface: rgb(43, 43, 43),
            text: rgb(245, 245, 245),
            disabled: rgb(160, 160, 160),
            border: rgb(115, 115, 115),
            button: rgb(52, 52, 52),
            hover: rgb(68, 68, 68),
            pressed: rgb(42, 42, 42),
            accent: rgb(134, 185, 255),
        }
    }
}

struct Theme {
    palette: Palette,
    background: HBRUSH,
    surface: HBRUSH,
}
impl Theme {
    fn new(palette: Palette) -> Self {
        Self {
            palette,
            background: unsafe { CreateSolidBrush(palette.background) },
            surface: unsafe { CreateSolidBrush(palette.surface) },
        }
    }
}
impl Drop for Theme {
    fn drop(&mut self) {
        unsafe {
            DeleteObject(self.background);
            DeleteObject(self.surface);
        }
    }
}

pub struct WindowState {
    pub host: HWND,
    theme: Theme,
}

pub fn attach(window: HWND, host: HWND) {
    let state = Box::new(WindowState {
        host,
        theme: Theme::new(Palette::system()),
    });
    unsafe {
        SetWindowLongPtrW(window, GWLP_USERDATA, Box::into_raw(state) as isize);
    }
}
fn state(window: HWND) -> *mut WindowState {
    unsafe { GetWindowLongPtrW(window, GWLP_USERDATA) as *mut WindowState }
}
pub fn host(window: HWND) -> HWND {
    let state = state(window);
    if state.is_null() {
        ptr::null_mut()
    } else {
        unsafe { (*state).host }
    }
}
pub fn detach(window: HWND) {
    let state = state(window);
    unsafe {
        SetWindowLongPtrW(window, GWLP_USERDATA, 0);
    }
    if !state.is_null() {
        unsafe {
            drop(Box::from_raw(state));
        }
    }
}

pub fn refresh(window: HWND) {
    let state = state(window);
    if state.is_null() {
        return;
    }
    let palette = Palette::system();
    unsafe {
        if (*state).theme.palette != palette {
            (*state).theme = Theme::new(palette);
        }
        let dark = palette.dark as i32;
        DwmSetWindowAttribute(
            window,
            DWMWA_USE_IMMERSIVE_DARK_MODE as u32,
            (&dark as *const i32).cast(),
            mem::size_of_val(&dark) as u32,
        );
        EnumChildWindows(window, Some(refresh_child), 0);
        RedrawWindow(
            window,
            ptr::null(),
            ptr::null_mut(),
            RDW_INVALIDATE | RDW_ERASE | RDW_FRAME | RDW_ALLCHILDREN,
        );
    }
}

unsafe extern "system" fn refresh_child(window: HWND, _: LPARAM) -> i32 {
    apply_native_theme(window);
    1
}

pub fn install_control(window: HWND, parent: HWND, class: &str) -> Result<(), String> {
    let kind = match class {
        "BUTTON" => 1,
        "COMBOBOX" => 2,
        _ => 0,
    };
    if kind != 0
        && unsafe { SetWindowSubclass(window, Some(control_proc), kind, state(parent) as usize) }
            == 0
    {
        return Err(crate::strings::CONTROL_FAILED.into());
    }
    apply_native_theme(window);
    Ok(())
}

fn apply_native_theme(window: HWND) {
    let parent = unsafe { GetParent(window) };
    let state = state(parent);
    if state.is_null() {
        return;
    }
    let palette = unsafe { (*state).theme.palette };
    // An optional system theme class improves native edit/list scrollbar borders.
    // Older Windows versions can reject it; client-area colors are painted below.
    let class = wide("DarkMode_Explorer");
    let name = if palette.dark {
        class.as_ptr()
    } else {
        ptr::null()
    };
    unsafe {
        SetWindowTheme(window, name, ptr::null());
    }
    let mut combo = COMBOBOXINFO {
        cbSize: mem::size_of::<COMBOBOXINFO>() as u32,
        ..Default::default()
    };
    if unsafe { GetComboBoxInfo(window, &mut combo) } != 0 && !combo.hwndList.is_null() {
        unsafe {
            SetWindowTheme(combo.hwndList, name, ptr::null());
        }
    }
}

pub fn message(window: HWND, message: u32, wparam: WPARAM, lparam: LPARAM) -> Option<LRESULT> {
    let state = state(window);
    if state.is_null() {
        return None;
    }
    let theme = unsafe { &(*state).theme };
    match message {
        WM_ERASEBKGND => {
            let mut rect = RECT::default();
            unsafe {
                GetClientRect(window, &mut rect);
                FillRect(wparam as HDC, &rect, theme.background);
            }
            Some(1)
        }
        WM_CTLCOLORSTATIC | WM_CTLCOLOREDIT | WM_CTLCOLORLISTBOX | WM_CTLCOLORBTN => {
            if theme.palette.high_contrast {
                return None;
            }
            let dc = wparam as HDC;
            let child = lparam as HWND;
            // Disabled edits send WM_CTLCOLORSTATIC; preserve their input surface.
            let mut class = [0u16; 32];
            let len = unsafe { GetClassNameW(child, class.as_mut_ptr(), class.len() as i32) };
            let input = message == WM_CTLCOLOREDIT
                || message == WM_CTLCOLORLISTBOX
                || String::from_utf16_lossy(&class[..len.max(0) as usize]) == "Edit";
            unsafe {
                SetTextColor(
                    dc,
                    if IsWindowEnabled(child) != 0 {
                        theme.palette.text
                    } else {
                        theme.palette.disabled
                    },
                );
                SetBkColor(
                    dc,
                    if input {
                        theme.palette.surface
                    } else {
                        theme.palette.background
                    },
                );
            }
            Some(if input {
                theme.surface
            } else {
                theme.background
            } as isize)
        }
        _ => None,
    }
}

unsafe extern "system" fn control_proc(
    window: HWND,
    message: u32,
    wparam: WPARAM,
    lparam: LPARAM,
    kind: usize,
    data: usize,
) -> LRESULT {
    if message == WM_NCDESTROY {
        unsafe {
            RemoveWindowSubclass(window, Some(control_proc), kind);
        }
        return unsafe { DefSubclassProc(window, message, wparam, lparam) };
    }
    let state = data as *const WindowState;
    if !state.is_null() && unsafe { (*state).theme.palette.dark } {
        match message {
            WM_PAINT => {
                let mut paint = PAINTSTRUCT::default();
                let dc = unsafe { BeginPaint(window, &mut paint) };
                draw_control(window, dc, kind, unsafe { (*state).theme.palette });
                unsafe {
                    EndPaint(window, &paint);
                }
                return 0;
            }
            WM_PRINTCLIENT => {
                draw_control(window, wparam as HDC, kind, unsafe {
                    (*state).theme.palette
                });
                return 0;
            }
            WM_ERASEBKGND => return 1,
            _ => (),
        }
    }
    unsafe { DefSubclassProc(window, message, wparam, lparam) }
}

fn draw_control(window: HWND, dc: HDC, kind: usize, palette: Palette) {
    let saved = unsafe { SaveDC(dc) };
    let font = unsafe { SendMessageW(window, WM_GETFONT, 0, 0) };
    if font != 0 {
        unsafe {
            SelectObject(dc, font as HGDIOBJ);
        }
    }
    let mut rect = RECT::default();
    unsafe {
        GetClientRect(window, &mut rect);
        SetBkMode(dc, TRANSPARENT as i32);
    }
    let enabled = unsafe { IsWindowEnabled(window) } != 0;
    let color = if enabled {
        palette.text
    } else {
        palette.disabled
    };
    unsafe {
        SetTextColor(dc, color);
    }
    let len = unsafe { GetWindowTextLengthW(window) }.max(0) as usize;
    let mut label = vec![0u16; len + 1];
    let count = unsafe { GetWindowTextW(window, label.as_mut_ptr(), label.len() as i32) };
    let dpi = unsafe { GetDpiForWindow(window) }.max(96);
    let unit = |n| super::scale(n, dpi);
    if kind == 2 {
        fill(dc, &rect, palette.surface);
        border(
            dc,
            &rect,
            if unsafe { GetFocus() } == window {
                palette.accent
            } else {
                palette.border
            },
        );
        let mut text_rect = RECT {
            left: unit(8),
            right: rect.right - unit(28),
            ..rect
        };
        unsafe {
            DrawTextW(
                dc,
                label.as_ptr(),
                count,
                &mut text_rect,
                DT_SINGLELINE | DT_VCENTER | DT_END_ELLIPSIS,
            );
        }
        let center_x = rect.right - unit(14);
        let center_y = rect.bottom / 2;
        line(
            dc,
            &[
                POINT {
                    x: center_x - unit(4),
                    y: center_y - unit(2),
                },
                POINT {
                    x: center_x,
                    y: center_y + unit(2),
                },
                POINT {
                    x: center_x + unit(4),
                    y: center_y - unit(2),
                },
            ],
            color,
            unit(1).max(1),
        );
    } else {
        let style = unsafe { GetWindowLongPtrW(window, GWL_STYLE) } as u32 & BS_TYPEMASK as u32;
        let button_state = unsafe { SendMessageW(window, BM_GETSTATE, 0, 0) } as u32;
        if style == BS_GROUPBOX as u32 {
            fill(dc, &rect, palette.background);
            let mut outline = rect;
            outline.top += unit(9);
            border(dc, &outline, palette.border);
            let mut size = SIZE::default();
            unsafe {
                GetTextExtentPoint32W(dc, label.as_ptr(), count, &mut size);
            }
            let mut caption = RECT {
                left: unit(10),
                top: 0,
                right: unit(18) + size.cx,
                bottom: unit(22),
            };
            fill(dc, &caption, palette.background);
            caption.left += unit(4);
            unsafe {
                DrawTextW(
                    dc,
                    label.as_ptr(),
                    count,
                    &mut caption,
                    DT_SINGLELINE | DT_VCENTER,
                );
            }
        } else if style == BS_AUTOCHECKBOX as u32 {
            fill(dc, &rect, palette.background);
            let size = unit(16);
            let top = (rect.bottom - size) / 2;
            let square = RECT {
                left: 0,
                top,
                right: size,
                bottom: top + size,
            };
            let checked =
                unsafe { SendMessageW(window, BM_GETCHECK, 0, 0) } == BST_CHECKED as isize;
            fill(
                dc,
                &square,
                if checked && enabled {
                    palette.accent
                } else {
                    palette.surface
                },
            );
            border(
                dc,
                &square,
                if enabled {
                    palette.border
                } else {
                    palette.disabled
                },
            );
            if checked {
                line(
                    dc,
                    &[
                        POINT {
                            x: unit(3),
                            y: top + unit(8),
                        },
                        POINT {
                            x: unit(7),
                            y: top + unit(12),
                        },
                        POINT {
                            x: unit(13),
                            y: top + unit(4),
                        },
                    ],
                    if enabled {
                        palette.background
                    } else {
                        palette.disabled
                    },
                    unit(2).max(1),
                );
            }
            let mut caption = RECT {
                left: size + unit(8),
                ..rect
            };
            unsafe {
                DrawTextW(
                    dc,
                    label.as_ptr(),
                    count,
                    &mut caption,
                    DT_SINGLELINE | DT_VCENTER,
                );
            }
            focus(window, dc, &caption);
        } else {
            let pressed = button_state & BST_PUSHED != 0;
            let hot = button_state & BST_HOT != 0;
            fill(
                dc,
                &rect,
                if pressed {
                    palette.pressed
                } else if hot {
                    palette.hover
                } else {
                    palette.button
                },
            );
            border(
                dc,
                &rect,
                if style == BS_DEFPUSHBUTTON as u32 || unsafe { GetFocus() } == window {
                    palette.accent
                } else {
                    palette.border
                },
            );
            let mut caption = rect;
            unsafe {
                DrawTextW(
                    dc,
                    label.as_ptr(),
                    count,
                    &mut caption,
                    DT_CENTER | DT_SINGLELINE | DT_VCENTER,
                );
            }
            unsafe {
                InflateRect(&mut caption, -unit(4), -unit(4));
            }
            focus(window, dc, &caption);
        }
    }
    unsafe {
        RestoreDC(dc, saved);
    }
}

fn focus(window: HWND, dc: HDC, rect: &RECT) {
    if unsafe { GetFocus() } == window
        && unsafe { SendMessageW(window, WM_QUERYUISTATE, 0, 0) } & UISF_HIDEFOCUS as isize == 0
    {
        unsafe {
            DrawFocusRect(dc, rect);
        }
    }
}
fn fill(dc: HDC, rect: &RECT, color: COLORREF) {
    unsafe {
        let brush = CreateSolidBrush(color);
        FillRect(dc, rect, brush);
        DeleteObject(brush);
    }
}
fn border(dc: HDC, rect: &RECT, color: COLORREF) {
    unsafe {
        let brush = CreateSolidBrush(color);
        FrameRect(dc, rect, brush);
        DeleteObject(brush);
    }
}
fn line(dc: HDC, points: &[POINT], color: COLORREF, width: i32) {
    unsafe {
        let pen = CreatePen(PS_SOLID, width, color);
        let old = SelectObject(dc, pen);
        Polyline(dc, points.as_ptr(), points.len() as i32);
        SelectObject(dc, old);
        DeleteObject(pen);
    }
}

pub struct MenuTheme(Theme);
impl MenuTheme {
    pub fn new(menu: HMENU) -> Self {
        let theme = Theme::new(Palette::system());
        if theme.palette.dark {
            let info = MENUINFO {
                cbSize: mem::size_of::<MENUINFO>() as u32,
                fMask: MIM_BACKGROUND,
                hbrBack: theme.background,
                ..Default::default()
            };
            unsafe {
                SetMenuInfo(menu, &info);
            }
        }
        Self(theme)
    }
    pub fn dark(&self) -> bool {
        self.0.palette.dark
    }
}
pub fn menu_message(message: u32, lparam: LPARAM) -> Option<LRESULT> {
    match message {
        WM_MEASUREITEM => {
            let item = unsafe { &mut *(lparam as *mut MEASUREITEMSTRUCT) };
            if item.CtlType != ODT_MENU {
                return None;
            }
            let dpi = unsafe { windows_sys::Win32::UI::HiDpi::GetDpiForSystem() }.max(96);
            item.itemWidth = super::scale(184, dpi) as u32;
            item.itemHeight = super::scale(30, dpi) as u32;
            Some(1)
        }
        WM_DRAWITEM => {
            let item = unsafe { &*(lparam as *const DRAWITEMSTRUCT) };
            if item.CtlType != ODT_MENU {
                return None;
            }
            let palette = Palette::system();
            let selected = item.itemState & ODS_SELECTED != 0;
            let disabled = item.itemState & ODS_DISABLED != 0;
            let saved = unsafe { SaveDC(item.hDC) };
            fill(
                item.hDC,
                &item.rcItem,
                if selected {
                    palette.hover
                } else {
                    palette.background
                },
            );
            let label = match item.itemID as usize {
                super::ID_SHOW => crate::strings::SHOW,
                super::ID_LOGIN => crate::strings::LOGIN,
                super::ID_REFRESH => crate::strings::REFRESH,
                super::ID_EXIT => crate::strings::EXIT,
                _ => "",
            };
            let mut rect = item.rcItem;
            rect.left += 12;
            let dpi = unsafe { windows_sys::Win32::UI::HiDpi::GetDpiForSystem() }.max(96);
            let mut metrics = NONCLIENTMETRICSW {
                cbSize: mem::size_of::<NONCLIENTMETRICSW>() as u32,
                ..Default::default()
            };
            let font = if unsafe {
                windows_sys::Win32::UI::HiDpi::SystemParametersInfoForDpi(
                    SPI_GETNONCLIENTMETRICS,
                    metrics.cbSize,
                    (&mut metrics as *mut NONCLIENTMETRICSW).cast(),
                    0,
                    dpi,
                )
            } != 0
            {
                unsafe { CreateFontIndirectW(&metrics.lfMenuFont) }
            } else {
                ptr::null_mut()
            };
            unsafe {
                SelectObject(
                    item.hDC,
                    if font.is_null() {
                        GetStockObject(DEFAULT_GUI_FONT)
                    } else {
                        font
                    },
                );
                SetBkMode(item.hDC, TRANSPARENT as i32);
                SetTextColor(
                    item.hDC,
                    if disabled {
                        palette.disabled
                    } else {
                        palette.text
                    },
                );
                DrawTextW(
                    item.hDC,
                    wide(label).as_ptr(),
                    -1,
                    &mut rect,
                    DT_SINGLELINE | DT_VCENTER,
                );
                RestoreDC(item.hDC, saved);
                if !font.is_null() {
                    DeleteObject(font);
                }
            }
            Some(1)
        }
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn high_contrast_and_missing_preferences_take_precedence_over_dark_mode() {
        assert!(use_dark(Some(0), false));
        assert!(!use_dark(Some(1), false));
        assert!(!use_dark(None, false));
        assert!(!use_dark(Some(0), true));
    }
    #[test]
    fn dark_text_and_disabled_labels_have_readable_contrast() {
        fn luminance(color: COLORREF) -> f64 {
            [0, 8, 16]
                .into_iter()
                .zip([0.2126, 0.7152, 0.0722])
                .map(|(shift, weight)| {
                    let value = ((color >> shift) & 255) as f64 / 255.0;
                    weight
                        * if value <= 0.04045 {
                            value / 12.92
                        } else {
                            ((value + 0.055) / 1.055).powf(2.4)
                        }
                })
                .sum()
        }
        let p = Palette::dark();
        for foreground in [p.text, p.disabled] {
            for background in [p.background, p.surface, p.button, p.hover] {
                assert!((luminance(foreground) + 0.05) / (luminance(background) + 0.05) >= 3.0);
            }
        }
        assert!((luminance(p.text) + 0.05) / (luminance(p.background) + 0.05) >= 4.5);
    }
}
