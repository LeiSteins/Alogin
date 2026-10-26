use crate::{
    app::{Command, RESULT_MESSAGE, View, Worker},
    domain::{
        Appearance, DeviceLogoutRequest, HomeAction, Network, NetworkKind, Online, Preferences,
    },
    platform::{
        network::{NETWORK_MESSAGE, NetworkEvents},
        wide,
    },
    strings,
};
use raw_window_handle::{HasWindowHandle, RawWindowHandle};
use slint::winit_030::{EventResult, WinitWindowAccessor};
use slint::{ComponentHandle, Model, ModelRc, VecModel};
use std::{
    cell::RefCell,
    ptr,
    rc::Rc,
    sync::{
        atomic::{AtomicU32, Ordering},
        mpsc::TrySendError,
    },
};
use windows_sys::Win32::{
    Foundation::*,
    System::{LibraryLoader::GetModuleHandleW, Threading::CreateMutexW},
    UI::{Shell::*, WindowsAndMessaging::*},
};
use zeroize::Zeroizing;
mod repaint;
#[cfg(test)]
mod tests;
mod theme;
slint::include_modules!();
const HOST_CLASS: &str = "Alogin.Windows.Host";
const TRAY_MESSAGE: u32 = WM_APP + 3;
const UI_MESSAGE: u32 = WM_APP + 4;
const OPEN_MESSAGE: u32 = WM_APP + 5;
const CLOSE_MESSAGE: u32 = WM_APP + 6;
const THEME_MESSAGE: u32 = WM_APP + 8;
const ERROR_MESSAGE: u32 = WM_APP + 9;
const DEBOUNCE_TIMER: usize = 1;
const NETWORK_REFRESH_DEBOUNCE_MS: u32 = 250;
const ID_LOGIN: usize = 101;
const ID_REFRESH: usize = 102;
const ID_ACCOUNTS: usize = 103;
const ID_SAVE: usize = 104;
const ID_DELETE: usize = 105;
const ID_AUTO_LOGIN: usize = 106;
const ID_ADD_CURRENT_WIFI: usize = 107;
const ID_ADD_TARGET: usize = 108;
const ID_DELETE_TARGET: usize = 109;
const ID_HOME_ACTION: usize = 110;
const ID_LOGOUT_DEVICE: usize = 111;
const ID_APPEARANCE: usize = 112;
const ID_SHOW: usize = 201;
const ID_EXIT: usize = 202;
static TASKBAR_CREATED: AtomicU32 = AtomicU32::new(0);

struct Mutex(HANDLE);
impl Drop for Mutex {
    fn drop(&mut self) {
        unsafe {
            CloseHandle(self.0);
        }
    }
}

