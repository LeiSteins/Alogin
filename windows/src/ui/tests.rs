use super::*;
use crate::domain::{AccountInfo, AccountOverview, Network};
use i_slint_backend_testing::{ElementHandle, TestingBackend, TestingBackendOptions};
use std::{sync::mpsc, time::Duration};

fn fixture() -> (App, mpsc::Receiver<Command>, mpsc::Sender<View>) {
    let (worker, commands, results) = Worker::test_channel();
    let window = MainWindow::new().unwrap();
    set_labels(&window);
    theme::refresh(&window);
    let app = App {
        host: ptr::null_mut(),
        window: Some(window),
        worker: Some(worker),
        events: None,
        view: View {
            accounts: vec!["student-a".into(), "student-b".into()],
            selected: Some("student-a".into()),
            ..View::default()
        },
        busy: false,
        dirty_network: false,
        initialized: true,
        user_action: false,
        pending_error: None,
        tray: false,
    };
    app.render(true, true);
    (app, commands, results)
}

fn headless() {
    slint::platform::set_platform(Box::new(TestingBackend::new(TestingBackendOptions {
        mock_time: true,
        threading: false,
        renderer_name: Some("software".into()),
    })))
    .unwrap();
}

fn click(window: &MainWindow, label: &str) {
    ElementHandle::find_by_accessible_label(window, label)
        .next()
        .unwrap()
        .invoke_accessible_default_action();
    i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(1));
}

fn snapshot(window: &MainWindow, name: &str) {
    snapshot_at_size(window, name, 660.0, 386.0);
}

fn snapshot_at_size(window: &MainWindow, name: &str, width: f32, height: f32) {
    window
        .window()
        .set_size(slint::LogicalSize::new(width, height));
    window.show().unwrap();
    window.window().take_snapshot().unwrap();
    i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(200));
    save_snapshot(window, name, width, height);
}

fn save_snapshot(window: &MainWindow, name: &str, width: f32, height: f32) {
    let pixels = window.window().take_snapshot().unwrap();
    let scale = window.window().scale_factor();
    assert_eq!(
        (pixels.width(), pixels.height()),
        (
            (width * scale).round() as u32,
            (height * scale).round() as u32
        )
    );
    let directory = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("target/ui-previews");
    std::fs::create_dir_all(&directory).unwrap();
    image::save_buffer(
        directory.join(format!("{name}.png")),
        pixels.as_bytes(),
        pixels.width(),
        pixels.height(),
        image::ColorType::Rgba8,
    )
    .unwrap();
}

#[test]
fn navigation_animation_interpolates_and_reverses_while_pages_remain_interactive() {
    headless();
    let (app, _, _) = fixture();
    let window = app.window.as_ref().unwrap();
    window.show().unwrap();
    window.window().take_snapshot().unwrap();
    let find = |label: &str| {
        ElementHandle::find_by_accessible_label(window, label)
            .next()
            .unwrap()
    };
    let home = find(strings::HOME_PAGE);
    let toggle = find(strings::TOGGLE_NAVIGATION);
    let expanded = home.size().width;
    toggle.invoke_accessible_default_action();
    window.window().take_snapshot().unwrap();
    i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(60));
    window.window().take_snapshot().unwrap();
    let intermediate = home.size().width;
    assert!(intermediate > 40.0 && intermediate < expanded);
    find(strings::ACCOUNTS_PAGE).invoke_accessible_default_action();
    assert_eq!(
        find(strings::ACCOUNTS_PAGE).accessible_checked(),
        Some(true)
    );
    toggle.invoke_accessible_default_action();
    window.window().take_snapshot().unwrap();
    assert!((home.size().width - intermediate).abs() < 0.1);
    i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(60));
    window.window().take_snapshot().unwrap();
    assert!(home.size().width > intermediate && home.size().width < expanded);
    i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(200));
    window.window().take_snapshot().unwrap();
    assert_eq!(home.size().width, expanded);
    toggle.invoke_accessible_default_action();
    window.window().take_snapshot().unwrap();
    i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(200));
    window.window().take_snapshot().unwrap();
    assert_eq!(home.size().width, 40.0);
}

