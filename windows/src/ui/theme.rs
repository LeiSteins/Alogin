//! Event-driven Windows theme adaptation for Slint and the native tray menu.
use super::{AppTheme, MainWindow};
use crate::{domain::Appearance, platform::wide};
use slint::ComponentHandle;
use std::{mem, ptr};
use windows_sys::Win32::{
    Foundation::*,
    Graphics::{Dwm::*, Gdi::*},
    System::Registry::*,
    UI::{Accessibility::*, Controls::*, WindowsAndMessaging::*},
};
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Palette {
    dark: bool,
    high_contrast: bool,
    background: COLORREF,
    chrome: COLORREF,
    surface: COLORREF,
    card: COLORREF,
    text: COLORREF,
    disabled: COLORREF,
    border: COLORREF,
    button: COLORREF,
    button_text: COLORREF,
    hover: COLORREF,
    navigation_selected: COLORREF,
    accent: COLORREF,
    accent_text: COLORREF,
}

const fn rgb(r: u8, g: u8, b: u8) -> COLORREF {
    r as u32 | ((g as u32) << 8) | ((b as u32) << 16)
}

fn use_dark(apps_use_light: Option<u32>, high_contrast: bool) -> bool {
    !high_contrast && apps_use_light == Some(0)
}

impl Palette {
    fn system() -> Self {
        Self::for_appearance(Appearance::System)
    }
    fn for_appearance(appearance: Appearance) -> Self {
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
        let light = match appearance {
            Appearance::System => (code == ERROR_SUCCESS).then_some(value),
            Appearance::Light => Some(1),
            Appearance::Dark => Some(0),
        };
        if use_dark(light, high_contrast) {
            Self::dark()
        } else {
            unsafe {
                Self {
                    dark: false,
                    high_contrast,
                    background: GetSysColor(COLOR_WINDOW),
                    chrome: if high_contrast {
                        GetSysColor(COLOR_WINDOW)
                    } else {
                        rgb(243, 243, 243)
                    },
                    surface: GetSysColor(COLOR_WINDOW),
                    card: if high_contrast {
                        GetSysColor(COLOR_BTNFACE)
                    } else {
                        rgb(255, 255, 255)
                    },
                    text: GetSysColor(COLOR_WINDOWTEXT),
                    disabled: GetSysColor(COLOR_GRAYTEXT),
                    border: if high_contrast {
                        GetSysColor(COLOR_WINDOWFRAME)
                    } else {
                        rgb(225, 225, 225)
                    },
                    button: GetSysColor(COLOR_BTNFACE),
                    button_text: GetSysColor(COLOR_BTNTEXT),
                    hover: if high_contrast {
                        GetSysColor(COLOR_HIGHLIGHT)
                    } else {
                        rgb(226, 230, 236)
                    },
                    navigation_selected: if high_contrast {
                        GetSysColor(COLOR_WINDOW)
                    } else {
                        rgb(230, 230, 230)
                    },
                    accent: GetSysColor(COLOR_HIGHLIGHT),
                    accent_text: GetSysColor(COLOR_HIGHLIGHTTEXT),
                }
            }
        }
    }
    fn dark() -> Self {
        Self {
            dark: true,
            high_contrast: false,
            background: rgb(30, 30, 30),
            chrome: rgb(45, 45, 48),
            surface: rgb(37, 37, 38),
            card: rgb(45, 45, 48),
            text: rgb(245, 245, 245),
            disabled: rgb(190, 190, 190),
            border: rgb(62, 62, 66),
            button: rgb(52, 52, 52),
            button_text: rgb(245, 245, 245),
            hover: rgb(58, 58, 62),
            navigation_selected: rgb(55, 55, 61),
            accent: rgb(96, 181, 255),
            accent_text: rgb(32, 32, 32),
        }
    }
}

struct Theme {
    palette: Palette,
    background: HBRUSH,
}
impl Theme {
    fn new(palette: Palette) -> Self {
        Self {
            palette,
            background: unsafe { CreateSolidBrush(palette.background) },
        }
    }
}
impl Drop for Theme {
    fn drop(&mut self) {
        unsafe {
            DeleteObject(self.background);
        }
    }
}

