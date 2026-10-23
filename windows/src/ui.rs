use crate::{
    app::{Command, RESULT_MESSAGE, View, Worker},
    domain::{Online, Preferences},
    platform::{
        network::{NETWORK_MESSAGE, NetworkEvents},
        wide,
    },
    strings,
};
use std::{ptr, sync::mpsc::TrySendError};
use windows_sys::Win32::{
    Foundation::*,
    Graphics::Gdi::*,
    System::{LibraryLoader::GetModuleHandleW, Threading::CreateMutexW},
    UI::{
        Controls::*, HiDpi::GetDpiForWindow, Input::KeyboardAndMouse::EnableWindow, Shell::*,
        WindowsAndMessaging::*,
    },
};
use zeroize::Zeroizing;
mod theme;

const HOST_CLASS: &str = "Alogin.Windows.Host";
const WINDOW_CLASS: &str = "Alogin.Windows.Window";
const TRAY_MESSAGE: u32 = WM_APP + 3;
const UI_MESSAGE: u32 = WM_APP + 4;
const OPEN_MESSAGE: u32 = WM_APP + 5;
const CLOSE_MESSAGE: u32 = WM_APP + 6;
const DPI_MESSAGE: u32 = WM_APP + 7;
const THEME_MESSAGE: u32 = WM_APP + 8;
const DEBOUNCE_TIMER: usize = 1;
const ID_LOGIN: usize = 101;
const ID_REFRESH: usize = 102;
const ID_ACCOUNTS: usize = 103;
const ID_SAVE: usize = 104;
const ID_DELETE: usize = 105;
const ID_SETTINGS: usize = 106;
const ID_SHOW: usize = 201;
const ID_EXIT: usize = 202;

struct Mutex(HANDLE);
impl Drop for Mutex {
    fn drop(&mut self) {
        unsafe {
            CloseHandle(self.0);
        }
    }
}

struct Controls {
    status: HWND,
    account_info: HWND,
    login: HWND,
    refresh: HWND,
    accounts: HWND,
    delete: HWND,
    username: HWND,
    password: HWND,
    save: HWND,
    targets: HWND,
    auto: HWND,
    settings: HWND,
    message: HWND,
    font: HFONT,
    children: Vec<(HWND, [i32; 4])>,
}

struct App {
    host: HWND,
    window: HWND,
    worker: Option<Worker>,
    events: Option<NetworkEvents>,
    controls: Option<Controls>,
    view: View,
    busy: bool,
    dirty_network: bool,
    initialized: bool,
    tray: bool,
    taskbar_created: u32,
}