#[test]
fn pages_keep_drafts_and_refresh_keeps_them_while_authentication_card_tracks_session() {
    headless();
    let (mut app, _, results) = fixture();
    let window = app.window.as_ref().unwrap();
    window.set_username("draft-user".into());
    window.set_targets("draft-wifi".into());
    click(window, strings::TOGGLE_NAVIGATION);
    click(window, strings::ACCOUNTS_PAGE);
    click(window, strings::NETWORK_PAGE);
    click(window, strings::HOME_PAGE);
    click(window, strings::TOGGLE_NAVIGATION);
    assert_eq!(window.get_username(), "draft-user");
    assert_eq!(window.get_targets(), "draft-wifi");
    assert!(!window.get_show_account());
    let mut view = app.view.clone();
    view.online = Online::Yes;
    view.account_info.username = Some("portal-user".into());
    results.send(view).unwrap();
    app.result();
    let window = app.window.as_ref().unwrap();
    assert!(window.get_show_account());
    assert_eq!(window.get_account_name(), "portal-user");
    assert_eq!(window.get_username(), "draft-user");
    assert_eq!(window.get_targets(), "draft-wifi");
}

#[test]
fn account_submission_clears_password_and_failed_selection_restores_saved_account() {
    headless();
    let (mut app, commands, results) = fixture();
    let window = app.window.as_ref().unwrap();
    window.set_username("  new-account  ".into());
    window.set_password("test-only-password".into());
    app.command(ID_SAVE);
    match commands.recv().unwrap() {
        Command::SaveAccount { username, password } => {
            assert_eq!(username, "new-account");
            assert_eq!(password.as_str(), "test-only-password");
        }
        _ => panic!("expected account save"),
    }
    assert!(app.window.as_ref().unwrap().get_password().is_empty());
    assert!(!app.window.as_ref().unwrap().get_ready());
    results.send(app.view.clone()).unwrap();
    app.result();
    app.window.as_ref().unwrap().set_account_index(1);
    app.command(ID_ACCOUNTS);
    assert!(
        matches!(commands.recv().unwrap(), Command::SelectAccount(name) if name == "student-b")
    );
    let mut failed = app.view.clone();
    failed.failed = true;
    failed.message = strings::STORAGE_FAILED.into();
    results.send(failed).unwrap();
    app.result();
    assert_eq!(app.view.selected.as_deref(), Some("student-a"));
    assert_eq!(app.window.as_ref().unwrap().get_account_index(), 0);
    assert!(app.pending_error.is_some());
}

#[test]
fn network_editor_normalizes_entries_and_enforces_ssid_byte_limit() {
    let p = preferences(" bjut_wifi \r\nbjut_wifi\n\n宿舍网络\n", false).unwrap();
    assert_eq!(p.target_ssids, ["bjut_wifi", "宿舍网络"]);
    assert!(!p.auto_login);
    assert!(preferences(&"网".repeat(11), true).is_err());
    assert!(preferences(&"a".repeat(32), true).is_ok());
}

#[test]
fn mouse_and_keyboard_activate_controls_and_account_popup() {
    use slint::platform::{PointerEventButton, WindowEvent};
    headless();
    let (app, _, _) = fixture();
    let window = app.window.as_ref().unwrap();
    window
        .window()
        .set_size(slint::LogicalSize::new(660.0, 386.0));
    window.show().unwrap();
    window.window().take_snapshot().unwrap();
    let find = |label: &str| {
        ElementHandle::find_by_accessible_label(window, label)
            .next()
            .unwrap_or_else(|| panic!("missing control: {label}"))
    };
    find(strings::NETWORK_PAGE).mock_single_click(PointerEventButton::Left);
    window.window().take_snapshot().unwrap();
    find(strings::AUTO).mock_single_click(PointerEventButton::Left);
    assert!(window.get_auto_login());
    window
        .window()
        .dispatch_event(WindowEvent::KeyPressed { text: " ".into() });
    window
        .window()
        .dispatch_event(WindowEvent::KeyReleased { text: " ".into() });
    assert!(!window.get_auto_login());
    find(strings::ACCOUNTS_PAGE).mock_single_click(PointerEventButton::Left);
    window.window().take_snapshot().unwrap();
    let saves = Rc::new(std::cell::Cell::new(0));
    let save_count = saves.clone();
    window.on_save_account(move || save_count.set(save_count.get() + 1));
    find(strings::SAVE).mock_single_click(PointerEventButton::Left);
    assert_eq!(saves.get(), 1);
    window
        .window()
        .dispatch_event(WindowEvent::KeyPressed { text: "\n".into() });
    window
        .window()
        .dispatch_event(WindowEvent::KeyReleased { text: "\n".into() });
    assert_eq!(saves.get(), 2);
    find(strings::DELETE).mock_single_click(PointerEventButton::Left);
    snapshot(window, "button-pointer-focus");
    window
        .window()
        .dispatch_event(WindowEvent::KeyPressed { text: "\n".into() });
    window
        .window()
        .dispatch_event(WindowEvent::KeyReleased { text: "\n".into() });
    snapshot(window, "button-keyboard-focus");
    let picker = ElementHandle::find_by_accessible_label(window, strings::ACCOUNTS)
        .find(|element| {
            element.accessible_role() == Some(i_slint_backend_testing::AccessibleRole::Combobox)
        })
        .unwrap();
    picker.mock_single_click(PointerEventButton::Left);
    i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(1));
    snapshot(window, "account-popup");
    find("student-b").mock_single_click(PointerEventButton::Left);
    assert_eq!(window.get_account_index(), 1);
}