pub fn refresh(window: &MainWindow) {
    apply(
        window,
        Palette::for_appearance(
            Appearance::from_index(window.get_appearance()).unwrap_or_default(),
        ),
    );
}
fn apply(window: &MainWindow, p: Palette) {
    let theme = window.global::<AppTheme>();
    theme.set_dark(p.dark);
    theme.set_high_contrast(p.high_contrast);
    theme.set_background(color(p.background));
    theme.set_chrome(color(p.chrome));
    theme.set_surface(color(p.card));
    theme.set_input(color(p.surface));
    theme.set_button(color(p.button));
    theme.set_button_text(color(p.button_text));
    theme.set_hover(color(p.hover));
    theme.set_navigation_selected(color(p.navigation_selected));
    theme.set_pressed(color(if p.dark { rgb(42, 42, 42) } else { p.hover }));
    theme.set_text(color(p.text));
    theme.set_muted(color(p.disabled));
    theme.set_error(color(if p.high_contrast {
        p.text
    } else if p.dark {
        rgb(244, 135, 130)
    } else {
        rgb(196, 43, 28)
    }));
    theme.set_border(color(p.border));
    theme.set_accent(color(p.accent));
    theme.set_accent_text(color(p.accent_text));
}
#[cfg(test)]
pub fn preview(window: &MainWindow, dark: bool, high_contrast: bool) {
    let mut p = Palette::dark();
    if !dark {
        p.background = rgb(255, 255, 255);
        p.chrome = rgb(243, 243, 243);
        p.surface = rgb(255, 255, 255);
        p.card = rgb(255, 255, 255);
        p.border = rgb(225, 225, 225);
        p.text = rgb(20, 20, 20);
        p.disabled = rgb(102, 102, 102);
        p.button = rgb(235, 237, 240);
        p.button_text = p.text;
        p.accent = rgb(0, 95, 184);
        p.accent_text = rgb(255, 255, 255);
        p.hover = rgb(226, 230, 236);
        p.navigation_selected = rgb(230, 230, 230);
    }
    if high_contrast {
        p.background = rgb(0, 0, 0);
        p.chrome = p.background;
        p.surface = p.background;
        p.card = p.background;
        p.button = p.background;
        p.text = rgb(255, 255, 255);
        p.disabled = p.text;
        p.button_text = p.text;
        p.border = p.text;
        p.accent = rgb(255, 255, 0);
        p.accent_text = p.background;
        p.hover = p.accent;
        p.navigation_selected = p.background;
    }
    p.dark = dark;
    p.high_contrast = high_contrast;
    apply(window, p);
}
fn color(value: COLORREF) -> slint::Color {
    slint::Color::from_rgb_u8(value as u8, (value >> 8) as u8, (value >> 16) as u8)
}
pub fn title_bar(window: HWND, component: &MainWindow) {
    let theme = component.global::<AppTheme>();
    let dark = i32::from(theme.get_dark());
    // Reset explicit colors when entering high contrast so Windows owns the
    // non-client palette again. Older Windows versions ignore these attributes.
    let caption = if theme.get_high_contrast() {
        DWMWA_COLOR_DEFAULT
    } else {
        let value = theme.get_chrome();
        rgb(value.red(), value.green(), value.blue())
    };
    let text = if theme.get_high_contrast() {
        DWMWA_COLOR_DEFAULT
    } else {
        let value = theme.get_text();
        rgb(value.red(), value.green(), value.blue())
    };
    unsafe {
        DwmSetWindowAttribute(
            window,
            DWMWA_USE_IMMERSIVE_DARK_MODE as u32,
            (&dark as *const i32).cast(),
            mem::size_of_val(&dark) as u32,
        );
        for (attribute, value) in [(DWMWA_CAPTION_COLOR, caption), (DWMWA_TEXT_COLOR, text)] {
            DwmSetWindowAttribute(
                window,
                attribute as u32,
                (&value as *const COLORREF).cast(),
                mem::size_of_val(&value) as u32,
            );
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
