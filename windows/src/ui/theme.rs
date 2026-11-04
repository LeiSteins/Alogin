//! Event-driven Windows theme adaptation for Slint and the native tray menu.
use super::{AppTheme, MainWindow};
use crate::{domain::Appearance, platform::wide};
use slint::ComponentHandle;
use std::{
    cell::{Cell, RefCell},
    mem, ptr,
};
use windows_sys::Win32::{
    Foundation::*,
    Graphics::{Dwm::*, Gdi::*},
    System::{Registry::*, Threading::GetCurrentThreadId},
    UI::{Accessibility::*, WindowsAndMessaging::*},
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
/// Immutable snapshot held through the native menu's nested message loop.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MenuRow {
    pub id: usize,
    pub label: String,
    pub detail: Option<String>,
    pub enabled: bool,
    pub checked: Option<bool>,
    separator: bool,
}
impl MenuRow {
    pub fn action(id: usize, label: &str, enabled: bool) -> Self {
        Self {
            id,
            label: label.into(),
            detail: None,
            enabled,
            checked: None,
            separator: false,
        }
    }
    pub fn toggle(id: usize, label: &str, enabled: bool, checked: bool) -> Self {
        Self {
            checked: Some(checked),
            ..Self::action(id, label, enabled)
        }
    }
    pub fn header(label: &str, detail: &str) -> Self {
        Self {
            detail: Some(detail.replace(['\r', '\n', '\t'], " ")),
            ..Self::action(0, label, false)
        }
    }
    pub fn separator() -> Self {
        Self {
            separator: true,
            ..Self::action(0, "", false)
        }
    }
    fn text(&self) -> Vec<u16> {
        let label = match &self.detail {
            Some(detail) => format!("{}：{}", self.label, detail),
            None => self.label.clone(),
        };
        // Ampersands in SSIDs are data, not menu accelerators.
        wide(&label.replace('&', "&&"))
    }
}

pub struct MenuTheme {
    menu: HMENU,
    rows: RefCell<Vec<MenuRow>>,
}
impl MenuTheme {
    pub fn new(_appearance: Appearance, rows: Vec<MenuRow>) -> Option<Self> {
        let menu = unsafe { CreatePopupMenu() };
        if menu.is_null() {
            return None;
        }
        let result = Self {
            menu,
            rows: RefCell::new(rows),
        };
        for (index, row) in result.rows.borrow().iter().enumerate() {
            let mut text = row.text();
            let info = MENUITEMINFOW {
                cbSize: mem::size_of::<MENUITEMINFOW>() as u32,
                fMask: MIIM_FTYPE | MIIM_ID | MIIM_STATE | MIIM_STRING,
                fType: if row.separator {
                    MFT_SEPARATOR
                } else {
                    MFT_STRING
                },
                fState: if row.enabled {
                    MFS_ENABLED
                } else {
                    MFS_DISABLED
                } | if row.checked == Some(true) {
                    MFS_CHECKED
                } else {
                    0
                },
                wID: row.id as u32,
                dwTypeData: text.as_mut_ptr(),
                ..Default::default()
            };
            if unsafe { InsertMenuItemW(menu, index as u32, 1, &info) } == 0 {
                return None;
            }
        }
        Some(result)
    }
    pub fn handle(&self) -> HMENU {
        self.menu
    }
    pub fn popup_window(&self) -> HWND {
        menu_window(self.menu)
    }

    pub fn update_rows(&self, rows: Vec<MenuRow>) {
        if rows.len() != self.rows.borrow().len() {
            return;
        }
        for (index, row) in rows.into_iter().enumerate() {
            if self.rows.borrow()[index] == row {
                continue;
            }
            let mut text = row.text();
            let previous = unsafe { GetMenuState(self.menu, index as u32, MF_BYPOSITION) };
            let info = MENUITEMINFOW {
                cbSize: mem::size_of::<MENUITEMINFOW>() as u32,
                fMask: MIIM_STRING | MIIM_STATE,
                fState: (previous & MFS_HILITE)
                    | if row.enabled {
                        MFS_ENABLED
                    } else {
                        MFS_DISABLED
                    }
                    | if row.checked == Some(true) {
                        MFS_CHECKED
                    } else {
                        0
                    },
                dwTypeData: text.as_mut_ptr(),
                ..Default::default()
            };
            self.rows.borrow_mut()[index] = row;
            unsafe {
                SetMenuItemInfoW(self.menu, index as u32, 1, &info);
            }
        }
        let popup = self.popup_window();
        if !popup.is_null() {
            unsafe {
                RedrawWindow(popup, ptr::null(), ptr::null_mut(), RDW_INVALIDATE);
            }
        }
    }
}

struct MenuWindowSearch {
    menu: HMENU,
    window: HWND,
}
fn menu_window(menu: HMENU) -> HWND {
    let mut search = MenuWindowSearch {
        menu,
        window: ptr::null_mut(),
    };
    unsafe {
        EnumThreadWindows(
            GetCurrentThreadId(),
            Some(find_menu_window),
            (&mut search as *mut MenuWindowSearch) as LPARAM,
        );
    }
    search.window
}

unsafe extern "system" fn find_menu_window(window: HWND, lparam: LPARAM) -> i32 {
    let search = unsafe { &mut *(lparam as *mut MenuWindowSearch) };
    let mut class = [0u16; 16];
    if unsafe { GetClassNameW(window, class.as_mut_ptr(), class.len() as i32) } != 6
        || class[..6] != wide("#32768")[..6]
    {
        return 1;
    }
    let mut info = MENUBARINFO {
        cbSize: mem::size_of::<MENUBARINFO>() as u32,
        ..Default::default()
    };
    if unsafe { GetMenuBarInfo(window, OBJID_CLIENT, 0, &mut info) } != 0
        && info.hMenu == search.menu
    {
        search.window = window;
        return 0;
    }
    1
}
impl Drop for MenuTheme {
    fn drop(&mut self) {
        unsafe {
            DestroyMenu(self.menu);
        }
    }
}