pub fn run() -> Result<(), String> {
    let mutex = unsafe {
        CreateMutexW(
            ptr::null(),
            0,
            wide("Local\\top.steins.alogin.windows").as_ptr(),
        )
    };
    if mutex.is_null() {
        return Err(strings::MUTEX_FAILED.into());
    }
    let already_running = unsafe { GetLastError() } == ERROR_ALREADY_EXISTS;
    let _mutex = Mutex(mutex);
    if already_running {
        let host = unsafe { FindWindowW(wide(HOST_CLASS).as_ptr(), ptr::null()) };
        if !host.is_null() {
            unsafe {
                PostMessageW(host, OPEN_MESSAGE, 0, 0);
            }
            return Ok(());
        }
        return Err(strings::SINGLE_INSTANCE.into());
    }
    let instance = unsafe { GetModuleHandleW(ptr::null()) };
    let icon = crate::platform::icon::load(false);
    if icon.is_null() {
        return Err(strings::ICON_FAILED.into());
    }
    for (name, proc) in [
        (
            HOST_CLASS,
            Some(host_proc as unsafe extern "system" fn(_, _, _, _) -> _),
        ),
        (
            WINDOW_CLASS,
            Some(window_proc as unsafe extern "system" fn(_, _, _, _) -> _),
        ),
    ] {
        let name = wide(name);
        let class = WNDCLASSW {
            lpfnWndProc: proc,
            hInstance: instance,
            hIcon: icon,
            hCursor: unsafe { LoadCursorW(ptr::null_mut(), IDC_ARROW) },
            hbrBackground: (COLOR_WINDOW + 1) as usize as _,
            lpszClassName: name.as_ptr(),
            ..Default::default()
        };
        if unsafe { RegisterClassW(&class) } == 0 {
            return Err(strings::REGISTER_WINDOW_FAILED.into());
        }
    }
    let mut app = Box::new(App {
        host: ptr::null_mut(),
        window: ptr::null_mut(),
        worker: None,
        events: None,
        controls: None,
        view: View::default(),
        busy: true,
        dirty_network: false,
        initialized: false,
        tray: false,
        taskbar_created: unsafe { RegisterWindowMessageW(wide("TaskbarCreated").as_ptr()) },
    });
    let host = unsafe {
        CreateWindowExW(
            0,
            wide(HOST_CLASS).as_ptr(),
            wide(strings::APP).as_ptr(),
            WS_OVERLAPPED,
            0,
            0,
            0,
            0,
            ptr::null_mut(),
            ptr::null_mut(),
            instance,
            (&mut *app as *mut App).cast(),
        )
    };
    if host.is_null() {
        return Err(strings::HOST_FAILED.into());
    }
    app.host = host;
    let result = (|| {
        app.add_tray()?;
        app.worker = Some(Worker::start(host as usize)?);
        app.events = Some(NetworkEvents::subscribe(host)?);
        if !std::env::args().any(|arg| arg == "--background") {
            app.open_window()?;
        }
        let mut message = MSG::default();
        loop {
            let result = unsafe { GetMessageW(&mut message, ptr::null_mut(), 0, 0) };
            if result == -1 {
                return Err(strings::MESSAGE_LOOP_FAILED.into());
            }
            if result == 0 {
                break;
            }
            if app.window.is_null() || unsafe { IsDialogMessageW(app.window, &message) } == 0 {
                unsafe {
                    TranslateMessage(&message);
                    DispatchMessageW(&message);
                }
            }
        }
        Ok(())
    })();
    app.events.take();
    if let Some(mut worker) = app.worker.take() {
        worker.shutdown();
    }
    app.close_window();
    app.remove_tray();
    unsafe {
        DestroyWindow(host);
    }
    result
}

