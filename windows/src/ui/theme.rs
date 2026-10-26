//! Event-driven Windows theme adaptation for Slint and the native tray menu.
use super::{AppTheme, MainWindow};
use crate::{domain::Appearance, platform::wide};
use slint::ComponentHandle;
use std::{
    cell::{Cell, RefCell},
    mem, ptr,
    rc::Rc,
};
use windows_sys::Win32::{
    Foundation::*,
    Graphics::{Dwm::*, Gdi::*},
    System::{Registry::*, Threading::GetCurrentThreadId},
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
fn fill(dc: HDC, rect: &RECT, color: COLORREF) {
    unsafe {
        let brush = CreateSolidBrush(color);
        FillRect(dc, rect, brush);
        DeleteObject(brush);
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
    fn height(&self) -> i32 {
        if self.separator {
            8
        } else if self.detail.is_some() {
            54
        } else {
            28
        }
    }
}

struct MenuItem {
    row: RefCell<MenuRow>,
    palette: Palette,
    dpi: u32,
    font: HFONT,
    width: i32,
    rounded_popup: Rc<Cell<HWND>>,
}
pub struct MenuTheme {
    menu: HMENU,
    background: HBRUSH,
    font: HFONT,
    // The Vec allocation stays fixed while native itemData pointers reference it.
    _items: Vec<MenuItem>,
}
impl MenuTheme {
    pub fn new(appearance: Appearance, rows: Vec<MenuRow>) -> Option<Self> {
        let mut point = POINT::default();
        let mut dpi = 96;
        let mut dpi_y = 96;
        unsafe {
            GetCursorPos(&mut point);
            windows_sys::Win32::UI::HiDpi::GetDpiForMonitor(
                MonitorFromPoint(point, MONITOR_DEFAULTTONEAREST),
                windows_sys::Win32::UI::HiDpi::MDT_EFFECTIVE_DPI,
                &mut dpi,
                &mut dpi_y,
            );
        }
        Self::with_palette(Palette::for_appearance(appearance), rows, dpi.max(96))
    }
    fn with_palette(palette: Palette, rows: Vec<MenuRow>, dpi: u32) -> Option<Self> {
        let menu = unsafe { CreatePopupMenu() };
        if menu.is_null() {
            return None;
        }
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
        let mut result = Self {
            menu,
            font,
            background: unsafe { CreateSolidBrush(palette.card) },
            _items: Vec::new(),
        };
        // Measure the actual system menu font, including non-ASCII SSIDs.
        let dc = unsafe { GetDC(ptr::null_mut()) };
        let saved = unsafe { SaveDC(dc) };
        unsafe {
            SelectObject(dc, menu_font(font));
        }
        let mut width = super::scale(200, dpi);
        for row in &rows {
            for text in std::iter::once(&row.label).chain(row.detail.iter()) {
                let mut rect = RECT::default();
                unsafe {
                    DrawTextW(
                        dc,
                        wide(text).as_ptr(),
                        -1,
                        &mut rect,
                        DT_CALCRECT | DT_SINGLELINE | DT_NOPREFIX,
                    );
                }
                width = width.max(
                    rect.right + super::scale(if row.checked.is_some() { 80 } else { 32 }, dpi),
                );
            }
        }
        unsafe {
            RestoreDC(dc, saved);
            ReleaseDC(ptr::null_mut(), dc);
        }
        width = width.min(super::scale(320, dpi));
        let rounded_popup = Rc::new(Cell::new(ptr::null_mut()));
        result._items = rows
            .into_iter()
            .map(|row| MenuItem {
                row: RefCell::new(row),
                palette,
                dpi,
                font,
                width,
                rounded_popup: rounded_popup.clone(),
            })
            .collect();
        let info = MENUINFO {
            cbSize: mem::size_of::<MENUINFO>() as u32,
            fMask: MIM_BACKGROUND | MIM_STYLE,
            dwStyle: MNS_NOCHECK,
            hbrBack: result.background,
            ..Default::default()
        };
        unsafe {
            SetMenuInfo(menu, &info);
        }
        for (index, item) in result._items.iter().enumerate() {
            let row = item.row.borrow();
            let mut text = wide(&match &row.detail {
                Some(detail) => format!("{}：{}", row.label, detail),
                None => row.label.clone(),
            });
            // Retain native strings and checked/disabled semantics for accessibility.
            let info = MENUITEMINFOW {
                cbSize: mem::size_of::<MENUITEMINFOW>() as u32,
                fMask: MIIM_FTYPE | MIIM_ID | MIIM_STATE | MIIM_DATA | MIIM_STRING,
                fType: MFT_OWNERDRAW | if row.separator { MFT_SEPARATOR } else { 0 },
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
                dwItemData: item as *const MenuItem as usize,
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
        if rows.len() != self._items.len() {
            return;
        }
        let mut changed = false;
        for (index, (item, row)) in self._items.iter().zip(rows).enumerate() {
            if *item.row.borrow() == row {
                continue;
            }
            let mut text = wide(&match &row.detail {
                Some(detail) => format!("{}：{}", row.label, detail),
                None => row.label.clone(),
            });
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
            // Release the row borrow before native dispatch can request a repaint.
            *item.row.borrow_mut() = row;
            unsafe {
                SetMenuItemInfoW(self.menu, index as u32, 1, &info);
            }
            changed = true;
        }
        if changed {
            let popup = self.popup_window();
            if popup.is_null() {
                return;
            }
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

// Apply the region during the first owner-draw pass, when the popup HWND is
// available. Set the guard before SetWindowRgn can re-enter native painting.
fn round_menu_popup(item: &MenuItem, menu: HMENU) {
    if menu.is_null() || !item.rounded_popup.get().is_null() {
        return;
    }
    let popup = menu_window(menu);
    if popup.is_null() {
        return;
    }
    // Remove the classic frame and the Windows 11 compositor outline. Keep
    // native menu tracking and the separate owner-drawn group separators.
    item.rounded_popup.set(popup);
    unsafe {
        let style = GetWindowLongPtrW(popup, GWL_STYLE);
        let ex_style = GetWindowLongPtrW(popup, GWL_EXSTYLE);
        let borderless = style & !((WS_BORDER | WS_DLGFRAME) as isize);
        let borderless_ex =
            ex_style & !((WS_EX_WINDOWEDGE | WS_EX_CLIENTEDGE | WS_EX_DLGMODALFRAME) as isize);
        if borderless != style || borderless_ex != ex_style {
            SetWindowLongPtrW(popup, GWL_STYLE, borderless);
            SetWindowLongPtrW(popup, GWL_EXSTYLE, borderless_ex);
            SetWindowPos(
                popup,
                ptr::null_mut(),
                0,
                0,
                0,
                0,
                SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE,
            );
        }
        let border = DWMWA_COLOR_NONE;
        DwmSetWindowAttribute(
            popup,
            DWMWA_BORDER_COLOR as u32,
            (&border as *const u32).cast(),
            mem::size_of_val(&border) as u32,
        );
    }
    let mut rect = RECT::default();
    if unsafe { GetWindowRect(popup, &mut rect) } == 0 {
        item.rounded_popup.set(ptr::null_mut());
        return;
    }
    let diameter = super::scale(16, item.dpi);
    let region = unsafe {
        CreateRoundRectRgn(
            0,
            0,
            rect.right - rect.left + 1,
            rect.bottom - rect.top + 1,
            diameter,
            diameter,
        )
    };
    if region.is_null() {
        item.rounded_popup.set(ptr::null_mut());
        return;
    }
    if unsafe { SetWindowRgn(popup, region, 1) } == 0 {
        unsafe {
            DeleteObject(region);
        }
        item.rounded_popup.set(ptr::null_mut());
    }
    // On success Windows owns and eventually deletes the region.
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
        // Destroy the menu before releasing any item data, font or background brush.
        unsafe {
            DestroyMenu(self.menu);
            DeleteObject(self.background);
            if !self.font.is_null() {
                DeleteObject(self.font);
            }
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
fn menu_font(font: HFONT) -> HGDIOBJ {
    if font.is_null() {
        unsafe { GetStockObject(DEFAULT_GUI_FONT) }
    } else {
        font
    }
}
fn menu_text(dc: HDC, text: &str, mut rect: RECT, color: COLORREF) {
    unsafe {
        SetTextColor(dc, color);
        DrawTextW(
            dc,
            wide(text).as_ptr(),
            -1,
            &mut rect,
            DT_SINGLELINE | DT_VCENTER | DT_END_ELLIPSIS | DT_NOPREFIX,
        );
    }
}
fn menu_pill(dc: HDC, rect: RECT, color: COLORREF, round: bool) {
    unsafe {
        let brush = CreateSolidBrush(color);
        let old_brush = SelectObject(dc, brush);
        let old_pen = SelectObject(dc, GetStockObject(NULL_PEN));
        if round {
            RoundRect(
                dc,
                rect.left,
                rect.top,
                rect.right,
                rect.bottom,
                rect.bottom - rect.top,
                rect.bottom - rect.top,
            );
        } else {
            Ellipse(dc, rect.left, rect.top, rect.right, rect.bottom);
        }
        SelectObject(dc, old_brush);
        SelectObject(dc, old_pen);
        DeleteObject(brush);
    }
}
fn draw_menu_item(item: &MenuItem, draw: &DRAWITEMSTRUCT) {
    round_menu_popup(item, draw.hwndItem as HMENU);
    let p = item.palette;
    let s = |value| super::scale(value, item.dpi);
    let row = item.row.borrow().clone();
    let selected = row.enabled && draw.itemState & ODS_SELECTED != 0;
    let disabled = !row.enabled || draw.itemState & ODS_DISABLED != 0;
    let background = if selected { p.hover } else { p.card };
    let foreground = if disabled {
        p.disabled
    } else if selected && p.high_contrast {
        p.accent_text
    } else {
        p.text
    };
    let saved = unsafe { SaveDC(draw.hDC) };
    fill(draw.hDC, &draw.rcItem, p.card);
    let mut rect = draw.rcItem;
    rect.left += s(6);
    rect.right -= s(6);
    if selected {
        rect.top += s(2);
        rect.bottom -= s(2);
        if p.high_contrast {
            fill(draw.hDC, &rect, background);
        } else {
            unsafe {
                let brush = CreateSolidBrush(background);
                let old_brush = SelectObject(draw.hDC, brush);
                let old_pen = SelectObject(draw.hDC, GetStockObject(NULL_PEN));
                RoundRect(
                    draw.hDC,
                    rect.left,
                    rect.top,
                    rect.right,
                    rect.bottom,
                    s(8),
                    s(8),
                );
                SelectObject(draw.hDC, old_brush);
                SelectObject(draw.hDC, old_pen);
                DeleteObject(brush);
            }
        }
    }
    rect.left = draw.rcItem.left + s(14);
    rect.right = draw.rcItem.right - s(14);
    unsafe {
        SelectObject(draw.hDC, menu_font(item.font));
        SetBkMode(draw.hDC, TRANSPARENT as i32);
    }
    if row.separator {
        rect.top = (rect.top + rect.bottom) / 2;
        rect.bottom = rect.top + s(1);
        fill(draw.hDC, &rect, p.border);
    } else if let Some(detail) = &row.detail {
        rect.top = draw.rcItem.top + s(8);
        rect.bottom = rect.top + s(22);
        menu_text(draw.hDC, &row.label, rect, p.disabled);
        rect.top = rect.bottom;
        rect.bottom = draw.rcItem.bottom - s(8);
        menu_text(draw.hDC, detail, rect, p.text);
    } else {
        if let Some(checked) = row.checked {
            let cy = (rect.top + rect.bottom) / 2;
            let track = RECT {
                left: rect.right - s(30),
                top: cy - s(8),
                right: rect.right,
                bottom: cy + s(8),
            };
            let ink = if disabled {
                p.disabled
            } else if selected && p.high_contrast {
                p.accent_text
            } else if checked {
                p.accent
            } else {
                p.disabled
            };
            menu_pill(draw.hDC, track, ink, true);
            if !checked {
                menu_pill(
                    draw.hDC,
                    RECT {
                        left: track.left + s(1),
                        right: track.right - s(1),
                        top: track.top + s(1),
                        bottom: track.bottom - s(1),
                    },
                    background,
                    true,
                );
            }
            let left = if checked {
                track.right - s(13)
            } else {
                track.left + s(3)
            };
            menu_pill(
                draw.hDC,
                RECT {
                    left,
                    top: cy - s(5),
                    right: left + s(10),
                    bottom: cy + s(5),
                },
                if checked {
                    if selected && p.high_contrast {
                        p.hover
                    } else if disabled {
                        p.card
                    } else {
                        p.accent_text
                    }
                } else {
                    ink
                },
                false,
            );
            rect.right = track.left - s(12);
        }
        menu_text(draw.hDC, &row.label, rect, foreground);
    }
    unsafe {
        RestoreDC(draw.hDC, saved);
    }
}
pub fn menu_message(message: u32, lparam: LPARAM) -> Option<LRESULT> {
    match message {
        WM_MEASUREITEM => {
            let measure = unsafe { &mut *(lparam as *mut MEASUREITEMSTRUCT) };
            if measure.CtlType != ODT_MENU || measure.itemData == 0 {
                return None;
            }
            let item = unsafe { &*(measure.itemData as *const MenuItem) };
            measure.itemWidth = item.width as u32;
            measure.itemHeight = super::scale(item.row.borrow().height(), item.dpi) as u32;
            Some(1)
        }
        WM_DRAWITEM => {
            let draw = unsafe { &*(lparam as *const DRAWITEMSTRUCT) };
            if draw.CtlType != ODT_MENU || draw.itemData == 0 {
                return None;
            }
            draw_menu_item(unsafe { &*(draw.itemData as *const MenuItem) }, draw);
            Some(1)
        }
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn tray_menu_preserves_native_semantics_renders_scaled_themes_and_releases_resources() {
        let directory = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("target/ui-previews");
        std::fs::create_dir_all(&directory).unwrap();
        for (mode, mut p) in [
            ("light", Palette::for_appearance(Appearance::Light)),
            ("dark", Palette::dark()),
            ("contrast", Palette::dark()),
        ] {
            if mode == "contrast" {
                p.high_contrast = true;
                p.card = rgb(0, 0, 0);
                p.text = rgb(255, 255, 255);
                p.disabled = p.text;
                p.border = p.text;
                p.hover = rgb(255, 255, 0);
                p.accent = p.hover;
                p.accent_text = p.card;
            }
            for dpi in [96, 144] {
                for (state, name) in [
                    ("normal", "bjut_wifi"),
                    ("long", "校园 & Wi-Fi 名称较长时应完整测量并在必要时省略"),
                    ("missing", crate::strings::SSID_UNAVAILABLE),
                ] {
                    let mut rows = vec![
                        MenuRow::header(crate::strings::TRAY_WIFI, name),
                        MenuRow::separator(),
                        MenuRow::action(super::super::ID_SHOW, crate::strings::SHOW, true),
                        MenuRow::action(super::super::ID_REFRESH, crate::strings::REFRESH, false),
                        MenuRow::separator(),
                        MenuRow::toggle(
                            super::super::ID_TRAY_MAINTENANCE,
                            crate::strings::MAINTENANCE_PAGE,
                            true,
                            true,
                        ),
                        MenuRow::toggle(
                            super::super::ID_TRAY_STARTUP,
                            crate::strings::STARTUP,
                            true,
                            false,
                        ),
                        MenuRow::separator(),
                        MenuRow::action(super::super::ID_EXIT, crate::strings::EXIT, true),
                    ];
                    if state == "missing" {
                        rows[6] = MenuRow::action(
                            super::super::ID_TRAY_STARTUP,
                            crate::strings::TRAY_STARTUP_UNKNOWN,
                            false,
                        );
                    }
                    let theme = MenuTheme::with_palette(p, rows, dpi).unwrap();
                    let mut label = [0u16; 128];
                    let mut info = MENUITEMINFOW {
                        cbSize: mem::size_of::<MENUITEMINFOW>() as u32,
                        fMask: MIIM_STRING | MIIM_STATE | MIIM_DATA,
                        dwTypeData: label.as_mut_ptr(),
                        cch: label.len() as u32,
                        ..Default::default()
                    };
                    unsafe {
                        assert_ne!(GetMenuItemInfoW(theme.menu, 5, 1, &mut info), 0);
                    }
                    assert_eq!(
                        String::from_utf16_lossy(&label[..info.cch as usize]),
                        crate::strings::MAINTENANCE_PAGE
                    );
                    assert_ne!(info.fState & MFS_CHECKED, 0);
                    assert_eq!(info.fState & MFS_DISABLED, 0);
                    let width = theme._items[0].width;
                    assert!(
                        (super::super::scale(200, dpi)..=super::super::scale(320, dpi))
                            .contains(&width)
                    );
                    let height: i32 = theme
                        ._items
                        .iter()
                        .map(|i| super::super::scale(i.row.borrow().height(), dpi))
                        .sum();
                    unsafe {
                        let dc = CreateCompatibleDC(ptr::null_mut());
                        let bitmap_info = BITMAPINFO {
                            bmiHeader: BITMAPINFOHEADER {
                                biSize: mem::size_of::<BITMAPINFOHEADER>() as u32,
                                biWidth: width,
                                biHeight: -height,
                                biPlanes: 1,
                                biBitCount: 32,
                                biCompression: BI_RGB,
                                ..Default::default()
                            },
                            ..Default::default()
                        };
                        let mut bits = ptr::null_mut();
                        let bitmap = CreateDIBSection(
                            dc,
                            &bitmap_info,
                            DIB_RGB_COLORS,
                            &mut bits,
                            ptr::null_mut(),
                            0,
                        );
                        assert!(!dc.is_null() && !bitmap.is_null() && !bits.is_null());
                        let old = SelectObject(dc, bitmap);
                        let mut top = 0;
                        for (index, item) in theme._items.iter().enumerate() {
                            let data = item as *const MenuItem as usize;
                            let mut measure = MEASUREITEMSTRUCT {
                                CtlType: ODT_MENU,
                                itemData: data,
                                ..Default::default()
                            };
                            assert_eq!(
                                menu_message(
                                    WM_MEASUREITEM,
                                    (&mut measure as *mut MEASUREITEMSTRUCT) as LPARAM
                                ),
                                Some(1)
                            );
                            let draw = DRAWITEMSTRUCT {
                                CtlType: ODT_MENU,
                                itemData: data,
                                hDC: dc,
                                itemState: if index == 5 { ODS_SELECTED } else { 0 },
                                rcItem: RECT {
                                    left: 0,
                                    right: width,
                                    top,
                                    bottom: top + measure.itemHeight as i32,
                                },
                                ..Default::default()
                            };
                            assert_eq!(
                                menu_message(
                                    WM_DRAWITEM,
                                    (&draw as *const DRAWITEMSTRUCT) as LPARAM
                                ),
                                Some(1)
                            );
                            top = draw.rcItem.bottom;
                        }
                        assert_eq!(GetPixel(dc, 0, 0), p.card);
                        GdiFlush();
                        let bytes = std::slice::from_raw_parts(
                            bits.cast::<u8>(),
                            (width * height * 4) as usize,
                        );
                        let mut rgba: Vec<u8> = bytes
                            .as_chunks::<4>()
                            .0
                            .iter()
                            .flat_map(|pixel| [pixel[2], pixel[1], pixel[0], 255])
                            .collect();
                        let diameter = super::super::scale(16, dpi);
                        let region =
                            CreateRoundRectRgn(0, 0, width + 1, height + 1, diameter, diameter);
                        for (index, pixel) in rgba.as_chunks_mut::<4>().0.iter_mut().enumerate() {
                            let (x, y) = (index as i32 % width, index as i32 / width);
                            if (x < diameter || x >= width - diameter)
                                && (y < diameter || y >= height - diameter)
                                && PtInRegion(region, x, y) == 0
                            {
                                pixel[3] = 0;
                            }
                        }
                        DeleteObject(region);
                        image::save_buffer(
                            directory.join(format!("{mode}-tray-{state}-{dpi}.png")),
                            &rgba,
                            width as u32,
                            height as u32,
                            image::ColorType::Rgba8,
                        )
                        .unwrap();
                        SelectObject(dc, old);
                        DeleteObject(bitmap);
                        DeleteDC(dc);
                        let (menu, brush) = (theme.menu, theme.background);
                        drop(theme);
                        assert_eq!(IsMenu(menu), 0);
                        assert_eq!(GetObjectType(brush), 0);
                        // GDI may share identical logical fonts with other windows;
                        // a released font handle need not become immediately invalid.
                    }
                }
            }
        }
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