#[test]
fn home_action_and_busy_state_follow_existing_network_rules() {
    headless();
    let (mut app, commands, _) = fixture();
    app.view.network = Network {
        kind: NetworkKind::Wifi,
        ssid: Some("bjut_wifi".into()),
        ipv4: "10.1.2.3".into(),
        ..Network::default()
    };
    app.view.online = Online::No;
    app.render(false, false);
    assert_eq!(
        app.window.as_ref().unwrap().get_action_label(),
        strings::LOGIN
    );
    app.view.network.ssid = None;
    app.render(false, false);
    assert_eq!(
        app.window.as_ref().unwrap().get_action_label(),
        strings::REFRESH
    );
    app.view.network.kind = NetworkKind::Ethernet;
    app.render(false, false);
    assert_eq!(
        app.window.as_ref().unwrap().get_action_label(),
        strings::LOGIN
    );
    app.command(ID_HOME_ACTION);
    assert!(matches!(commands.recv().unwrap(), Command::HomeAction));
    assert!(!app.window.as_ref().unwrap().get_ready());
    assert_eq!(
        app.window.as_ref().unwrap().get_action_label(),
        strings::BUSY
    );
}

#[test]
fn traffic_progress_tracks_verified_overview_and_clears_when_values_are_unavailable() {
    headless();
    let (mut app, _, _) = fixture();
    app.view.online = Online::Yes;
    app.view.account_info = AccountInfo {
        username: Some("student-a".into()),
        overview: Some(AccountOverview {
            used_megabytes: Some(3072.0),
            remaining_megabytes: Some(1024.0),
            money_yuan: None,
        }),
        error: None,
    };
    app.render(false, false);
    let window = app.window.as_ref().unwrap();
    assert!(window.get_show_traffic());
    assert_eq!(window.get_traffic_progress(), 0.75);
    assert_eq!(window.get_traffic_used_percent(), "已用 75%");
    assert_eq!(window.get_traffic_amount(), "3G / 4G");
    assert!(window.get_account_balance().is_empty());
    for online in [Online::No, Online::Unknown] {
        app.view.online = online;
        app.render(false, false);
        assert!(!app.window.as_ref().unwrap().get_show_traffic());
    }
    app.view.online = Online::Yes;
    for (used, remaining) in [(Some(0.0), Some(0.0)), (None, Some(1024.0))] {
        let overview = app.view.account_info.overview.as_mut().unwrap();
        overview.used_megabytes = used;
        overview.remaining_megabytes = remaining;
        app.render(false, false);
        assert!(app.window.as_ref().unwrap().get_show_account());
        assert!(!app.window.as_ref().unwrap().get_show_traffic());
        assert_eq!(app.window.as_ref().unwrap().get_traffic_progress(), 0.0);
    }
    app.view.account_info.overview = None;
    app.view.account_info.error = Some(strings::ACCOUNT_FAILED.into());
    app.render(false, false);
    let window = app.window.as_ref().unwrap();
    assert!(window.get_show_account());
    assert!(!window.get_show_traffic());
    assert_eq!(window.get_account_error(), strings::ACCOUNT_FAILED);
    assert_eq!(window.get_account_name(), "student-a");
    assert!(!window.get_has_overview());
    snapshot(window, "account-overview-error");
    ElementHandle::find_by_element_id(window, "MainWindow::network-card-view")
        .next()
        .unwrap()
        .scroll(0.0, -1000.0);
    i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(200));
    window.window().take_snapshot().unwrap();
    save_snapshot(window, "account-overview-error-scrolled", 660.0, 386.0);
    let retries = Rc::new(std::cell::Cell::new(0));
    let retry_count = retries.clone();
    window.on_home_action(move || retry_count.set(retry_count.get() + 1));
    click(window, strings::RETRY_FETCH);
    assert_eq!(retries.get(), 1);
    window.set_ready(false);
    click(window, strings::RETRY_FETCH);
    assert_eq!(retries.get(), 1);
}