impl App {
    fn add_tray(&mut self) -> Result<(), String> {
        let mut data = self.tray_data();
        data.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP;
        data.uCallbackMessage = TRAY_MESSAGE;
        data.hIcon = crate::platform::icon::load(true);
        let tip = wide(strings::APP);
        data.szTip[..tip.len()].copy_from_slice(&tip);
        self.tray = unsafe { Shell_NotifyIconW(NIM_ADD, &data) } != 0;
        if self.tray {
            Ok(())
        } else {
            Err(strings::TRAY_FAILED.into())
        }
    }
    fn tray_data(&self) -> NOTIFYICONDATAW {
        NOTIFYICONDATAW {
            cbSize: std::mem::size_of::<NOTIFYICONDATAW>() as u32,
            hWnd: self.host,
            uID: 1,
            ..Default::default()
        }
    }
    fn remove_tray(&mut self) {
        if self.tray {
            unsafe {
                Shell_NotifyIconW(NIM_DELETE, &self.tray_data());
            }
            self.tray = false;
        }
    }
    fn open_window(&mut self) -> Result<(), String> {
        if !self.window.is_null() {
            unsafe {
                ShowWindow(self.window, SW_RESTORE);
                SetForegroundWindow(self.window);
            }
            return Ok(());
        }
        let instance = unsafe { GetModuleHandleW(ptr::null()) };
        self.window = unsafe {
            CreateWindowExW(
                WS_EX_CONTROLPARENT,
                wide(WINDOW_CLASS).as_ptr(),
                wide(strings::APP).as_ptr(),
                WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX,
                CW_USEDEFAULT,
                CW_USEDEFAULT,
                570,
                780,
                ptr::null_mut(),
                ptr::null_mut(),
                instance,
                self.host.cast(),
            )
        };
        if self.window.is_null() {
            return Err(strings::WINDOW_FAILED.into());
        }
        self.controls = Some(Controls::new(self.window)?);
        crate::platform::icon::apply(self.window);
        theme::refresh(self.window);
        self.resize_for_dpi();
        self.render(true);
        unsafe {
            ShowWindow(self.window, SW_SHOW);
            SetForegroundWindow(self.window);
        }
        Ok(())
    }
    fn close_window(&mut self) {
        if !self.window.is_null() {
            unsafe {
                DestroyWindow(self.window);
            }
            self.window = ptr::null_mut();
        }
        if let Some(controls) = self.controls.take() {
            unsafe {
                DeleteObject(controls.font);
            }
        }
    }
    fn resize_for_dpi(&mut self) {
        if self.window.is_null() {
            return;
        }
        let dpi = unsafe { GetDpiForWindow(self.window) }.max(96);
        let mut bounds = RECT {
            left: 0,
            top: 0,
            right: scale(860, dpi),
            bottom: scale(688, dpi),
        };
        unsafe {
            windows_sys::Win32::UI::HiDpi::AdjustWindowRectExForDpi(
                &mut bounds,
                WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX,
                0,
                WS_EX_CONTROLPARENT,
                dpi,
            );
            SetWindowPos(
                self.window,
                ptr::null_mut(),
                0,
                0,
                bounds.right - bounds.left,
                bounds.bottom - bounds.top,
                SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE,
            );
        }
        if let Some(controls) = &mut self.controls {
            controls.layout(dpi);
        }
    }
    fn submit(&mut self, command: Command) {
        if self.busy || self.view.fatal {
            return;
        }
        let Some(worker) = &self.worker else {
            return;
        };
        let invalidates_info = matches!(
            command,
            Command::Login | Command::Refresh | Command::NetworkChanged
        );
        match worker.commands.try_send(command) {
            Ok(()) => {
                self.busy = true;
                self.view.message = strings::BUSY.into();
                if invalidates_info {
                    self.view.account_info = Default::default();
                    self.view.online = Online::Unknown;
                }
            }
            Err(TrySendError::Disconnected(_)) => {
                self.view.message = strings::THREAD_STOPPED.into();
                self.view.fatal = true;
            }
            Err(TrySendError::Full(_)) => {
                self.dirty_network = true;
            }
        }
        self.render(false);
    }
    fn result(&mut self) {
        let Some(worker) = &self.worker else {
            return;
        };
        let mut latest = None;
        while let Ok(view) = worker.results.try_recv() {
            latest = Some(view);
        }
        let Some(view) = latest else {
            return;
        };
        let sync_form = !self.initialized
            || self.view.accounts != view.accounts
            || self.view.selected != view.selected
            || self.view.preferences.target_ssids != view.preferences.target_ssids
            || self.view.preferences.auto_login != view.preferences.auto_login;
        self.view = view;
        self.busy = false;
        self.initialized = true;
        self.render(sync_form);
        if self.dirty_network {
            self.dirty_network = false;
            unsafe {
                SetTimer(self.host, DEBOUNCE_TIMER, 1200, None);
            }
        }
    }
    fn render(&self, sync_form: bool) {
        let Some(c) = &self.controls else {
            return;
        };
        let network = &self.view.network;
        let ssid = network
            .ssid
            .as_deref()
            .unwrap_or(if network.ipv4.is_empty() {
                strings::DISCONNECTED
            } else {
                strings::SSID_UNAVAILABLE
            });
        let online = match self.view.online {
            Online::Yes => strings::LOGGED_IN,
            Online::No => strings::LOGGED_OUT,
            Online::Unknown => strings::UNKNOWN,
        };
        set_text(
            c.status,
            &strings::network_status(
                ssid,
                if network.ipv4.is_empty() {
                    "—"
                } else {
                    &network.ipv4
                },
                online,
            ),
        );
        set_text(c.message, &self.view.message);
        set_text(
            c.account_info,
            &strings::account_info(&self.view.account_info, self.view.online),
        );
        if sync_form {
            unsafe {
                SendMessageW(c.accounts, CB_RESETCONTENT, 0, 0);
            }
            for account in &self.view.accounts {
                unsafe {
                    SendMessageW(c.accounts, CB_ADDSTRING, 0, wide(account).as_ptr() as isize);
                }
            }
            let selected = self
                .view
                .selected
                .as_ref()
                .and_then(|name| self.view.accounts.iter().position(|a| a == name));
            unsafe {
                SendMessageW(c.accounts, CB_SETCURSEL, selected.unwrap_or(usize::MAX), 0);
            }
            set_text(c.username, self.view.selected.as_deref().unwrap_or(""));
            set_text(c.password, "");
            set_text(c.targets, &self.view.preferences.target_ssids.join("\r\n"));
            unsafe {
                SendMessageW(
                    c.auto,
                    BM_SETCHECK,
                    if self.view.preferences.auto_login {
                        BST_CHECKED
                    } else {
                        BST_UNCHECKED
                    } as usize,
                    0,
                );
            }
        }
        let enabled = !self.busy && !self.view.fatal && self.initialized;
        for control in [
            c.login, c.refresh, c.accounts, c.delete, c.username, c.password, c.save, c.targets,
            c.auto, c.settings,
        ] {
            unsafe {
                EnableWindow(control, enabled as i32);
            }
        }
        if self.view.selected.is_none() {
            unsafe {
                EnableWindow(c.login, 0);
                EnableWindow(c.delete, 0);
            }
        }
    }
    fn command(&mut self, id: usize, notification: u16) {
        if id == ID_SHOW {
            if let Err(error) = self.open_window() {
                show_error(&error);
            }
            return;
        }
        if id == ID_EXIT {
            unsafe {
                PostQuitMessage(0);
            }
            return;
        }
        if self.busy || self.view.fatal {
            return;
        }
        match id {
            ID_LOGIN => self.submit(Command::Login),
            ID_REFRESH => self.submit(Command::Refresh),
            ID_SAVE => {
                if let Some(c) = &self.controls {
                    let username = text(c.username).trim().to_owned();
                    let password = text(c.password);
                    set_text(c.password, "");
                    self.submit(Command::SaveAccount { username, password });
                }
            }
            ID_DELETE => {
                if let Some(name) = &self.view.selected {
                    // Explicit user action and recoverable by re-adding the account.
                    self.submit(Command::DeleteAccount(name.clone()));
                }
            }
            ID_ACCOUNTS if notification == CBN_SELCHANGE as u16 => {
                if let Some(c) = &self.controls {
                    let index = unsafe { SendMessageW(c.accounts, CB_GETCURSEL, 0, 0) };
                    if index >= 0 {
                        if let Some(name) = self.view.accounts.get(index as usize) {
                            self.submit(Command::SelectAccount(name.clone()));
                        }
                    }
                }
            }
            ID_SETTINGS => {
                if let Some(c) = &self.controls {
                    let mut target_ssids = Vec::new();
                    for line in text(c.targets)
                        .lines()
                        .map(str::trim)
                        .filter(|s| !s.is_empty())
                    {
                        if line.len() > 32 {
                            self.view.message = strings::SSID_TOO_LONG.into();
                            self.render(false);
                            return;
                        }
                        if !target_ssids.iter().any(|existing| existing == line) {
                            target_ssids.push(line.to_owned());
                        }
                    }
                    let auto_login =
                        unsafe { SendMessageW(c.auto, BM_GETCHECK, 0, 0) } == BST_CHECKED as isize;
                    self.submit(Command::SavePreferences(Preferences {
                        target_ssids,
                        auto_login,
                    }));
                }
            }
            _ => (),
        }
    }
}