thread_local! {
    static TRACKED_MENU: Cell<Option<(HWND, HMENU)>> = const { Cell::new(None) };
}

/// Only filters this UI thread's active tray menu; other menus and input pass on.
pub struct MenuFilter {
    hook: HHOOK,
    previous: Option<(HWND, HMENU)>,
}
impl MenuFilter {
    pub fn install(host: HWND, menu: HMENU) -> Option<Self> {
        let hook = unsafe {
            SetWindowsHookExW(
                WH_MSGFILTER,
                Some(menu_filter),
                ptr::null_mut(),
                GetCurrentThreadId(),
            )
        };
        if hook.is_null() {
            return None;
        }
        let previous = TRACKED_MENU.with(|slot| slot.replace(Some((host, menu))));
        Some(Self { hook, previous })
    }
}
impl Drop for MenuFilter {
    fn drop(&mut self) {
        TRACKED_MENU.with(|slot| slot.set(self.previous));
        unsafe {
            UnhookWindowsHookEx(self.hook);
        }
    }
}
unsafe extern "system" fn menu_filter(code: i32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    if code == MSGF_MENU as i32 && lparam != 0 {
        let message = unsafe { &*(lparam as *const MSG) };
        if let Some((host, menu)) = TRACKED_MENU.with(Cell::get)
            && keep_menu_input(host, menu, message)
        {
            return 1;
        }
    }
    unsafe { CallNextHookEx(ptr::null_mut(), code, wparam, lparam) }
}
fn keep_menu_input(host: HWND, menu: HMENU, message: &MSG) -> bool {
    let keyboard = message.message == WM_KEYDOWN && matches!(message.wParam, 0x0d | 0x20);
    let mouse = matches!(message.message, WM_LBUTTONUP | WM_RBUTTONUP);
    if !keyboard && !mouse {
        return false;
    }
    if mouse {
        let popup = menu_window(menu);
        let mut rect = RECT::default();
        if popup.is_null() || unsafe { GetWindowRect(popup, &mut rect) } == 0 {
            return false;
        }
        let region = unsafe { CreateRectRgn(0, 0, 0, 0) };
        let outside = !region.is_null()
            && unsafe { GetWindowRgn(popup, region) } != 0
            && unsafe { PtInRegion(region, message.pt.x - rect.left, message.pt.y - rect.top) }
                == 0;
        unsafe {
            DeleteObject(region);
        }
        // Rounded, clipped corners belong to the outside-dismiss area.
        if outside {
            return false;
        }
    }
    for index in 0..unsafe { GetMenuItemCount(menu) } {
        let state = unsafe { GetMenuState(menu, index as u32, MF_BYPOSITION) };
        let hit = if keyboard {
            state & MFS_HILITE != 0
        } else {
            let mut rect = RECT::default();
            (unsafe { GetMenuItemRect(ptr::null_mut(), menu, index as u32, &mut rect) != 0 })
                && message.pt.x >= rect.left
                && message.pt.x < rect.right
                && message.pt.y >= rect.top
                && message.pt.y < rect.bottom
        };
        if !hit {
            continue;
        }
        let id = unsafe { GetMenuItemID(menu, index) } as usize;
        if matches!(id, super::ID_SHOW | super::ID_EXIT) {
            if state & MFS_DISABLED == 0 && (!keyboard || message.lParam & (1 << 30) == 0) {
                // Dispatch these explicitly too: the menu's native selection
                // can lag behind highlight changes caused by a live update.
                unsafe {
                    EndMenu();
                }
                super::post(host, super::UI_MESSAGE, id);
            }
            return true;
        }
        if !matches!(
            id,
            super::ID_LOGIN
                | super::ID_REFRESH
                | super::ID_TRAY_MAINTENANCE
                | super::ID_TRAY_STARTUP
        ) {
            return false;
        }
        if state & MFS_DISABLED == 0 && (!keyboard || message.lParam & (1 << 30) == 0) {
            super::post(host, super::UI_MESSAGE, id);
        }
        return true;
    }
    false
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn tray_menu_uses_native_items_and_updates_checked_state() {
        let rows = vec![
            MenuRow::header("当前 Wi-Fi", "test & wifi"),
            MenuRow::separator(),
            MenuRow::toggle(1, "登录维持", true, false),
        ];
        let menu = MenuTheme::new(Appearance::System, rows.clone()).unwrap();
        let mut info = MENUITEMINFOW {
            cbSize: mem::size_of::<MENUITEMINFOW>() as u32,
            fMask: MIIM_FTYPE | MIIM_STATE,
            ..Default::default()
        };
        assert_ne!(unsafe { GetMenuItemInfoW(menu.menu, 2, 1, &mut info) }, 0);
        assert_eq!(info.fType & MFT_OWNERDRAW, 0);
        assert_eq!(info.fState & MFS_CHECKED, 0);
        let mut updated = rows;
        updated[2].checked = Some(true);
        menu.update_rows(updated);
        assert_ne!(
            unsafe { GetMenuState(menu.menu, 2, MF_BYPOSITION) } & MFS_CHECKED,
            0
        );
        assert_eq!(
            MenuRow::header("Wi-Fi", "a & b").text(),
            wide("Wi-Fi：a && b")
        );
    }

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