#[test]
fn render_all_pages_in_light_dark_and_high_contrast_at_compact_size() {
    use slint::platform::{PointerEventButton, WindowEvent};

    headless();
    let (mut app, _, _) = fixture();
    app.view.network = Network {
        kind: NetworkKind::Wifi,
        ssid: Some("bjut_wifi".into()),
        ipv4: "10.1.2.3".into(),
        ..Network::default()
    };
    app.view.online = Online::Yes;
    app.view.account_info = AccountInfo {
        username: Some("student-a".into()),
        overview: Some(AccountOverview {
            used_megabytes: Some(2048.0),
            remaining_megabytes: Some(10240.0),
            money_yuan: Some(32.5),
        }),
        error: None,
    };
    app.render(true, true);
    let window = app.window.as_ref().unwrap();
    for (mode, dark, contrast) in [
        ("light", false, false),
        ("dark", true, false),
        ("contrast", false, true),
    ] {
        theme::preview(window, dark, contrast);
        for (page, label) in [
            ("home", strings::HOME_PAGE),
            ("accounts", strings::ACCOUNTS_PAGE),
            ("network", strings::NETWORK_PAGE),
        ] {
            click(window, label);
            snapshot(window, &format!("{mode}-{page}"));
            if page == "home" {
                assert_home_card_layout(window, true);
                let account =
                    ElementHandle::find_by_element_id(window, "MainWindow::account-card-view")
                        .next()
                        .unwrap();
                let network =
                    ElementHandle::find_by_element_id(window, "MainWindow::network-card-view")
                        .next()
                        .unwrap();
                let action = ElementHandle::find_by_accessible_label(window, strings::REFRESH)
                    .next()
                    .unwrap();
                let account_before = account.absolute_position();
                let action_before = action.absolute_position();
                network.scroll(0.0, -400.0);
                i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(200));
                window.window().take_snapshot().unwrap();
                assert!(account.absolute_position().y < account_before.y);
                assert_eq!(action.absolute_position(), action_before);
                save_snapshot(
                    window,
                    &format!("{mode}-home-stacked-scrolled"),
                    660.0,
                    386.0,
                );
            }
            snapshot_at_size(window, &format!("{mode}-{page}-default"), 860.0, 560.0);
            if page == "home" {
                assert_eq!(assert_home_card_layout(window, false), 312.0);
                for (name, width, stacked, card_width) in [
                    ("wrap-below-boundary", 835.0, true, 600.0),
                    ("wrap-at-boundary", 836.0, false, 300.0),
                    ("maximum-width", 1600.0, false, 600.0),
                ] {
                    snapshot_at_size(window, &format!("{mode}-home-{name}"), width, 560.0);
                    assert_eq!(assert_home_card_layout(window, stacked), card_width);
                }
            }
        }
        let navigation = ElementHandle::find_by_accessible_label(window, strings::ACCOUNTS_PAGE)
            .next()
            .unwrap();
        let origin = navigation.absolute_position();
        let size = navigation.size();
        let position =
            slint::LogicalPosition::new(origin.x + size.width / 2.0, origin.y + size.height / 2.0);
        window
            .window()
            .dispatch_event(WindowEvent::PointerMoved { position });
        snapshot(window, &format!("{mode}-navigation-hover"));
        window.window().dispatch_event(WindowEvent::PointerPressed {
            position,
            button: PointerEventButton::Left,
        });
        snapshot(window, &format!("{mode}-navigation-pressed"));
        window
            .window()
            .dispatch_event(WindowEvent::PointerReleased {
                position,
                button: PointerEventButton::Left,
            });
        window.window().dispatch_event(WindowEvent::PointerExited);
        click(window, strings::TOGGLE_NAVIGATION);
        snapshot(window, &format!("{mode}-navigation-collapsed"));
        click(window, strings::TOGGLE_NAVIGATION);
        click(window, strings::HOME_PAGE);
        snapshot_at_size(window, &format!("{mode}-home-wide"), 1200.0, 560.0);
        window.set_show_account(false);
        snapshot_at_size(window, &format!("{mode}-home-network-only"), 1200.0, 560.0);
        window.set_show_account(true);
        window.set_account_name("student-with-a-long-confirmed-username".into());
        window.set_account_balance("".into());
        window.set_show_traffic(false);
        window.set_traffic_progress(0.0);
        window.set_traffic_used_percent("—".into());
        window.set_traffic_amount("2G / —".into());
        window.set_account_error(strings::ACCOUNT_FAILED.into());
        window.set_network_name("宿舍网络名称较长测试Wi".into());
        window.set_network_ipv4("—".into());
        snapshot(window, &format!("{mode}-home-missing-wrapped-error"));
        window.set_account_name("student-a".into());
        window.set_account_balance("32.50 元".into());
        window.set_show_traffic(true);
        window.set_traffic_progress(1.0 / 6.0);
        window.set_traffic_used_percent("已用 16%".into());
        window.set_traffic_amount("2G / 12G".into());
        window.set_account_error("".into());
        window.set_network_name("bjut_wifi".into());
        window.set_network_ipv4("10.1.2.3".into());
    }
}