// TrackPopupMenu runs a nested message loop: never hold an App borrow across it.
fn tray_menu(host: HWND, disabled: bool) {
    let menu = unsafe { CreatePopupMenu() };
    if menu.is_null() {
        return;
    }
    // Keep the menu background brush alive until DestroyMenu below.
    let menu_theme = theme::MenuTheme::new(menu);
    let dark = menu_theme.dark();
    for (id, label) in [
        (ID_SHOW, strings::SHOW),
        (ID_LOGIN, strings::LOGIN),
        (ID_REFRESH, strings::REFRESH),
        (ID_EXIT, strings::EXIT),
    ] {
        let mut flags = if (id == ID_LOGIN || id == ID_REFRESH) && disabled {
            MF_STRING | MF_GRAYED
        } else {
            MF_STRING
        };
        if dark {
            flags |= MF_OWNERDRAW;
        }
        unsafe {
            let mut text = wide(label);
            AppendMenuW(
                menu,
                flags,
                id,
                if dark { ptr::null() } else { text.as_ptr() },
            );
            if dark {
                let info = MENUITEMINFOW {
                    cbSize: std::mem::size_of::<MENUITEMINFOW>() as u32,
                    fMask: MIIM_STRING,
                    dwTypeData: text.as_mut_ptr(),
                    ..Default::default()
                };
                SetMenuItemInfoW(menu, id as u32, 0, &info);
            }
        }
    }
    let mut point = POINT::default();
    unsafe {
        GetCursorPos(&mut point);
        SetForegroundWindow(host);
    }
    let id = unsafe {
        TrackPopupMenu(
            menu,
            TPM_RETURNCMD | TPM_NONOTIFY | TPM_RIGHTBUTTON,
            point.x,
            point.y,
            0,
            host,
            ptr::null(),
        )
    };
    unsafe {
        DestroyMenu(menu);
        PostMessageW(host, WM_NULL, 0, 0);
    }
    if id != 0 {
        unsafe {
            PostMessageW(host, UI_MESSAGE, id as usize, 0);
        }
    }
}