// Native callbacks enqueue work instead of borrowing UI state during reentrant
// Windows dispatch (window creation, painting, message boxes and popup menus).
thread_local! {
    static APP: RefCell<std::rc::Weak<RefCell<App>>> = const { RefCell::new(std::rc::Weak::new()) };
}
struct PendingPreferences {
    selected: Option<String>,
    editor: bool,
}
struct App {
    host: HWND,
    window: Option<MainWindow>,
    worker: Option<Worker>,
    events: Option<NetworkEvents>,
    view: View,
    busy: bool,
    dirty_network: bool,
    initialized: bool,
    user_action: bool,
    pending_error: Option<String>,
    pending_preferences: Option<PendingPreferences>,
    tray: bool,
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
    slint::BackendSelector::new()
        .backend_name("winit".into())
        .renderer_name("software".into())
        .select()
        .map_err(|_| strings::WINDOW_FAILED)?;
    let name = wide(HOST_CLASS);
    let class = WNDCLASSW {
        lpfnWndProc: Some(host_proc),
        hInstance: instance,
        hIcon: icon,
        lpszClassName: name.as_ptr(),
        ..Default::default()
    };
    if unsafe { RegisterClassW(&class) } == 0 {
        return Err(strings::REGISTER_WINDOW_FAILED.into());
    }
    let app = Rc::new(RefCell::new(App {
        host: ptr::null_mut(),
        window: None,
        worker: None,
        events: None,
        view: View::default(),
        busy: true,
        dirty_network: false,
        initialized: false,
        user_action: false,
        pending_error: None,
        pending_preferences: None,
        tray: false,
    }));
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
            ptr::null(),
        )
    };
    if host.is_null() {
        return Err(strings::HOST_FAILED.into());
    }
    app.borrow_mut().host = host;
    TASKBAR_CREATED.store(
        unsafe { RegisterWindowMessageW(wide("TaskbarCreated").as_ptr()) },
        Ordering::Relaxed,
    );
    APP.with(|slot| *slot.borrow_mut() = Rc::downgrade(&app));
    let result = (|| {
        {
            let mut app = app.borrow_mut();
            app.add_tray()?;
            app.worker = Some(Worker::start(host as usize)?);
            app.events = Some(NetworkEvents::subscribe(host)?);
            if !std::env::args().any(|arg| arg == "--background") {
                // Winit creates native windows only while its event loop is
                // active. Use the same path as tray/second-instance opens so
                // the HWND exists before applying the icon and caption colors.
                post(host, OPEN_MESSAGE, 0);
            }
        }
        // Keep tray and network subscriptions alive without a visible window.
        slint::run_event_loop_until_quit().map_err(|_| strings::MESSAGE_LOOP_FAILED.into())
    })();
    APP.with(|slot| *slot.borrow_mut() = std::rc::Weak::new());
    let mut app = app.borrow_mut();
    app.events.take();
    if let Some(mut worker) = app.worker.take() {
        worker.shutdown();
    }
    app.close_window();
    app.remove_tray();
    unsafe {
        KillTimer(host, DEBOUNCE_TIMER);
        DestroyWindow(host);
        UnregisterClassW(wide(HOST_CLASS).as_ptr(), instance);
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
        if let Some(window) = &self.window {
            let native = native_window(window);
            unsafe {
                ShowWindow(native, SW_RESTORE);
                SetForegroundWindow(native);
            }
            return Ok(());
        }
        let window = MainWindow::new().map_err(|_| strings::WINDOW_FAILED)?;
        set_labels(&window);
        theme::refresh(&window);
        let host = self.host as usize;
        window.on_home_action(move || post(host as _, UI_MESSAGE, ID_HOME_ACTION));
        window.on_logout_device(move || post(host as _, UI_MESSAGE, ID_LOGOUT_DEVICE));
        window.on_save_account(move || post(host as _, UI_MESSAGE, ID_SAVE));
        window.on_delete_account(move || post(host as _, UI_MESSAGE, ID_DELETE));
        window.on_select_account(move || post(host as _, UI_MESSAGE, ID_ACCOUNTS));
        window.on_save_auto_login(move || post(host as _, UI_MESSAGE, ID_AUTO_LOGIN));
        window.on_save_appearance(move || post(host as _, UI_MESSAGE, ID_APPEARANCE));
        let weak = window.as_weak();
        window.on_open_about_link(move |index| {
            // Only the three compiled-in public project links can be launched.
            if let Some(url) = usize::try_from(index)
                .ok()
                .and_then(|i| strings::ABOUT_LINKS.get(i))
            {
                let owner = weak
                    .upgrade()
                    .map(|window| native_window(&window))
                    .unwrap_or(ptr::null_mut());
                let result = unsafe {
                    ShellExecuteW(
                        owner,
                        wide("open").as_ptr(),
                        wide(url).as_ptr(),
                        ptr::null(),
                        ptr::null(),
                        SW_SHOWNORMAL,
                    )
                };
                if result as isize <= 32 {
                    show_window_error(owner, strings::OPEN_LINK_FAILED);
                }
            }
        });
        window.on_add_current_wifi(move || post(host as _, UI_MESSAGE, ID_ADD_CURRENT_WIFI));
        window.on_add_target(move || post(host as _, UI_MESSAGE, ID_ADD_TARGET));
        window.on_delete_target(move || post(host as _, UI_MESSAGE, ID_DELETE_TARGET));
        window.window().on_close_requested(move || {
            post(host as _, CLOSE_MESSAGE, 0);
            slint::CloseRequestResponse::KeepWindowShown
        });
        let weak = window.as_weak();
        let mut native_initialized = false;
        window
            .window()
            .on_winit_window_event(move |native_window, event| {
                // show() can return before Winit creates the HWND, even when it is
                // called from the event loop. Initialize on the first native event
                // instead of silently sending icon/theme updates to a null handle.
                if !native_initialized && let Some(window) = weak.upgrade() {
                    native_initialized = initialize_native_window(&window);
                }
                if matches!(
                    event,
                    slint::winit_030::winit::event::WindowEvent::RedrawRequested
                ) {
                    repaint::mark_native_update_region_dirty(native_window);
                }
                EventResult::Propagate
            });
        self.window = Some(window);
        self.render(true, true);
        let window = self.window.as_ref().unwrap();
        window.show().map_err(|_| strings::WINDOW_FAILED)?;
        if self.pending_error.is_some() {
            post(self.host, ERROR_MESSAGE, 0);
        }
        Ok(())
    }
    fn close_window(&mut self) {
        if let Some(window) = self.window.take() {
            window.set_password("".into());
            let _ = window.hide();
            // Dropping the component releases its native window and form drafts.
        }
    }
    fn submit(&mut self, command: Command) {
        if self.busy || self.view.fatal || !self.initialized {
            return;
        }
        let Some(worker) = &self.worker else {
            return;
        };
        let invalidates_info = matches!(
            command,
            Command::HomeAction | Command::Login | Command::Refresh | Command::NetworkChanged
        );
        let user_action = !matches!(command, Command::NetworkChanged);
        match worker.commands.try_send(command) {
            Ok(()) => {
                self.busy = true;
                self.user_action = user_action;
                self.view.message = strings::BUSY.into();
                if invalidates_info {
                    self.view.account_info = Default::default();
                    self.view.online = Online::Unknown;
                }
            }
            Err(TrySendError::Disconnected(_)) => {
                self.view.message = strings::THREAD_STOPPED.into();
                self.view.fatal = true;
                self.report_error(strings::THREAD_STOPPED.into());
            }
            Err(TrySendError::Full(_)) => {
                self.dirty_network = true;
            }
        }
        self.render(false, false);
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
        let sync_accounts = !self.initialized
            || self.view.accounts != view.accounts
            || self.view.selected != view.selected;
        let pending_preferences = self.pending_preferences.take();
        let sync_preferences = pending_preferences.is_some()
            || !self.initialized
            || self.view.preferences.target_ssids != view.preferences.target_ssids
            || self.view.preferences.auto_login != view.preferences.auto_login;
        let sync_preferences =
            sync_preferences || self.view.preferences.appearance != view.preferences.appearance;
        let editor_error = view.failed && pending_preferences.as_ref().is_some_and(|p| p.editor);
        let error = (view.failed && !editor_error && (self.user_action || view.fatal))
            .then(|| view.message.clone());
        self.user_action = false;
        if let Some(window) = &self.window
            && window.get_saving_account()
        {
            window.set_saving_account(false);
            if !view.failed {
                window.invoke_close_account_editor();
            }
        }
        self.view = view;
        if let Some(window) = &self.window
            && let Some(pending) = &pending_preferences
        {
            window.set_saving_target(false);
            if pending.editor {
                if self.view.failed {
                    window.set_target_error(self.view.message.as_str().into());
                } else {
                    window.set_target_name("".into());
                    window.set_target_error("".into());
                    window.invoke_close_target_editor();
                }
            }
        }
        self.busy = false;
        self.initialized = true;
        self.render(sync_accounts, sync_preferences);
        if !self.view.failed
            && let Some(pending) = pending_preferences
            && let Some(window) = &self.window
        {
            select_target(window, pending.selected.as_deref());
        }
        if let Some(error) = error {
            self.report_error(error);
        }
        if self.dirty_network {
            self.dirty_network = false;
            unsafe {
                SetTimer(self.host, DEBOUNCE_TIMER, NETWORK_REFRESH_DEBOUNCE_MS, None);
            }
        }
    }
    fn report_error(&mut self, error: String) {
        self.pending_error = Some(error);
        post(self.host, ERROR_MESSAGE, 0);
    }
    fn render(&self, sync_accounts: bool, sync_preferences: bool) {
        let Some(window) = &self.window else { return };
        let network = &self.view.network;
        let ssid = network
            .ssid
            .as_deref()
            .unwrap_or(if network.ipv4.is_empty() {
                strings::DISCONNECTED
            } else if network.kind == NetworkKind::Ethernet {
                strings::ETHERNET
            } else {
                strings::SSID_UNAVAILABLE
            });
        window.set_network_name(ssid.into());
        window.set_can_add_current_wifi(current_wifi(network).is_some());
        window.set_network_ipv4(
            if network.ipv4.is_empty() {
                "—"
            } else {
                &network.ipv4
            }
            .into(),
        );
        let account = strings::account_card(&self.view.account_info, self.view.online);
        window.set_show_account(self.view.online == Online::Yes);
        window.set_account_name(account.username.into());
        window.set_account_balance(account.balance.into());
        window.set_account_error(account.error.into());
        window.set_has_overview(account.has_overview);
        window.set_show_traffic(account.progress.is_some());
        window.set_traffic_progress(account.progress.unwrap_or_default());
        window.set_traffic_used_percent(account.used_percent.into());
        window.set_traffic_amount(account.traffic_amount.into());
        let info = &self.view.account_info;
        let show_devices = self.view.online == Online::Yes && info.username.is_some();
        window.set_show_devices(show_devices);
        window.set_devices_available(show_devices && info.devices.is_some());
        window.set_device_generation(self.view.device_generation.to_string().into());
        window.set_devices_error(
            if show_devices {
                info.devices_error
                    .as_deref()
                    .or_else(|| info.devices.is_none().then_some(strings::DEVICES_FAILED))
                    .unwrap_or_default()
            } else {
                ""
            }
            .into(),
        );
        let mut devices = if show_devices {
            info.devices.clone().unwrap_or_default()
        } else {
            Vec::new()
        };
        devices.sort_by_key(|device| device.ip != network.ipv4);
        window.set_devices(ModelRc::new(VecModel::from(
            devices
                .into_iter()
                .map(|device| DeviceRow {
                    current: !device.ip.is_empty() && device.ip == network.ipv4,
                    ip: strings::device_ip(&device.ip).into(),
                    mac: device.mac.into(),
                    session_id: device.session_id.into(),
                })
                .collect::<Vec<_>>(),
        )));
        window.set_action_label(
            if self.busy {
                strings::BUSY
            } else {
                match HomeAction::for_session(self.view.online, network, &self.view.preferences) {
                    HomeAction::Login => strings::LOGIN,
                    HomeAction::Refresh => strings::REFRESH,
                }
            }
            .into(),
        );
        window.set_ready(!self.busy && !self.view.fatal && self.initialized);
        let index = self
            .view
            .selected
            .as_ref()
            .and_then(|name| self.view.accounts.iter().position(|s| s == name));
        window.set_account_index(index.map_or(-1, |i| i as i32));
        if sync_accounts {
            window.set_accounts(ModelRc::new(VecModel::from(
                self.view
                    .accounts
                    .iter()
                    .map(|s| s.as_str().into())
                    .collect::<Vec<_>>(),
            )));
            if !window.get_account_editor_open() {
                window.set_username("".into());
                window.set_password("".into());
            }
        }
        if sync_preferences {
            let selected = selected_target(window);
            set_target_items(window, &self.view.preferences.target_ssids);
            select_target(window, selected.as_deref());
            window.set_auto_login(self.view.preferences.auto_login);
            let appearance = self.view.preferences.appearance.index();
            window.set_appearance(appearance);
            theme::refresh(window);
            theme::title_bar(native_window(window), window);
        }
    }
    fn command(&mut self, id: usize) {
        if id == ID_EXIT {
            let _ = slint::quit_event_loop();
            return;
        }
        if self.busy || self.view.fatal || !self.initialized {
            return;
        }
        match id {
            ID_APPEARANCE => {
                if let Some(window) = &self.window
                    && let Some(appearance) = Appearance::from_index(window.get_appearance())
                    && appearance != self.view.preferences.appearance
                {
                    let mut candidate = self.view.preferences.clone();
                    candidate.appearance = appearance;
                    self.save_preferences(candidate, selected_target(window), false);
                }
            }
            ID_HOME_ACTION => self.submit(Command::HomeAction),
            ID_LOGIN => self.submit(Command::Login),
            ID_REFRESH => self.submit(Command::Refresh),
            ID_LOGOUT_DEVICE => {
                if let Some(window) = &self.window {
                    let generation = window.get_requested_generation().parse::<u64>().ok();
                    let info = &self.view.account_info;
                    if generation == Some(self.view.device_generation)
                        && self.view.online == Online::Yes
                        && let Some(username) = &info.username
                        && let Some(device) = info.devices.as_ref().and_then(|devices| {
                            devices.iter().find(|device| {
                                device.session_id == window.get_requested_device().as_str()
                            })
                        })
                    {
                        self.submit(Command::LogoutDevice(DeviceLogoutRequest {
                            generation: self.view.device_generation,
                            network: self.view.network.clone(),
                            username: username.clone(),
                            device: device.clone(),
                        }));
                    }
                }
            }
            ID_SAVE => {
                if let Some(window) = &self.window {
                    let username = window.get_username().trim().to_owned();
                    let password = Zeroizing::new(window.get_password().to_string());
                    window.set_password("".into());
                    window.set_saving_account(true);
                    self.submit(Command::SaveAccount { username, password });
                    if !self.busy
                        && let Some(window) = &self.window
                    {
                        window.set_saving_account(false);
                    }
                }
            }
            ID_DELETE => {
                if let Some(name) = &self.view.selected {
                    self.submit(Command::DeleteAccount(name.clone()));
                }
            }
            ID_ACCOUNTS => {
                if let Some(window) = &self.window {
                    let index = window.get_account_index();
                    if index >= 0
                        && let Some(name) = self.view.accounts.get(index as usize)
                    {
                        self.submit(Command::SelectAccount(name.clone()));
                    }
                }
            }
            ID_AUTO_LOGIN => {
                if let Some(window) = &self.window {
                    let mut candidate = self.view.preferences.clone();
                    candidate.auto_login = window.get_auto_login();
                    if candidate.auto_login != self.view.preferences.auto_login {
                        self.save_preferences(candidate, selected_target(window), false);
                    }
                }
            }
            ID_ADD_CURRENT_WIFI => {
                if let Some(window) = &self.window
                    && let Some(ssid) = current_wifi(&self.view.network)
                {
                    let mut candidate = self.view.preferences.clone();
                    let ssid = ssid.to_owned();
                    if candidate.target_ssids.contains(&ssid) {
                        select_target(window, Some(&ssid));
                    } else {
                        candidate.target_ssids.push(ssid.clone());
                        self.save_preferences(candidate, Some(ssid), false);
                    }
                }
            }
            ID_ADD_TARGET => {
                if let Some(window) = &self.window {
                    let name = window.get_target_name();
                    let name = name.trim();
                    let error = if name.is_empty() || name.contains(['\r', '\n']) {
                        Some(strings::INVALID_WIFI)
                    } else if name.len() > 32 {
                        Some(strings::SSID_TOO_LONG)
                    } else {
                        None
                    };
                    if let Some(error) = error {
                        window.set_target_error(error.into());
                        return;
                    }
                    window.set_target_error("".into());
                    let mut candidate = self.view.preferences.clone();
                    if candidate.target_ssids.iter().any(|s| s == name) {
                        select_target(window, Some(name));
                        window.set_target_name("".into());
                        window.invoke_close_target_editor();
                    } else {
                        candidate.target_ssids.push(name.to_owned());
                        self.save_preferences(candidate, Some(name.to_owned()), true);
                    }
                }
            }
            ID_DELETE_TARGET => {
                if let Some(window) = &self.window {
                    let mut candidate = self.view.preferences.clone();
                    let targets = &mut candidate.target_ssids;
                    let index = window.get_target_index();
                    if index >= 0 && (index as usize) < targets.len() {
                        targets.remove(index as usize);
                        let next = targets
                            .get((index as usize).min(targets.len().saturating_sub(1)))
                            .cloned();
                        self.save_preferences(candidate, next, false);
                    }
                }
            }
            _ => (),
        }
    }
    fn save_preferences(&mut self, candidate: Preferences, selected: Option<String>, editor: bool) {
        let appearance = candidate.appearance;
        let candidate = match preferences(&candidate.target_ssids, candidate.auto_login) {
            Ok(mut candidate) => {
                candidate.appearance = appearance;
                candidate
            }
            Err(error) => {
                self.report_error(error);
                return;
            }
        };
        self.pending_preferences = Some(PendingPreferences { selected, editor });
        if let Some(window) = &self.window {
            window.set_saving_target(editor);
        }
        self.submit(Command::SavePreferences(candidate));
        if !self.busy {
            self.pending_preferences = None;
            if let Some(window) = &self.window {
                window.set_saving_target(false);
            }
            self.render(false, true);
        }
    }
}
fn current_wifi(network: &Network) -> Option<&str> {
    network.ssid.as_deref().filter(|ssid| {
        network.kind == NetworkKind::Wifi
            && !ssid.is_empty()
            && ssid.len() <= 32
            && *ssid == ssid.trim()
            && !ssid.contains(['\r', '\n'])
    })
}
fn target_items(window: &MainWindow) -> Vec<String> {
    window
        .get_target_items()
        .iter()
        .map(|s| s.to_string())
        .collect()
}
fn set_target_items(window: &MainWindow, targets: &[String]) {
    window.set_target_items(ModelRc::new(VecModel::from(
        targets
            .iter()
            .map(|s| s.as_str().into())
            .collect::<Vec<_>>(),
    )));
}
fn selected_target(window: &MainWindow) -> Option<String> {
    usize::try_from(window.get_target_index())
        .ok()
        .and_then(|index| target_items(window).get(index).cloned())
}
fn select_target(window: &MainWindow, name: Option<&str>) {
    let index = name.and_then(|name| window.get_target_items().iter().position(|s| s == name));
    window.set_target_index(index.map_or(-1, |index| index as i32));
}
fn preferences(targets: &[String], auto_login: bool) -> Result<Preferences, String> {
    let mut target_ssids = Vec::new();
    for line in targets.iter().map(|s| s.trim()).filter(|s| !s.is_empty()) {
        if line.contains(['\r', '\n']) {
            return Err(strings::INVALID_WIFI.into());
        }
        if line.len() > 32 {
            return Err(strings::SSID_TOO_LONG.into());
        }
        if !target_ssids.iter().any(|existing| existing == line) {
            target_ssids.push(line.to_owned());
        }
    }
    Ok(Preferences {
        target_ssids,
        auto_login,
        ..Preferences::default()
    })
}
fn set_labels(window: &MainWindow) {
    let preferences = window.global::<PreferencesLabels>();
    preferences.set_settings(strings::SETTINGS_PAGE.into());
    preferences.set_about(strings::ABOUT_PAGE.into());
    preferences.set_appearance(strings::APPEARANCE.into());
    preferences.set_system(strings::APPEARANCE_SYSTEM.into());
    preferences.set_light(strings::APPEARANCE_LIGHT.into());
    preferences.set_dark(strings::APPEARANCE_DARK.into());
    preferences.set_auto_login(strings::AUTO.into());
    preferences.set_app(strings::APP.into());
    preferences.set_description(strings::ABOUT_DESCRIPTION.into());
    preferences.set_app_name(strings::APP_NAME_LABEL.into());
    preferences.set_version_label(strings::VERSION_LABEL.into());
    preferences.set_version(env!("CARGO_PKG_VERSION").into());
    preferences.set_identifier_label(strings::IDENTIFIER_LABEL.into());
    preferences.set_identifier(strings::IDENTIFIER.into());
    preferences.set_license_label(strings::LICENSE_LABEL.into());
    preferences.set_license(env!("CARGO_PKG_LICENSE").into());
    preferences.set_homepage(strings::HOMEPAGE.into());
    preferences.set_homepage_summary(strings::HOMEPAGE_SUMMARY.into());
    preferences.set_source(strings::SOURCE.into());
    preferences.set_source_summary(strings::SOURCE_SUMMARY.into());
    preferences.set_license_details(strings::LICENSE_DETAILS.into());
    preferences.set_license_summary(strings::LICENSE_SUMMARY.into());
    preferences.set_updates(strings::CHECK_UPDATES.into());
    preferences.set_updates_summary(strings::UPDATES_UNAVAILABLE.into());
    let labels = window.global::<Labels>();
    labels.set_app(strings::APP.into());
    labels.set_home(strings::HOME_PAGE.into());
    labels.set_accounts_page(strings::ACCOUNTS_PAGE.into());
    labels.set_network_page(strings::NETWORK_PAGE.into());
    labels.set_toggle_navigation(strings::TOGGLE_NAVIGATION.into());
    labels.set_network_title(strings::NETWORK_TITLE.into());
    labels.set_network_name(strings::NETWORK_NAME.into());
    labels.set_network_ipv4(strings::NETWORK_IPV4.into());
    labels.set_account_title(strings::ACCOUNT_TITLE.into());
    labels.set_account_username(strings::ACCOUNT_USERNAME.into());
    labels.set_remaining_money(strings::REMAINING_MONEY.into());
    labels.set_retry_fetch(strings::RETRY_FETCH.into());
    labels.set_traffic_progress(strings::TRAFFIC_PROGRESS.into());
    labels.set_accounts(strings::ACCOUNTS.into());
    labels.set_no_saved_accounts(strings::NO_SAVED_ACCOUNTS.into());
    labels.set_username(strings::USERNAME.into());
    labels.set_password(strings::PASSWORD.into());
    labels.set_add_account(strings::ADD_ACCOUNT.into());
    labels.set_edit_account(strings::EDIT_ACCOUNT.into());
    labels.set_cancel(strings::CANCEL.into());
    labels.set_save(strings::SAVE.into());
    labels.set_delete(strings::DELETE.into());
    labels.set_targets(strings::TARGETS.into());
    labels.set_wifi_name(strings::WIFI_NAME.into());
    labels.set_add_wifi(strings::ADD_WIFI.into());
    labels.set_add(strings::ADD.into());
    labels.set_delete_wifi(strings::DELETE_WIFI.into());
    labels.set_no_targets(strings::NO_TARGETS.into());
    labels.set_add_current_wifi(strings::ADD_CURRENT_WIFI.into());
    labels.set_devices_title(strings::DEVICES_TITLE.into());
    labels.set_no_devices(strings::NO_DEVICES.into());
    labels.set_current_device(strings::CURRENT_DEVICE.into());
    labels.set_logout_device(strings::LOGOUT_DEVICE.into());
}
fn native_window(window: &MainWindow) -> HWND {
    window
        .window()
        .window_handle()
        .window_handle()
        .ok()
        .map(|h| h.as_raw())
        .and_then(|handle| match handle {
            RawWindowHandle::Win32(handle) => Some(handle.hwnd.get() as HWND),
            _ => None,
        })
        .unwrap_or(ptr::null_mut())
}
fn initialize_native_window(window: &MainWindow) -> bool {
    let native = native_window(window);
    if native.is_null() {
        return false;
    }
    crate::platform::icon::apply(native);
    theme::title_bar(native, window);
    unsafe {
        SetForegroundWindow(native);
    }
    true
}
fn post(host: HWND, message: u32, id: usize) {
    unsafe {
        PostMessageW(host, message, id, 0);
    }
}
fn enqueue(action: impl FnOnce(Rc<RefCell<App>>) + Send + 'static) {
    let _ = slint::invoke_from_event_loop(move || {
        APP.with(|slot| {
            if let Some(app) = slot.borrow().upgrade() {
                action(app);
            }
        });
    });
}
fn open_app_window(app: Rc<RefCell<App>>) {
    let result = app.borrow_mut().open_window();
    if let Err(error) = result {
        show_error(&error);
    }
}
unsafe extern "system" fn host_proc(
    window: HWND,
    message: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    if (message == WM_DRAWITEM || message == WM_MEASUREITEM)
        && lparam != 0
        && let Some(result) = theme::menu_message(message, lparam)
    {
        return result;
    }
    match message {
        RESULT_MESSAGE => enqueue(|app| app.borrow_mut().result()),
        NETWORK_MESSAGE => unsafe {
            SetTimer(window, DEBOUNCE_TIMER, NETWORK_REFRESH_DEBOUNCE_MS, None);
        },
        WM_TIMER if wparam == DEBOUNCE_TIMER => {
            unsafe {
                KillTimer(window, DEBOUNCE_TIMER);
            }
            enqueue(|app| {
                let mut app = app.borrow_mut();
                if app.busy {
                    app.dirty_network = true;
                } else {
                    app.submit(Command::NetworkChanged);
                }
            });
        }
        OPEN_MESSAGE => enqueue(open_app_window),
        CLOSE_MESSAGE => enqueue(|app| app.borrow_mut().close_window()),
        UI_MESSAGE if wparam == ID_SHOW => enqueue(open_app_window),
        UI_MESSAGE => enqueue(move |app| app.borrow_mut().command(wparam)),
        WM_SETTINGCHANGE | WM_THEMECHANGED | WM_SYSCOLORCHANGE => post(window, THEME_MESSAGE, 0),
        THEME_MESSAGE => enqueue(|app| {
            if let Some(window) = &app.borrow().window {
                theme::refresh(window);
                theme::title_bar(native_window(window), window);
            }
        }),
        ERROR_MESSAGE => enqueue(|app| {
            let error = {
                let mut app = app.borrow_mut();
                app.window
                    .as_ref()
                    .map(native_window)
                    .and_then(|window| app.pending_error.take().map(|error| (window, error)))
            };
            // Modal native loops must never hold an application state borrow.
            if let Some((window, error)) = error {
                show_window_error(window, &error);
            }
        }),
        TRAY_MESSAGE => match lparam as u32 {
            WM_LBUTTONUP | WM_LBUTTONDBLCLK => post(window, OPEN_MESSAGE, 0),
            WM_RBUTTONUP | WM_CONTEXTMENU => enqueue(|app| {
                let (host, disabled) = {
                    let app = app.borrow();
                    (app.host, app.busy || app.view.fatal || !app.initialized)
                };
                tray_menu(host, disabled);
            }),
            _ => (),
        },
        WM_POWERBROADCAST if wparam == 0x12 => {
            unsafe {
                SetTimer(window, DEBOUNCE_TIMER, 1500, None);
            }
            return 1;
        }
        _ if message != 0 && message == TASKBAR_CREATED.load(Ordering::Relaxed) => {
            enqueue(|app| {
                let mut app = app.borrow_mut();
                app.tray = false;
                if let Err(error) = app.add_tray() {
                    app.report_error(error);
                }
            });
        }
        _ => return unsafe { DefWindowProcW(window, message, wparam, lparam) },
    }
    0
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

fn scale(value: i32, dpi: u32) -> i32 {
    ((value as i64 * dpi as i64 + 48) / 96) as i32
}
pub fn show_error(message: &str) {
    show_window_error(ptr::null_mut(), message);
}
fn show_window_error(owner: HWND, message: &str) {
    unsafe {
        MessageBoxW(
            owner,
            wide(message).as_ptr(),
            wide(strings::APP).as_ptr(),
            MB_OK | MB_ICONERROR,
        );
    }
}