fn assert_home_card_layout(window: &MainWindow, stacked: bool) -> f32 {
    let find = |id| {
        ElementHandle::find_by_element_id(window, id)
            .next()
            .unwrap()
    };
    let network = find("MainWindow::network-card-view");
    let account = find("MainWindow::account-card-view");
    let content = find("MainWindow::home-cards");
    let width = network.size().width;
    assert!((250.0..=600.0).contains(&width));
    assert_eq!(width, account.size().width);
    let network_origin = network.absolute_position();
    let account_origin = account.absolute_position();
    if stacked {
        assert_eq!(account_origin.x, network_origin.x);
        assert_eq!(
            account_origin.y,
            network_origin.y + network.size().height + 20.0
        );
        assert_eq!(
            content.size().height,
            network.size().height + 20.0 + account.size().height
        );
    } else {
        assert_eq!(account_origin.y, network_origin.y);
        assert_eq!(account_origin.x, network_origin.x + width + 20.0);
        assert_eq!(
            content.size().height,
            network.size().height.max(account.size().height)
        );
    }
    assert!(
        account_origin.x + account.size().width
            <= content.absolute_position().x + content.size().width
    );
    width
}

// Read the displayed client area before PrintWindow, take_snapshot or an
// explicit invalidation can repaint it and hide a restore regression.
fn assert_native_background(window: &MainWindow) {
    use windows_sys::Win32::{Graphics::Gdi::*, UI::HiDpi::GetDpiForWindow};
    let native = native_window(window);
    let theme = window.global::<AppTheme>();
    unsafe {
        assert_eq!(IsIconic(native), 0);
        let dpi = GetDpiForWindow(native);
        let dc = GetDC(native);
        assert!(!dc.is_null());
        let sidebar = GetPixel(dc, scale(30, dpi), scale(280, dpi));
        let background = GetPixel(dc, scale(400, dpi), scale(350, dpi));
        ReleaseDC(native, dc);
        let colorref = |color: slint::Color| {
            u32::from(color.red())
                | (u32::from(color.green()) << 8)
                | (u32::from(color.blue()) << 16)
        };
        assert_eq!(sidebar, colorref(theme.get_chrome()), "displayed sidebar");
        assert_eq!(
            background,
            colorref(theme.get_background()),
            "displayed content background"
        );
    }
}