impl Controls {
    fn new(parent: HWND) -> Result<Self, String> {
        let mut children = Vec::new();
        let mut add = |class: &str,
                       label: &str,
                       id: usize,
                       style: u32,
                       rect: [i32; 4]|
         -> Result<HWND, String> {
            let control = unsafe {
                CreateWindowExW(
                    if class == "EDIT" { WS_EX_CLIENTEDGE } else { 0 },
                    wide(class).as_ptr(),
                    wide(label).as_ptr(),
                    WS_CHILD | WS_VISIBLE | WS_CLIPSIBLINGS | style,
                    0,
                    0,
                    0,
                    0,
                    parent,
                    id as HMENU,
                    GetModuleHandleW(ptr::null()),
                    ptr::null(),
                )
            };
            if control.is_null() {
                return Err(strings::CONTROL_FAILED.into());
            }
            theme::install_control(control, parent, class)?;
            children.push((control, rect));
            Ok(control)
        };
        let status = add("STATIC", "", 0, 0, [24, 20, 492, 74])?;
        // Earlier siblings sit above later ones. Keep the text above the group
        // box so WS_CLIPSIBLINGS cannot clip it out of the visible region.
        let account_info = add("STATIC", "", 0, 0, [556, 54, 264, 150])?;
        add(
            "BUTTON",
            strings::ACCOUNT_TITLE,
            0,
            BS_GROUPBOX as u32,
            [540, 20, 296, 204],
        )?;
        let login = add(
            "BUTTON",
            strings::LOGIN,
            ID_LOGIN,
            WS_TABSTOP | BS_DEFPUSHBUTTON as u32,
            [24, 104, 118, 34],
        )?;
        let refresh = add(
            "BUTTON",
            strings::REFRESH,
            ID_REFRESH,
            WS_TABSTOP,
            [154, 104, 118, 34],
        )?;
        add("STATIC", strings::ACCOUNTS, 0, 0, [24, 158, 350, 22])?;
        let accounts = add(
            "COMBOBOX",
            "",
            ID_ACCOUNTS,
            WS_TABSTOP | WS_VSCROLL | CBS_DROPDOWNLIST as u32,
            [24, 184, 358, 180],
        )?;
        let delete = add(
            "BUTTON",
            strings::DELETE,
            ID_DELETE,
            WS_TABSTOP,
            [394, 182, 122, 30],
        )?;
        add("STATIC", strings::USERNAME, 0, 0, [24, 230, 210, 20])?;
        add("STATIC", strings::PASSWORD, 0, 0, [254, 230, 262, 20])?;
        let username = add(
            "EDIT",
            "",
            0,
            WS_TABSTOP | ES_AUTOHSCROLL as u32,
            [24, 254, 216, 30],
        )?;
        let password = add(
            "EDIT",
            "",
            0,
            WS_TABSTOP | ES_AUTOHSCROLL as u32 | ES_PASSWORD as u32,
            [254, 254, 262, 30],
        )?;
        let save = add(
            "BUTTON",
            strings::SAVE,
            ID_SAVE,
            WS_TABSTOP,
            [24, 296, 148, 32],
        )?;
        add("STATIC", strings::TARGETS, 0, 0, [24, 350, 492, 22])?;
        let targets = add(
            "EDIT",
            "",
            0,
            WS_TABSTOP
                | WS_VSCROLL
                | ES_MULTILINE as u32
                | ES_AUTOVSCROLL as u32
                | ES_WANTRETURN as u32,
            [24, 378, 492, 78],
        )?;
        let auto = add(
            "BUTTON",
            strings::AUTO,
            0,
            WS_TABSTOP | BS_AUTOCHECKBOX as u32,
            [24, 470, 492, 26],
        )?;
        let settings = add(
            "BUTTON",
            strings::SAVE_SETTINGS,
            ID_SETTINGS,
            WS_TABSTOP,
            [24, 510, 148, 32],
        )?;
        let message = add("STATIC", "", 0, 0, [24, 564, 492, 56])?;
        add("STATIC", strings::FOOTER, 0, 0, [24, 644, 492, 24])?;
        unsafe {
            SendMessageW(username, EM_SETLIMITTEXT, 128, 0);
            SendMessageW(password, EM_SETLIMITTEXT, 1024, 0);
            SendMessageW(targets, EM_SETLIMITTEXT, 8192, 0);
        }
        Ok(Self {
            status,
            account_info,
            login,
            refresh,
            accounts,
            delete,
            username,
            password,
            save,
            targets,
            auto,
            settings,
            message,
            font: ptr::null_mut(),
            children,
        })
    }
    fn layout(&mut self, dpi: u32) {
        let font = unsafe {
            CreateFontW(
                -scale(14, dpi),
                0,
                0,
                0,
                FW_NORMAL as i32,
                0,
                0,
                0,
                DEFAULT_CHARSET as u32,
                OUT_DEFAULT_PRECIS as u32,
                CLIP_DEFAULT_PRECIS as u32,
                CLEARTYPE_QUALITY as u32,
                DEFAULT_PITCH as u32,
                wide("Microsoft YaHei UI").as_ptr(),
            )
        };
        for (control, [x, y, width, height]) in &self.children {
            unsafe {
                SetWindowPos(
                    *control,
                    ptr::null_mut(),
                    scale(*x, dpi),
                    scale(*y, dpi),
                    scale(*width, dpi),
                    scale(*height, dpi),
                    SWP_NOZORDER | SWP_NOACTIVATE,
                );
                SendMessageW(*control, WM_SETFONT, font as usize, 1);
            }
        }
        if !self.font.is_null() {
            unsafe {
                DeleteObject(self.font);
            }
        }
        self.font = font;
    }
}