fn assert_native_chrome(window: &MainWindow) {
    use windows_sys::Win32::{
        Graphics::Gdi::*, Storage::Xps::PrintWindow, UI::HiDpi::GetDpiForWindow,
    };
    let native = native_window(window);
    assert!(!native.is_null());
    let chrome = window.global::<AppTheme>().get_chrome();
    let expected = u32::from(chrome.red())
        | (u32::from(chrome.green()) << 8)
        | (u32::from(chrome.blue()) << 16);
    unsafe {
        let mut rect = RECT::default();
        assert_ne!(GetWindowRect(native, &mut rect), 0);
        let screen = GetWindowDC(native);
        let dc = CreateCompatibleDC(screen);
        let bitmap = CreateCompatibleBitmap(screen, rect.right - rect.left, rect.bottom - rect.top);
        assert!(!dc.is_null() && !bitmap.is_null());
        let previous = SelectObject(dc, bitmap);
        RedrawWindow(
            native,
            ptr::null(),
            ptr::null_mut(),
            RDW_FRAME | RDW_INVALIDATE | RDW_UPDATENOW,
        );
        let captured = PrintWindow(native, dc, 2);
        let dpi = GetDpiForWindow(native);
        let caption = GetPixel(dc, (rect.right - rect.left) / 2, scale(12, dpi));
        let sidebar = GetPixel(dc, scale(30, dpi), scale(280, dpi));
        SelectObject(dc, previous);
        DeleteObject(bitmap);
        DeleteDC(dc);
        ReleaseDC(native, screen);
        assert_ne!(captured, 0);
        assert_eq!(sidebar, expected, "sidebar must use the chrome palette");
        // Caption colors are write-only DWM attributes. Probe support with the
        // text color, without reapplying the caption and masking startup bugs.
        let text = window.global::<AppTheme>().get_text();
        let value =
            u32::from(text.red()) | (u32::from(text.green()) << 8) | (u32::from(text.blue()) << 16);
        let supported = windows_sys::Win32::Graphics::Dwm::DwmSetWindowAttribute(
            native,
            windows_sys::Win32::Graphics::Dwm::DWMWA_TEXT_COLOR as u32,
            (&value as *const u32).cast(),
            std::mem::size_of::<u32>() as u32,
        ) >= 0;
        if supported && !window.global::<AppTheme>().get_high_contrast() {
            assert_eq!(caption, expected, "native title bar must match the sidebar");
        }
    }
}

// Run separately because Winit owns a process-wide native event loop. This
// uses a dedicated host and synthetic worker, without reading local credentials.
#[test]
#[ignore = "requires an interactive Windows desktop; run this test alone"]
fn native_host_keeps_running_hidden_and_recreates_the_slint_window() {
    use slint::winit_030::winit::platform::windows::EventLoopBuilderExtWindows;
    let mut builder = slint::winit_030::winit::event_loop::EventLoop::<slint::winit_030::SlintEvent>::with_user_event();
    builder.with_any_thread(true);
    slint::BackendSelector::new()
        .backend_name("winit".into())
        .renderer_name("software".into())
        .with_winit_event_loop_builder(builder)
        .select()
        .unwrap();
    let instance = unsafe { GetModuleHandleW(ptr::null()) };
    let class_name = wide("Alogin.Windows.SlintLifecycleTest");
    let class = WNDCLASSW {
        lpfnWndProc: Some(host_proc),
        hInstance: instance,
        lpszClassName: class_name.as_ptr(),
        ..Default::default()
    };
    assert_ne!(unsafe { RegisterClassW(&class) }, 0);
    let host = unsafe {
        CreateWindowExW(
            0,
            class_name.as_ptr(),
            wide("Alogin UI test").as_ptr(),
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
    assert!(!host.is_null());
    let (mut app, commands, results) = fixture();
    app.close_window();
    app.host = host;
    app.add_tray().unwrap();
    app.events = Some(NetworkEvents::subscribe(host).unwrap());
    let app = Rc::new(RefCell::new(app));
    APP.with(|slot| *slot.borrow_mut() = Rc::downgrade(&app));
    // Queue the initial open before starting Winit, as the application does.
    post(host, OPEN_MESSAGE, 0);
    let stage = Rc::new(std::cell::Cell::new(0));
    let final_stage = stage.clone();
    let old_window = Rc::new(std::cell::Cell::new(ptr::null_mut()));
    let timer = slint::Timer::default();
    let test_app = app.clone();
    timer.start(
        slint::TimerMode::Repeated,
        Duration::from_millis(150),
        move || {
            let mut app = test_app.borrow_mut();
            match stage.get() {
                0 => {
                    let window = app.window.as_ref().expect("startup window must open");
                    assert_native_chrome(window);
                    assert_ne!(
                        unsafe {
                            SendMessageW(native_window(window), WM_GETICON, ICON_SMALL as usize, 0)
                        },
                        0
                    );
                    app.close_window();
                    assert!(app.window.is_none());
                    results.send(app.view.clone()).unwrap();
                    app.busy = true;
                    post(app.host, RESULT_MESSAGE, 0);
                }
                1 => {
                    assert!(!app.busy, "worker results must be delivered while hidden");
                    post(app.host, OPEN_MESSAGE, 0);
                }
                2 => {
                    let window = app.window.as_ref().unwrap();
                    let native = native_window(window);
                    assert!(!native.is_null());
                    old_window.set(native);
                    assert_native_chrome(window);
                    theme::preview(window, false, false);
                    theme::title_bar(native, window);
                }
                3 => {
                    let window = app.window.as_ref().unwrap();
                    assert_native_chrome(window);
                    theme::preview(window, true, false);
                    theme::title_bar(native_window(window), window);
                }
                4 => {
                    let window = app.window.as_ref().unwrap();
                    assert_native_chrome(window);
                    theme::preview(window, false, true);
                    theme::title_bar(native_window(window), window);
                }
                5 => {
                    let window = app.window.as_ref().unwrap();
                    theme::refresh(window);
                    theme::title_bar(native_window(window), window);
                    window
                        .window()
                        .set_size(slint::LogicalSize::new(860.0, 560.0));
                    save_snapshot(window, "native-home", 860.0, 560.0);
                    // Exercise a real Slint callback through the Win32 host bridge.
                    window.invoke_home_action();
                }
                6 => {
                    assert!(matches!(commands.try_recv().unwrap(), Command::HomeAction));
                    assert!(app.busy);
                    post(app.host, CLOSE_MESSAGE, 0);
                }
                7 => {
                    assert!(app.window.is_none());
                    assert_eq!(unsafe { IsWindow(old_window.get()) }, 0);
                    post(app.host, OPEN_MESSAGE, 0);
                }
                8 => {
                    let window = app.window.as_ref().unwrap();
                    assert_native_chrome(window);
                    theme::preview(window, true, false);
                    theme::title_bar(native_window(window), window);
                }
                9 | 15 | 21 => {
                    let window = app.window.as_ref().unwrap();
                    assert_native_background(window);
                    unsafe { ShowWindow(native_window(window), SW_MINIMIZE) };
                }
                10..=12 | 16..=18 | 22..=24 => {
                    let window = app.window.as_ref().unwrap();
                    assert_ne!(unsafe { IsIconic(native_window(window)) }, 0);
                    // Repeated partial frames while minimized consume the
                    // renderer's cache invalidation before the window returns.
                    window.set_action_label(format!("测试刷新 {}", stage.get()).into());
                }
                13 | 25 => {
                    // Taskbar restoration bypasses App::open_window.
                    let window = app.window.as_ref().unwrap();
                    unsafe { ShowWindow(native_window(window), SW_RESTORE) };
                }
                19 => post(app.host, OPEN_MESSAGE, 0),
                14 | 20 => {
                    let window = app.window.as_ref().unwrap();
                    assert_native_background(window);
                    assert_native_chrome(window);
                    theme::preview(window, false, stage.get() == 20);
                    theme::title_bar(native_window(window), window);
                }
                26 => {
                    let window = app.window.as_ref().unwrap();
                    assert_native_background(window);
                    assert_native_chrome(window);
                    post(app.host, UI_MESSAGE, ID_EXIT);
                }
                _ => panic!("native exit was not dispatched"),
            }
            stage.set(stage.get() + 1);
        },
    );
    slint::run_event_loop_until_quit().unwrap();
    timer.stop();
    assert_eq!(final_stage.get(), 27);
    APP.with(|slot| *slot.borrow_mut() = std::rc::Weak::new());
    let mut app = app.borrow_mut();
    app.events.take();
    app.close_window();
    app.remove_tray();
    unsafe {
        DestroyWindow(host);
        UnregisterClassW(class_name.as_ptr(), instance);
    }
}