unsafe extern "system" fn host_proc(
    window: HWND,
    message: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    if (message == WM_DRAWITEM || message == WM_MEASUREITEM) && lparam != 0 {
        if let Some(result) = theme::menu_message(message, lparam) {
            return result;
        }
    }
    if message == WM_NCCREATE {
        let create = unsafe { &*(lparam as *const CREATESTRUCTW) };
        unsafe {
            SetWindowLongPtrW(window, GWLP_USERDATA, create.lpCreateParams as isize);
        }
        return 1;
    }
    let app = unsafe { GetWindowLongPtrW(window, GWLP_USERDATA) } as *mut App;
    if app.is_null() {
        return unsafe { DefWindowProcW(window, message, wparam, lparam) };
    }
    // Only dispatched application messages borrow App; nested system messages use DefWindowProcW.
    match message {
        RESULT_MESSAGE => {
            unsafe {
                (*app).result();
            }
            0
        }
        NETWORK_MESSAGE => {
            unsafe {
                SetTimer(window, DEBOUNCE_TIMER, 1200, None);
            }
            0
        }
        WM_TIMER if wparam == DEBOUNCE_TIMER => {
            unsafe {
                KillTimer(window, DEBOUNCE_TIMER);
            }
            let app = unsafe { &mut *app };
            if app.busy {
                app.dirty_network = true;
            } else {
                app.submit(Command::NetworkChanged);
            }
            0
        }
        OPEN_MESSAGE => {
            if let Err(error) = unsafe { (*app).open_window() } {
                show_error(&error);
            }
            0
        }
        CLOSE_MESSAGE => {
            unsafe {
                (*app).close_window();
            }
            0
        }
        DPI_MESSAGE => {
            unsafe {
                (*app).resize_for_dpi();
                if !(*app).window.is_null() {
                    crate::platform::icon::apply((*app).window);
                }
            }
            0
        }
        WM_SETTINGCHANGE | WM_THEMECHANGED | WM_SYSCOLORCHANGE => {
            unsafe {
                PostMessageW(window, THEME_MESSAGE, 0, 0);
            }
            0
        }
        THEME_MESSAGE => {
            let visible = unsafe { (*app).window };
            if !visible.is_null() {
                theme::refresh(visible);
            }
            0
        }
        UI_MESSAGE => {
            unsafe {
                (*app).command(wparam & 0xffff, (wparam >> 16) as u16);
            }
            0
        }
        TRAY_MESSAGE => {
            match lparam as u32 {
                WM_LBUTTONUP | WM_LBUTTONDBLCLK => {
                    if let Err(error) = unsafe { (*app).open_window() } {
                        show_error(&error);
                    }
                }
                WM_RBUTTONUP | WM_CONTEXTMENU => {
                    tray_menu(window, unsafe { (*app).busy || (*app).view.fatal })
                }
                _ => (),
            }
            0
        }
        WM_POWERBROADCAST if wparam == 0x12 => {
            unsafe {
                SetTimer(window, DEBOUNCE_TIMER, 1500, None);
            }
            1
        }
        _ if message != 0 && message == unsafe { (*app).taskbar_created } => {
            // Explorer restart discards all previous notification icons.
            unsafe {
                (*app).tray = false;
            }
            if let Err(error) = unsafe { (*app).add_tray() } {
                show_error(&error);
            }
            0
        }
        _ => unsafe { DefWindowProcW(window, message, wparam, lparam) },
    }
}

unsafe extern "system" fn window_proc(
    window: HWND,
    message: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    if message == WM_NCCREATE {
        let create = unsafe { &*(lparam as *const CREATESTRUCTW) };
        theme::attach(window, create.lpCreateParams.cast());
        return 1;
    }
    if message == WM_NCDESTROY {
        let result = unsafe { DefWindowProcW(window, message, wparam, lparam) };
        theme::detach(window);
        return result;
    }
    if let Some(result) = theme::message(window, message, wparam, lparam) {
        return result;
    }
    let host = theme::host(window);
    match message {
        WM_SETTINGCHANGE | WM_THEMECHANGED | WM_SYSCOLORCHANGE => {
            unsafe {
                PostMessageW(host, THEME_MESSAGE, 0, 0);
            }
            0
        }
        WM_COMMAND => {
            unsafe {
                PostMessageW(host, UI_MESSAGE, wparam, lparam);
            }
            0
        }
        WM_CLOSE => {
            unsafe {
                PostMessageW(host, CLOSE_MESSAGE, 0, 0);
            }
            0
        }
        WM_DPICHANGED => {
            let bounds = unsafe { &*(lparam as *const RECT) };
            unsafe {
                SetWindowPos(
                    window,
                    ptr::null_mut(),
                    bounds.left,
                    bounds.top,
                    bounds.right - bounds.left,
                    bounds.bottom - bounds.top,
                    SWP_NOZORDER | SWP_NOACTIVATE,
                );
                PostMessageW(host, DPI_MESSAGE, 0, 0);
            }
            0
        }
        _ => unsafe { DefWindowProcW(window, message, wparam, lparam) },
    }
}

fn scale(value: i32, dpi: u32) -> i32 {
    ((value as i64 * dpi as i64 + 48) / 96) as i32
}
fn set_text(control: HWND, value: &str) {
    unsafe {
        SetWindowTextW(control, wide(value).as_ptr());
    }
}
fn text(control: HWND) -> Zeroizing<String> {
    let size = unsafe { GetWindowTextLengthW(control) }.max(0) as usize;
    let mut buffer = Zeroizing::new(vec![0u16; size + 1]);
    let read = unsafe { GetWindowTextW(control, buffer.as_mut_ptr(), buffer.len() as i32) };
    Zeroizing::new(String::from_utf16_lossy(&buffer[..read.max(0) as usize]))
}
pub fn show_error(message: &str) {
    unsafe {
        MessageBoxW(
            ptr::null_mut(),
            wide(message).as_ptr(),
            wide(strings::APP).as_ptr(),
            MB_OK | MB_ICONERROR,
        );
    }
}
