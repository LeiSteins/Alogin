use super::*;
use crate::domain::{AccountDevice, AccountInfo, AccountOverview, Network};
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
        pending_preferences: None,
        tray: false,
        active_menu: None,
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

fn sample_devices() -> Vec<AccountDevice> {
    vec![
        AccountDevice {
            session_id: "other".into(),
            ip: "10.1.2.4".into(),
            mac: "AA:BB:CC:DD:EE:02".into(),
        },
        AccountDevice {
            session_id: "current".into(),
            ip: "10.1.2.3".into(),
            mac: "AA:BB:CC:DD:EE:01".into(),
        },
    ]
}

#[test]
fn devices_sort_current_first_bind_operations_to_generation_and_render_all_states() {
    headless();
    let (mut app, commands, _) = fixture();
    app.view.online = Online::Yes;
    app.view.network = Network {
        kind: NetworkKind::Ethernet,
        ipv4: "10.1.2.3".into(),
        ..Default::default()
    };
    app.view.device_generation = 7;
    app.view.account_info = AccountInfo {
        username: Some("student-a".into()),
        devices: Some(sample_devices()),
        overview: Some(AccountOverview {
            used_megabytes: Some(2048.0),
            remaining_megabytes: Some(10240.0),
            money_yuan: Some(32.5),
        }),
        ..Default::default()
    };
    app.render(false, false);
    let window = app.window.as_ref().unwrap();
    assert!(window.get_show_devices());
    assert_eq!(
        window.get_devices().row_data(0).unwrap().session_id,
        "current"
    );
    assert!(window.get_devices().row_data(0).unwrap().current);
    assert!(!window.get_devices().row_data(1).unwrap().current);
    let normal_devices = window.get_devices();
    for (mode, dark, contrast) in [
        ("light", false, false),
        ("dark", true, false),
        ("contrast", false, true),
    ] {
        window.set_devices(normal_devices.clone());
        theme::preview(window, dark, contrast);
        snapshot_at_size(window, &format!("{mode}-devices-default"), 860.0, 560.0);
        let refresh = ElementHandle::find_by_accessible_label(window, strings::REFRESH)
            .next()
            .unwrap();
        let fixed = refresh.absolute_position();
        snapshot(window, &format!("{mode}-devices-minimum"));
        let fixed_min = refresh.absolute_position();
        scroll_home_to_devices(window);
        let card = ElementHandle::find_by_element_id(window, "MainWindow::device-card-view")
            .next()
            .unwrap();
        assert!(card.absolute_position().y >= 24.0);
        assert!(card.absolute_position().y + card.size().height <= fixed_min.y - 20.0);
        assert_eq!(refresh.absolute_position(), fixed_min);
        save_snapshot(window, &format!("{mode}-devices-scrolled"), 660.0, 386.0);
        snapshot_at_size(window, &format!("{mode}-devices-wide"), 1200.0, 560.0);
        assert!(fixed.y > 0.0);
        window.set_devices(ModelRc::default());
        snapshot(window, &format!("{mode}-devices-empty"));
        scroll_home_to_devices(window);
        save_snapshot(window, &format!("{mode}-devices-empty"), 660.0, 386.0);
        window.set_devices_available(false);
        window.set_devices_error(strings::DEVICES_FAILED.into());
        snapshot(window, &format!("{mode}-devices-error"));
        scroll_home_to_devices(window);
        save_snapshot(window, &format!("{mode}-devices-error"), 660.0, 386.0);
        window.set_devices_error("".into());
        window.set_devices_available(true);
        window.set_devices(ModelRc::new(VecModel::from(vec![DeviceRow {
            session_id: "current".into(),
            mac: "AA:BB:CC:DD:EE:01".into(),
            ip: "IP：2001:db8:1234:5678:abcd:ef01:2345:6789".into(),
            current: true,
        }])));
        snapshot(window, &format!("{mode}-devices-wrapped"));
        scroll_home_to_devices(window);
        save_snapshot(window, &format!("{mode}-devices-wrapped"), 660.0, 386.0);
    }
    app.render(false, false);
    let window = app.window.as_ref().unwrap();
    // The callback captures the generation displayed at click time.
    scroll_home_to_devices(window);
    click(window, strings::LOGOUT_DEVICE);
    app.view.device_generation = 8;
    app.command(ID_LOGOUT_DEVICE);
    assert!(commands.try_recv().is_err());
    app.render(false, false);
    click(app.window.as_ref().unwrap(), strings::LOGOUT_DEVICE);
    app.command(ID_LOGOUT_DEVICE);
    let Command::LogoutDevice(request) = commands.recv().unwrap() else {
        panic!("expected logout");
    };
    assert_eq!(request.generation, 8);
    assert_eq!(request.username, "student-a");
    assert_eq!(request.device.session_id, "current");
    assert_eq!(request.network, app.view.network);
    assert!(!app.window.as_ref().unwrap().get_ready());
    app.command(ID_LOGOUT_DEVICE);
    assert!(commands.try_recv().is_err());
    app.busy = false;
    app.view.online = Online::No;
    app.render(false, false);
    assert!(!app.window.as_ref().unwrap().get_show_devices());
    assert_eq!(app.window.as_ref().unwrap().get_devices().row_count(), 0);
}

fn scroll_home_to_devices(window: &MainWindow) {
    ElementHandle::find_by_element_id(window, "MainWindow::home-scroll")
        .next()
        .unwrap()
        .scroll(0.0, -2000.0);
    i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(200));
    window.window().take_snapshot().unwrap();
}

fn click(window: &MainWindow, label: &str) {
    ElementHandle::find_by_accessible_label(window, label)
        .next()
        .unwrap_or_else(|| panic!("missing control: {label}"))
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
    window.set_target_name("draft-wifi".into());
    click(window, strings::TOGGLE_NAVIGATION);
    click(window, strings::ACCOUNTS_PAGE);
    click(window, strings::NETWORK_PAGE);
    click(window, strings::HOME_PAGE);
    click(window, strings::TOGGLE_NAVIGATION);
    assert_eq!(window.get_username(), "draft-user");
    assert_eq!(window.get_target_name(), "draft-wifi");
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
    assert_eq!(window.get_target_name(), "draft-wifi");
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
    let p = preferences(
        &[
            " bjut_wifi ".into(),
            "bjut_wifi".into(),
            "".into(),
            "宿舍网络".into(),
        ],
        false,
    )
    .unwrap();
    assert_eq!(p.target_ssids, ["bjut_wifi", "宿舍网络"]);
    assert!(!p.auto_login);
    assert!(preferences(&["网".repeat(11)], true).is_err());
    assert!(preferences(&["a".repeat(32)], true).is_ok());
    assert!(preferences(&["wifi\nother".into()], true).is_err());
}

fn preference_reply(
    app: &mut App,
    commands: &mpsc::Receiver<Command>,
    results: &mpsc::Sender<View>,
    failed: bool,
) -> Preferences {
    let Command::SavePreferences(candidate) =
        commands.recv_timeout(Duration::from_secs(1)).unwrap()
    else {
        panic!("expected immediate preferences save");
    };
    let mut view = app.view.clone();
    view.failed = failed;
    if failed {
        view.message = strings::STORAGE_FAILED.into();
    } else {
        view.preferences = candidate.clone();
        view.message = strings::SAVED.into();
    }
    results.send(view).unwrap();
    app.result();
    candidate
}

#[test]
fn target_edits_save_immediately_and_only_change_the_list_after_success() {
    use slint::platform::WindowEvent;
    headless();
    let (mut app, commands, results) = fixture();
    let window = app.window.as_ref().unwrap();
    click(window, strings::NETWORK_PAGE);
    snapshot(window, "target-list-before-editing");
    click(window, strings::ADD_WIFI);
    assert_editor_centered(window, "MainWindow::target-editor");
    click(window, strings::HOME_PAGE);
    assert_eq!(
        ElementHandle::find_by_accessible_label(window, strings::NETWORK_PAGE)
            .next()
            .unwrap()
            .accessible_checked(),
        Some(true)
    );
    for (name, error) in [
        ("   ".to_string(), strings::INVALID_WIFI),
        ("wifi\nother".to_string(), strings::INVALID_WIFI),
        ("网".repeat(11), strings::SSID_TOO_LONG),
    ] {
        app.window
            .as_ref()
            .unwrap()
            .set_target_name(name.clone().into());
        app.command(ID_ADD_TARGET);
        let window = app.window.as_ref().unwrap();
        assert_eq!(window.get_target_error(), error);
        assert_eq!(window.get_target_name(), name);
        assert!(window.get_target_editor_open());
        assert!(commands.try_recv().is_err());
    }
    app.window
        .as_ref()
        .unwrap()
        .set_target_name("  宿舍网络  ".into());
    app.command(ID_ADD_TARGET);
    let window = app.window.as_ref().unwrap();
    assert!(window.get_saving_target());
    assert!(!window.get_ready());
    snapshot(window, "network-saving-custom");
    click(window, strings::CANCEL);
    assert!(window.get_target_editor_open());
    assert_eq!(target_items(window), ["bjut_wifi"]);
    assert_eq!(app.view.preferences.target_ssids, ["bjut_wifi"]);
    let candidate = preference_reply(&mut app, &commands, &results, true);
    assert_eq!(candidate.target_ssids, ["bjut_wifi", "宿舍网络"]);
    let window = app.window.as_ref().unwrap();
    assert!(window.get_target_editor_open());
    assert!(!window.get_saving_target());
    assert_eq!(window.get_target_error(), strings::STORAGE_FAILED);
    snapshot(window, "network-save-failed");
    assert_eq!(window.get_target_name(), "  宿舍网络  ");
    assert_eq!(target_items(window), ["bjut_wifi"]);
    assert!(app.pending_error.is_none());
    app.command(ID_ADD_TARGET);
    preference_reply(&mut app, &commands, &results, false);
    let window = app.window.as_ref().unwrap();
    assert_eq!(target_items(window), ["bjut_wifi", "宿舍网络"]);
    assert_eq!(window.get_target_index(), 1);
    assert!(!window.get_target_editor_open());
    assert!(window.get_target_name().is_empty());
    click(window, strings::ADD_WIFI);
    window.set_target_name("宿舍网络".into());
    app.command(ID_ADD_TARGET);
    let window = app.window.as_ref().unwrap();
    assert!(!window.get_target_editor_open());
    assert!(commands.try_recv().is_err());
    click(window, strings::ADD_WIFI);
    window.set_target_name("unsaved-input".into());
    window.window().dispatch_event(WindowEvent::KeyPressed {
        text: slint::platform::Key::Escape.into(),
    });
    click(window, strings::HOME_PAGE);
    click(window, strings::NETWORK_PAGE);
    click(window, strings::ADD_WIFI);
    assert_eq!(window.get_target_name(), "unsaved-input");
    click(window, strings::CANCEL);
    app.command(ID_DELETE_TARGET);
    assert_eq!(
        target_items(app.window.as_ref().unwrap()),
        ["bjut_wifi", "宿舍网络"]
    );
    preference_reply(&mut app, &commands, &results, true);
    let window = app.window.as_ref().unwrap();
    assert_eq!(target_items(window), ["bjut_wifi", "宿舍网络"]);
    assert_eq!(window.get_target_index(), 1);
    assert!(app.pending_error.is_some());
    app.command(ID_DELETE_TARGET);
    preference_reply(&mut app, &commands, &results, false);
    assert_eq!(target_items(app.window.as_ref().unwrap()), ["bjut_wifi"]);
    app.command(ID_DELETE_TARGET);
    preference_reply(&mut app, &commands, &results, false);
    assert!(target_items(app.window.as_ref().unwrap()).is_empty());
    assert_eq!(app.window.as_ref().unwrap().get_target_index(), -1);
    app.command(ID_DELETE_TARGET);
    assert!(commands.try_recv().is_err());
}

#[test]
fn adding_current_wifi_saves_immediately_and_existing_targets_are_only_selected() {
    headless();
    let (mut app, commands, results) = fixture();
    app.view.network = Network {
        kind: NetworkKind::Wifi,
        ssid: Some("宿舍网络".into()),
        ipv4: "10.1.2.3".into(),
        ..Network::default()
    };
    app.render(false, false);
    let window = app.window.as_ref().unwrap();
    window.set_target_name("custom-draft".into());
    app.command(ID_ADD_CURRENT_WIFI);
    assert_eq!(target_items(app.window.as_ref().unwrap()), ["bjut_wifi"]);
    app.command(ID_ADD_CURRENT_WIFI);
    let candidate = preference_reply(&mut app, &commands, &results, true);
    assert_eq!(candidate.target_ssids, ["bjut_wifi", "宿舍网络"]);
    assert!(!candidate.auto_login);
    assert_eq!(target_items(app.window.as_ref().unwrap()), ["bjut_wifi"]);
    app.command(ID_ADD_CURRENT_WIFI);
    preference_reply(&mut app, &commands, &results, false);
    let window = app.window.as_ref().unwrap();
    assert_eq!(target_items(window), ["bjut_wifi", "宿舍网络"]);
    assert_eq!(window.get_target_name(), "custom-draft");
    assert_eq!(window.get_target_index(), 1);
    app.command(ID_ADD_CURRENT_WIFI);
    assert!(commands.try_recv().is_err());
}

#[test]
fn auto_login_toggle_saves_once_and_rolls_back_when_persistence_fails() {
    headless();
    let (mut app, commands, results) = fixture();
    let window = app.window.as_ref().unwrap();
    click(window, strings::SETTINGS_PAGE);
    let toggles = Rc::new(std::cell::Cell::new(0));
    let counter = toggles.clone();
    window.on_save_auto_login(move || counter.set(counter.get() + 1));
    click(window, strings::AUTO);
    assert_eq!(toggles.get(), 1);
    assert!(window.get_auto_login());
    app.command(ID_AUTO_LOGIN);
    let candidate = preference_reply(&mut app, &commands, &results, true);
    assert!(candidate.auto_login);
    assert!(!app.window.as_ref().unwrap().get_auto_login());
    assert!(!app.view.preferences.auto_login);
    assert_eq!(toggles.get(), 1);
    click(app.window.as_ref().unwrap(), strings::AUTO);
    app.command(ID_AUTO_LOGIN);
    preference_reply(&mut app, &commands, &results, false);
    assert!(app.window.as_ref().unwrap().get_auto_login());
    assert!(app.view.preferences.auto_login);
    assert_eq!(toggles.get(), 2);
    assert!(commands.try_recv().is_err());
}

#[test]
fn tray_switches_use_confirmed_state_without_a_window_and_preserve_drafts() {
    headless();
    let (mut app, commands, results) = fixture();
    let window = app.window.as_ref().unwrap();
    window.set_username("draft-account".into());
    window.set_password("draft-password".into());
    window.set_target_name("draft-wifi".into());
    // A pending UI value must not override the confirmed tray state.
    window.set_auto_login(true);
    app.command(ID_TRAY_MAINTENANCE);
    assert!(app.busy);
    assert!(!app.view.preferences.auto_login);
    app.command(ID_TRAY_STARTUP);
    let candidate = preference_reply(&mut app, &commands, &results, false);
    assert!(candidate.auto_login);
    let window = app.window.as_ref().unwrap();
    assert!(window.get_auto_login());
    assert_eq!(window.get_username(), "draft-account");
    assert_eq!(window.get_password(), "draft-password");
    assert_eq!(window.get_target_name(), "draft-wifi");
    assert!(commands.try_recv().is_err());

    app.close_window();
    app.command(ID_TRAY_MAINTENANCE);
    assert!(!preference_reply(&mut app, &commands, &results, true).auto_login);
    assert!(app.view.preferences.auto_login);
    assert_eq!(app.pending_error.as_deref(), Some(strings::STORAGE_FAILED));
    app.command(ID_TRAY_MAINTENANCE);
    preference_reply(&mut app, &commands, &results, false);
    assert!(!app.view.preferences.auto_login);

    for (failed, confirmed) in [(true, false), (false, true), (false, false)] {
        app.command(ID_TRAY_STARTUP);
        let Command::SetStartup(requested) = commands.try_recv().unwrap() else {
            panic!("expected startup command")
        };
        assert_ne!(requested, app.view.startup.as_ref().copied().unwrap());
        assert!(app.busy);
        let mut view = app.view.clone();
        view.failed = failed;
        view.startup = Ok(confirmed);
        results.send(view).unwrap();
        app.result();
        assert_eq!(app.view.startup, Ok(confirmed));
        assert!(!app.view.preferences.auto_login);
    }
    app.view.startup = Err(strings::STARTUP_READ_FAILED.into());
    app.command(ID_TRAY_STARTUP);
    assert!(commands.try_recv().is_err());
    app.open_window().unwrap();
    assert!(!app.window.as_ref().unwrap().get_auto_login());
}

#[test]
fn tray_snapshot_distinguishes_networks_unknown_startup_and_busy_states() {
    headless();
    let (mut app, _, _) = fixture();
    for (kind, ssid, label, value) in [
        (
            NetworkKind::Wifi,
            Some("校园 & Wi-Fi"),
            strings::TRAY_WIFI,
            "校园 & Wi-Fi",
        ),
        (
            NetworkKind::Wifi,
            None,
            strings::TRAY_WIFI,
            strings::SSID_UNAVAILABLE,
        ),
        (
            NetworkKind::Ethernet,
            Some("stale-ssid"),
            strings::TRAY_NETWORK,
            strings::ETHERNET,
        ),
        (
            NetworkKind::Unknown,
            None,
            strings::TRAY_NETWORK,
            strings::DISCONNECTED,
        ),
    ] {
        app.view.network.kind = kind;
        app.view.network.ssid = ssid.map(str::to_owned);
        let rows = tray_rows(&app);
        assert_eq!(rows[0].label, label);
        assert_eq!(rows[0].detail.as_deref(), Some(value));
        assert!(!rows[0].enabled);
    }
    app.view.preferences.auto_login = true;
    app.view.startup = Ok(true);
    let rows = tray_rows(&app);
    for id in [ID_TRAY_MAINTENANCE, ID_TRAY_STARTUP] {
        let row = rows.iter().find(|row| row.id == id).unwrap();
        assert_eq!(row.checked, Some(true));
        assert!(row.enabled);
    }
    app.busy = true;
    for row in tray_rows(&app).iter().filter(|row| row.id != 0) {
        assert_eq!(row.enabled, [ID_SHOW, ID_EXIT].contains(&row.id));
    }
    app.view.startup = Err(strings::STARTUP_READ_FAILED.into());
    let rows = tray_rows(&app);
    let startup = rows.iter().find(|row| row.id == ID_TRAY_STARTUP).unwrap();
    assert_eq!(startup.label, strings::TRAY_STARTUP_UNKNOWN);
    assert_eq!(startup.checked, None);
    assert!(!startup.enabled);
    app.busy = false;
    app.view.online = Online::Yes;
    let rows = tray_rows(&app);
    let login = rows.iter().find(|row| row.id == ID_LOGIN).unwrap();
    assert_eq!(login.label, strings::TRAY_LOGGED_IN);
    assert!(!login.enabled);
    app.command(ID_LOGIN);
    assert!(!app.busy);
    app.view.online = Online::No;
    let rows = tray_rows(&app);
    let login = rows.iter().find(|row| row.id == ID_LOGIN).unwrap();
    assert_eq!(login.label, strings::LOGIN);
    assert!(login.enabled);
}

#[test]
fn startup_switch_uses_worker_and_restores_confirmed_state_on_failure() {
    headless();
    let (mut app, commands, results) = fixture();
    let window = app.window.as_ref().unwrap();
    click(window, strings::SETTINGS_PAGE);
    assert!(!window.get_startup_enabled());
    click(window, strings::STARTUP);
    assert!(window.get_startup_enabled());
    app.command(ID_STARTUP);
    assert!(app.busy);
    assert!(matches!(
        commands.try_recv().unwrap(),
        Command::SetStartup(true)
    ));
    app.command(ID_STARTUP);
    assert!(commands.try_recv().is_err());
    // A network result must not complete this pending command.
    let mut background = app.view.clone();
    background.background = true;
    results.send(background).unwrap();
    app.result();
    assert!(app.busy);
    let mut view = app.view.clone();
    view.background = false;
    view.failed = true;
    view.message = strings::STARTUP_WRITE_FAILED.into();
    results.send(view).unwrap();
    app.result();
    assert!(!app.busy);
    assert!(!app.window.as_ref().unwrap().get_startup_enabled());
    click(app.window.as_ref().unwrap(), strings::STARTUP);
    app.command(ID_STARTUP);
    assert!(matches!(
        commands.try_recv().unwrap(),
        Command::SetStartup(true)
    ));
    let mut view = app.view.clone();
    view.failed = false;
    view.startup = Ok(true);
    results.send(view).unwrap();
    app.result();
    assert!(app.window.as_ref().unwrap().get_startup_enabled());
    assert!(!app.view.preferences.auto_login);
    app.close_window();
    app.open_window().unwrap();
    assert!(app.window.as_ref().unwrap().get_startup_enabled());
}

#[test]
fn maintenance_navigation_settings_persist_transactionally_and_share_the_auto_login_switch() {
    headless();
    let (mut app, commands, results) = fixture();
    let window = app.window.as_ref().unwrap();
    window
        .window()
        .set_size(slint::LogicalSize::new(860.0, 760.0));
    click(window, strings::MAINTENANCE_PAGE);
    let target = ElementHandle::find_by_accessible_label(window, strings::NETWORK_PAGE)
        .next()
        .unwrap();
    let maintenance = ElementHandle::find_by_accessible_label(window, strings::MAINTENANCE_PAGE)
        .find(|element| element.accessible_checked() == Some(true))
        .unwrap();
    assert!(maintenance.absolute_position().y > target.absolute_position().y);
    assert_eq!(maintenance.accessible_checked(), Some(true));
    click(
        window,
        &format!("{} {}", strings::CHECK_INTERVAL, strings::SECONDS_60),
    );
    app.command(ID_MAINTENANCE);
    let candidate = preference_reply(&mut app, &commands, &results, true);
    assert_eq!(candidate.maintenance.check_interval_secs, 60);
    assert_eq!(app.window.as_ref().unwrap().get_check_interval(), 30);
    click(
        app.window.as_ref().unwrap(),
        &format!("{} {}", strings::CHECK_INTERVAL, strings::SECONDS_60),
    );
    app.command(ID_MAINTENANCE);
    let candidate = preference_reply(&mut app, &commands, &results, false);
    assert_eq!(candidate.maintenance.check_interval_secs, 60);
    assert_eq!(app.window.as_ref().unwrap().get_check_interval(), 60);
    click(app.window.as_ref().unwrap(), strings::AUTO);
    app.command(ID_AUTO_LOGIN);
    let candidate = preference_reply(&mut app, &commands, &results, false);
    assert!(candidate.auto_login);
    assert_eq!(candidate.maintenance.check_interval_secs, 60);
    click(app.window.as_ref().unwrap(), strings::SETTINGS_PAGE);
    assert!(app.window.as_ref().unwrap().get_auto_login());
    click(app.window.as_ref().unwrap(), strings::TOGGLE_NAVIGATION);
    click(app.window.as_ref().unwrap(), strings::MAINTENANCE_PAGE);
    assert_eq!(app.window.as_ref().unwrap().get_check_interval(), 60);
    app.command(ID_RESUME_MAINTENANCE);
    assert!(matches!(
        commands.try_recv().unwrap(),
        Command::ResumeMaintenance
    ));
}

#[test]
fn background_results_do_not_complete_pending_account_save_or_discard_foreground_results() {
    headless();
    let (mut app, commands, results) = fixture();
    let window = app.window.as_ref().unwrap();
    click(window, strings::ACCOUNTS_PAGE);
    click(window, strings::ADD_ACCOUNT);
    window.set_username("draft-user".into());
    window.set_password("synthetic-draft-password".into());
    app.command(ID_SAVE);
    assert!(matches!(
        commands.try_recv().unwrap(),
        Command::SaveAccount { .. }
    ));
    let mut background = app.view.clone();
    background.background = true;
    background.failed = true;
    background.message = strings::REQUEST_TIMEOUT.into();
    results.send(background).unwrap();
    app.result();
    assert!(app.busy);
    assert!(app.window.as_ref().unwrap().get_saving_account());
    assert!(app.window.as_ref().unwrap().get_account_editor_open());
    assert!(app.pending_error.is_none());
    let mut foreground = app.view.clone();
    foreground.background = false;
    foreground.failed = false;
    foreground.message = strings::SAVED.into();
    let mut following = foreground.clone();
    following.background = true;
    results.send(foreground).unwrap();
    results.send(following).unwrap();
    app.result();
    assert!(!app.busy);
    assert!(!app.window.as_ref().unwrap().get_saving_account());
    assert!(!app.window.as_ref().unwrap().get_account_editor_open());
}

#[test]
fn appearance_saves_through_worker_rolls_back_on_failure_and_preserves_other_preferences() {
    headless();
    let (mut app, commands, results) = fixture();
    let window = app.window.as_ref().unwrap();
    window.set_username("draft-user".into());
    window.set_password("draft-password".into());
    window.set_target_name("draft-wifi".into());
    click(window, strings::SETTINGS_PAGE);
    click(window, strings::APPEARANCE_DARK);
    app.command(ID_APPEARANCE);
    let candidate = preference_reply(&mut app, &commands, &results, false);
    assert_eq!(candidate.appearance, Appearance::Dark);
    assert_eq!(candidate.target_ssids, ["bjut_wifi"]);
    let window = app.window.as_ref().unwrap();
    assert!(window.global::<AppTheme>().get_dark());
    click(window, strings::APPEARANCE_LIGHT);
    app.command(ID_APPEARANCE);
    preference_reply(&mut app, &commands, &results, true);
    let window = app.window.as_ref().unwrap();
    assert_eq!(window.get_appearance(), Appearance::Dark.index());
    assert!(window.global::<AppTheme>().get_dark());
    // Other preference edits must not reset the chosen appearance.
    click(window, strings::AUTO);
    app.command(ID_AUTO_LOGIN);
    let candidate = preference_reply(&mut app, &commands, &results, false);
    assert_eq!(candidate.appearance, Appearance::Dark);
    let window = app.window.as_ref().unwrap();
    click(window, strings::ABOUT_PAGE);
    click(window, strings::TOGGLE_NAVIGATION);
    click(window, strings::ACCOUNTS_PAGE);
    assert_eq!(window.get_username(), "draft-user");
    assert_eq!(window.get_password(), "draft-password");
    assert_eq!(window.get_target_name(), "draft-wifi");
}

#[test]
fn about_links_use_fixed_destinations_and_update_entry_is_inactive() {
    headless();
    let (app, commands, _) = fixture();
    let window = app.window.as_ref().unwrap();
    click(window, strings::ABOUT_PAGE);
    snapshot(window, "about-bottom-navigation");
    ElementHandle::find_by_element_id(window, "AboutPage::about-scroll")
        .next()
        .unwrap()
        .scroll(0.0, -2000.0);
    window.window().take_snapshot().unwrap();
    let links = Rc::new(RefCell::new(Vec::new()));
    let opened = links.clone();
    window.on_open_about_link(move |index| opened.borrow_mut().push(index));
    for label in [strings::HOMEPAGE, strings::SOURCE, strings::LICENSE_DETAILS] {
        click(window, label);
    }
    click(window, strings::CHECK_UPDATES);
    assert_eq!(*links.borrow(), [0, 1, 2]);
    assert!(commands.try_recv().is_err());
    let settings = ElementHandle::find_by_accessible_label(window, strings::SETTINGS_PAGE)
        .next()
        .unwrap();
    let about = ElementHandle::find_by_accessible_label(window, strings::ABOUT_PAGE)
        .next()
        .unwrap();
    assert!(about.absolute_position().y > settings.absolute_position().y);
    assert!(about.absolute_position().y + about.size().height >= 370.0);
}

#[test]
fn current_wifi_addition_rejects_unavailable_names_and_obeys_busy_state() {
    headless();
    let (mut app, commands, _) = fixture();
    for (kind, ssid) in [
        (NetworkKind::Unknown, Some("wifi".into())),
        (NetworkKind::Ethernet, Some("wifi".into())),
        (NetworkKind::Wifi, None),
        (NetworkKind::Wifi, Some(String::new())),
        (NetworkKind::Wifi, Some(" ".into())),
        (NetworkKind::Wifi, Some(" wifi ".into())),
        (NetworkKind::Wifi, Some("网".repeat(11))),
        (NetworkKind::Wifi, Some("wifi\nother".into())),
        (NetworkKind::Wifi, Some("wifi\rother".into())),
    ] {
        app.view.network.kind = kind;
        app.view.network.ssid = ssid;
        app.render(false, false);
        assert!(!app.window.as_ref().unwrap().get_can_add_current_wifi());
        app.command(ID_ADD_CURRENT_WIFI);
        assert_eq!(target_items(app.window.as_ref().unwrap()), ["bjut_wifi"]);
    }
    app.view.network.kind = NetworkKind::Wifi;
    app.view.network.ssid = Some("a".repeat(32));
    app.busy = true;
    app.render(false, false);
    let window = app.window.as_ref().unwrap();
    click(window, strings::NETWORK_PAGE);
    let additions = Rc::new(std::cell::Cell::new(0));
    let add_count = additions.clone();
    window.on_add_current_wifi(move || add_count.set(add_count.get() + 1));
    click(window, strings::ADD_CURRENT_WIFI);
    assert_eq!(additions.get(), 0);
    app.command(ID_ADD_CURRENT_WIFI);
    assert_eq!(target_items(app.window.as_ref().unwrap()), ["bjut_wifi"]);
    app.busy = false;
    app.render(false, false);
    app.command(ID_ADD_CURRENT_WIFI);
    let window = app.window.as_ref().unwrap();
    assert_eq!(target_items(window), ["bjut_wifi"]);
    let Command::SavePreferences(candidate) =
        commands.recv_timeout(Duration::from_secs(1)).unwrap()
    else {
        panic!("expected immediate save");
    };
    assert_eq!(
        candidate.target_ssids,
        ["bjut_wifi".to_string(), "a".repeat(32)]
    );
    assert!(commands.try_recv().is_err());
}

#[test]
fn mouse_and_keyboard_activate_controls_and_visible_account_list() {
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
    find(strings::SETTINGS_PAGE).mock_single_click(PointerEventButton::Left);
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
    find(strings::ADD_ACCOUNT).mock_single_click(PointerEventButton::Left);
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
    click(window, strings::CANCEL);
    find(strings::DELETE).mock_single_click(PointerEventButton::Left);
    snapshot(window, "button-pointer-focus");
    window
        .window()
        .dispatch_event(WindowEvent::KeyPressed { text: "\n".into() });
    window
        .window()
        .dispatch_event(WindowEvent::KeyReleased { text: "\n".into() });
    snapshot(window, "button-keyboard-focus");
    assert_eq!(find("student-a").accessible_checked(), Some(true));
    assert_eq!(find("student-b").accessible_checked(), Some(false));
    let selections = Rc::new(std::cell::Cell::new(0));
    let selection_count = selections.clone();
    window.on_select_account(move || selection_count.set(selection_count.get() + 1));
    find("student-b").mock_single_click(PointerEventButton::Left);
    assert_eq!(window.get_account_index(), 1);
    assert_eq!(selections.get(), 1);
    snapshot(window, "account-list-pointer-focus");
    find("student-b").mock_single_click(PointerEventButton::Left);
    assert_eq!(selections.get(), 1);
    window.set_account_index(0);
    window
        .window()
        .dispatch_event(WindowEvent::KeyPressed { text: " ".into() });
    window
        .window()
        .dispatch_event(WindowEvent::KeyReleased { text: " ".into() });
    assert_eq!(window.get_account_index(), 1);
    assert_eq!(selections.get(), 2);
    snapshot(window, "account-list-keyboard-focus");
    window.set_ready(false);
    find("student-a").mock_single_click(PointerEventButton::Left);
    assert_eq!(window.get_account_index(), 1);
    assert_eq!(selections.get(), 2);
}

#[test]
fn account_editor_blocks_background_preserves_drafts_and_waits_for_save_result() {
    use slint::platform::{PointerEventButton, WindowEvent};

    headless();
    let (mut app, commands, results) = fixture();
    let window = app.window.as_ref().unwrap();
    click(window, strings::ACCOUNTS_PAGE);
    snapshot(window, "accounts-before-editor");
    let before = window.window().take_snapshot().unwrap();
    click(window, strings::ADD_ACCOUNT);
    snapshot(window, "accounts-editor-open");
    assert!(window.get_account_editor_open());
    assert!(window.get_username().is_empty());
    assert_account_editor_centered(window);
    let after = window.window().take_snapshot().unwrap();
    let corner = |pixels: &slint::SharedPixelBuffer<slint::Rgba8Pixel>| {
        pixels.as_slice()[10 * pixels.width() as usize + 10]
    };
    assert!(u16::from(corner(&after).r) < u16::from(corner(&before).r));
    let network = ElementHandle::find_by_accessible_label(window, strings::NETWORK_PAGE)
        .next()
        .unwrap();
    network.mock_single_click(PointerEventButton::Left);
    network.invoke_accessible_default_action();
    assert_eq!(network.accessible_checked(), Some(false));
    assert_eq!(network.accessible_enabled(), Some(false));
    window.set_username("draft-user".into());
    window.set_password("test-only-password".into());
    window.window().dispatch_event(WindowEvent::KeyPressed {
        text: slint::platform::Key::Escape.into(),
    });
    assert!(!window.get_account_editor_open());
    click(window, strings::NETWORK_PAGE);
    click(window, strings::ACCOUNTS_PAGE);
    click(window, strings::ADD_ACCOUNT);
    assert_eq!(window.get_username(), "draft-user");
    assert_eq!(window.get_password(), "test-only-password");
    app.command(ID_SAVE);
    assert!(matches!(
        commands.recv().unwrap(),
        Command::SaveAccount { .. }
    ));
    let window = app.window.as_ref().unwrap();
    assert!(window.get_account_editor_open());
    assert!(window.get_saving_account());
    click(window, strings::CANCEL);
    assert!(window.get_account_editor_open());
    assert!(window.get_password().is_empty());
    let mut failed = app.view.clone();
    failed.failed = true;
    failed.message = strings::STORAGE_FAILED.into();
    results.send(failed).unwrap();
    app.result();
    let window = app.window.as_ref().unwrap();
    assert!(window.get_account_editor_open());
    assert!(!window.get_saving_account());
    assert_eq!(window.get_username(), "draft-user");
    window.set_password("test-only-password".into());
    app.command(ID_SAVE);
    assert!(matches!(
        commands.recv().unwrap(),
        Command::SaveAccount { .. }
    ));
    let mut saved = app.view.clone();
    saved.failed = false;
    saved.accounts.push("draft-user".into());
    saved.selected = Some("draft-user".into());
    results.send(saved).unwrap();
    app.result();
    let window = app.window.as_ref().unwrap();
    assert!(!window.get_account_editor_open());
    assert!(!window.get_saving_account());
    assert_eq!(window.get_account_index(), 2);
    click(window, strings::EDIT_ACCOUNT);
    assert_eq!(window.get_username(), "draft-user");
    assert!(window.get_password().is_empty());
    click(window, strings::CANCEL);
}

fn assert_account_editor_centered(window: &MainWindow) {
    assert_editor_centered(window, "MainWindow::account-editor");
}

fn assert_editor_centered(window: &MainWindow, id: &str) {
    let editor = ElementHandle::find_by_element_id(window, id)
        .next()
        .unwrap();
    let position = editor.absolute_position();
    let size = editor.size();
    let scale = window.window().scale_factor();
    let window_size = window.window().size();
    assert!((position.x * 2.0 + size.width - window_size.width as f32 / scale).abs() < 1.0);
    assert!((position.y * 2.0 + size.height - window_size.height as f32 / scale).abs() < 1.0);
    assert!(position.x >= 24.0 && position.y >= 24.0);
}

#[test]
fn network_notifications_leave_the_window_ready_and_allow_manual_commands_without_a_reply() {
    headless();
    let (mut app, commands, results) = fixture();
    app.view.message = strings::LOGGED_IN.into();
    app.window
        .as_ref()
        .unwrap()
        .set_username("unsaved-account".into());
    app.window
        .as_ref()
        .unwrap()
        .set_password("unsaved-password".into());
    app.submit(Command::NetworkChanged);
    assert!(matches!(commands.recv().unwrap(), Command::NetworkChanged));
    assert!(!app.busy);
    assert!(app.window.as_ref().unwrap().get_ready());
    assert_eq!(app.view.message, strings::LOGGED_IN);
    // The worker intentionally sends no reply for an unchanged notification.
    app.submit(Command::Refresh);
    assert!(matches!(commands.recv().unwrap(), Command::Refresh));
    assert!(app.busy);
    let mut background = app.view.clone();
    background.background = true;
    results.send(background).unwrap();
    app.result();
    assert!(app.busy);
    assert!(!app.window.as_ref().unwrap().get_ready());
    assert_eq!(
        app.window.as_ref().unwrap().get_username(),
        "unsaved-account"
    );
    assert_eq!(
        app.window.as_ref().unwrap().get_password(),
        "unsaved-password"
    );
    let mut foreground = app.view.clone();
    foreground.background = false;
    results.send(foreground).unwrap();
    app.result();
    assert!(!app.busy);
    assert!(app.window.as_ref().unwrap().get_ready());
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
        ..Default::default()
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
        ..Default::default()
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
            ("maintenance", strings::MAINTENANCE_PAGE),
            ("settings", strings::SETTINGS_PAGE),
            ("about", strings::ABOUT_PAGE),
        ] {
            click(window, label);
            if page == "maintenance" {
                let resume =
                    ElementHandle::find_by_accessible_label(window, strings::RESUME_MAINTENANCE)
                        .next()
                        .unwrap();
                resume.scroll(0.0, 10000.0);
                i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(200));
                window.set_maintenance_status(strings::MAINTENANCE_OFF.into());
                window.set_maintenance_detail("".into());
            }
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
            if page == "settings" {
                window.set_startup_error(strings::STARTUP_READ_FAILED.into());
                snapshot(window, &format!("{mode}-settings-startup-error"));
                snapshot_at_size(
                    window,
                    &format!("{mode}-settings-startup-error-default"),
                    860.0,
                    560.0,
                );
                window.set_startup_error("".into());
            }
            if page == "maintenance" {
                window.set_maintenance_status(strings::MAINTENANCE_PASSWORD.into());
                window.set_maintenance_detail("最近检测：暂时无法确认校园网认证状态，将在网络恢复后继续检测。请检查当前选中账号的凭据。".into());
                snapshot(window, &format!("{mode}-maintenance-wrapped-error"));
                let status =
                    ElementHandle::find_by_accessible_label(window, strings::RESUME_MAINTENANCE)
                        .next()
                        .unwrap();
                status.scroll(0.0, -1000.0);
                i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(200));
                save_snapshot(
                    window,
                    &format!("{mode}-maintenance-options-scrolled"),
                    660.0,
                    386.0,
                );
                window.set_maintenance_status(strings::MAINTENANCE_ONLINE.into());
                window.set_maintenance_detail(strings::MAINTENANCE_CHECK_ONLINE.into());
            }
            if page == "accounts" {
                window.set_accounts(ModelRc::new(VecModel::from(
                    Vec::<slint::SharedString>::new(),
                )));
                window.set_account_index(-1);
                snapshot(window, &format!("{mode}-accounts-empty"));
                window.set_accounts(ModelRc::new(VecModel::from(
                    (0..20)
                        .map(|index| {
                            format!("student-{index:02}-with-a-long-saved-account-name").into()
                        })
                        .collect::<Vec<slint::SharedString>>(),
                )));
                window.set_account_index(0);
                snapshot(window, &format!("{mode}-accounts-many"));
                let first = ElementHandle::find_by_accessible_label(
                    window,
                    "student-00-with-a-long-saved-account-name",
                )
                .next()
                .unwrap();
                let before = first.absolute_position();
                first.scroll(0.0, -1000.0);
                i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(200));
                window.window().take_snapshot().unwrap();
                assert!(first.absolute_position().y < before.y);
                save_snapshot(
                    window,
                    &format!("{mode}-accounts-many-scrolled"),
                    660.0,
                    386.0,
                );
                window.set_accounts(ModelRc::new(VecModel::from(vec![
                    "student-a".into(),
                    "student-b".into(),
                ])));
                window.set_account_index(0);
                click(window, strings::ADD_ACCOUNT);
                snapshot(window, &format!("{mode}-add-account"));
                assert_account_editor_centered(window);
                snapshot_at_size(window, &format!("{mode}-add-account-default"), 860.0, 560.0);
                assert_account_editor_centered(window);
                click(window, strings::CANCEL);
                click(window, strings::EDIT_ACCOUNT);
                snapshot(window, &format!("{mode}-edit-account"));
                click(window, strings::CANCEL);
            }
            if page == "network" {
                window.set_can_add_current_wifi(false);
                snapshot(window, &format!("{mode}-network-wifi-unavailable"));
                window.set_can_add_current_wifi(true);
                set_target_items(window, &[]);
                snapshot(window, &format!("{mode}-network-empty"));
                snapshot_at_size(
                    window,
                    &format!("{mode}-network-empty-default"),
                    860.0,
                    560.0,
                );
                set_target_items(
                    window,
                    &(0..20)
                        .map(|i| format!("campus-wifi-{i:02}-long-ssid-name"))
                        .collect::<Vec<_>>(),
                );
                snapshot(window, &format!("{mode}-network-many"));
                window.set_target_index(19);
                snapshot(window, &format!("{mode}-network-many-selected"));
                let last = ElementHandle::find_by_accessible_label(
                    window,
                    "campus-wifi-19-long-ssid-name",
                )
                .next()
                .unwrap();
                let save = ElementHandle::find_by_accessible_label(window, strings::DELETE_WIFI)
                    .next()
                    .unwrap();
                assert!(last.absolute_position().y > 36.0);
                assert!(
                    last.absolute_position().y + last.size().height < save.absolute_position().y
                );
                click(window, strings::ADD_WIFI);
                snapshot(window, &format!("{mode}-network-add-custom"));
                assert_editor_centered(window, "MainWindow::target-editor");
                snapshot_at_size(
                    window,
                    &format!("{mode}-network-add-custom-default"),
                    860.0,
                    560.0,
                );
                assert_editor_centered(window, "MainWindow::target-editor");
                window.set_target_error(strings::SSID_TOO_LONG.into());
                window.set_target_name("网".repeat(11).into());
                snapshot(window, &format!("{mode}-network-invalid-name"));
                assert_editor_centered(window, "MainWindow::target-editor");
                click(window, strings::CANCEL);
                window.set_target_error("".into());
                window.set_target_name("".into());
                window.set_target_index(-1);
                set_target_items(window, &["bjut_wifi".into()]);
            }
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
        window.set_devices_available(false);
        window.set_devices(ModelRc::default());
        window.set_devices_error(strings::DEVICES_FAILED.into());
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
        window.set_devices_available(true);
        window.set_devices_error("".into());
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
        let summary_height = network.size().height + 20.0 + account.size().height;
        assert!(content.size().height >= summary_height);
        assert_eq!(
            content.size().height > summary_height,
            window.get_show_devices()
        );
    } else {
        assert_eq!(account_origin.y, network_origin.y);
        assert_eq!(account_origin.x, network_origin.x + width + 20.0);
        let summary_height = network.size().height.max(account.size().height);
        assert!(content.size().height >= summary_height);
        assert_eq!(
            content.size().height > summary_height,
            window.get_show_devices()
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

// Exercise the installed thread hook with synthetic menu input, without moving
// the desktop cursor or sending input to another application.
fn native_menu_message(app: &App, id: usize, mouse: bool) -> MSG {
    let theme = app
        .active_menu
        .as_ref()
        .expect("tray menu must remain open");
    let menu = theme.handle();
    let index = (0..unsafe { GetMenuItemCount(menu) })
        .find(|index| unsafe { GetMenuItemID(menu, *index) } as usize == id)
        .unwrap();
    let mut rect = RECT::default();
    assert_ne!(
        unsafe { GetMenuItemRect(ptr::null_mut(), menu, index as u32, &mut rect) },
        0
    );
    let point = POINT {
        x: (rect.left + rect.right) / 2,
        y: (rect.top + rect.bottom) / 2,
    };
    if !mouse {
        for index in 0..unsafe { GetMenuItemCount(menu) } {
            unsafe {
                HiliteMenuItem(app.host, menu, index as u32, MF_BYPOSITION | MF_UNHILITE);
            }
        }
        unsafe {
            HiliteMenuItem(app.host, menu, id as u32, MF_BYCOMMAND | MF_HILITE);
        }
    }
    MSG {
        hwnd: theme.popup_window(),
        message: if mouse { WM_LBUTTONUP } else { WM_KEYDOWN },
        wParam: if mouse { 0 } else { 0x0d },
        pt: point,
        ..Default::default()
    }
}

fn native_keep_menu_click(app: &App, id: usize, mouse: bool) {
    let message = native_menu_message(app, id, mouse);
    assert_ne!(unsafe { CallMsgFilterW(&message, MSGF_MENU as i32) }, 0);
}

fn assert_native_tray_has_no_outline(popup: HWND) {
    use windows_sys::Win32::{
        Graphics::Gdi::*, Storage::Xps::PrintWindow, UI::HiDpi::GetDpiForWindow,
    };
    unsafe {
        let mut rect = RECT::default();
        assert_ne!(GetWindowRect(popup, &mut rect), 0);
        let (width, height) = (rect.right - rect.left, rect.bottom - rect.top);
        let screen = GetWindowDC(popup);
        let dc = CreateCompatibleDC(screen);
        let bitmap = CreateCompatibleBitmap(screen, width, height);
        assert!(!dc.is_null() && !bitmap.is_null());
        let previous = SelectObject(dc, bitmap);
        let captured = PrintWindow(popup, dc, 0);
        let background = GetPixel(dc, width / 2, scale(3, GetDpiForWindow(popup)));
        let edges = [
            (width / 2, 0),
            (width / 2, height - 1),
            (0, height / 2),
            (width - 1, height / 2),
        ]
        .map(|(x, y)| GetPixel(dc, x, y));
        SelectObject(dc, previous);
        DeleteObject(bitmap);
        DeleteDC(dc);
        ReleaseDC(popup, screen);
        assert_ne!(captured, 0);
        assert_ne!(background, CLR_INVALID);
        assert_eq!(
            edges, [background; 4],
            "popup frame pixels must match the menu background"
        );
    }
}

// Physical network notifications may arrive during this desktop test. The
// synthetic worker does not reply to them; they must not reorder manual checks.
fn native_command(commands: &mpsc::Receiver<Command>) -> Option<Command> {
    commands
        .try_iter()
        .find(|command| !matches!(command, Command::NetworkChanged))
}

thread_local! {
    static NATIVE_TEST_STEP: RefCell<Option<Box<dyn FnMut()>>> = RefCell::new(None);
}
unsafe extern "system" fn native_test_tick(_: HWND, _: u32, _: usize, _: u32) {
    // Slint's deadline timer advances on Winit NewEvents, which a native modal
    // menu does not emit. Drive this test with a native timer and real proxy
    // messages, just like worker/network results arriving during menu tracking.
    let _ = slint::invoke_from_event_loop(|| {
        NATIVE_TEST_STEP.with(|slot| {
            if let Some(step) = slot.borrow_mut().as_mut() {
                step();
            }
        });
    });
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
    let tracked_menu = std::cell::Cell::new(ptr::null_mut());
    let test_app = app.clone();
    NATIVE_TEST_STEP.with(|slot| {
        *slot.borrow_mut() = Some(Box::new(move || {
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
                    app.view.preferences.appearance = Appearance::Dark;
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
                    assert!(window.global::<AppTheme>().get_dark());
                    window.set_appearance(Appearance::Light.index());
                    theme::refresh(window);
                    theme::title_bar(native, window);
                }
                3 => {
                    let window = app.window.as_ref().unwrap();
                    assert_native_chrome(window);
                    assert!(!window.global::<AppTheme>().get_dark());
                    window.set_appearance(Appearance::Dark.index());
                    theme::refresh(window);
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
                    assert!(matches!(
                        native_command(&commands).expect("expected manual command"),
                        Command::HomeAction
                    ));
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
                    app.close_window();
                    app.busy = false;
                    app.view.online = Online::No;
                    app.view.preferences.auto_login = false;
                    app.view.startup = Ok(false);
                    post(app.host, TRAY_OPEN_MESSAGE, 0);
                }
                27 => {
                    tracked_menu.set(app.active_menu.as_ref().unwrap().handle());
                    // Validate the actual popup geometry, not just an offscreen row.
                    use windows_sys::Win32::{Graphics::Gdi::*, UI::HiDpi::GetDpiForWindow};
                    let popup = app.active_menu.as_ref().unwrap().popup_window();
                    assert!(!popup.is_null());
                    assert_native_tray_has_no_outline(popup);
                    let mut rect = RECT::default();
                    unsafe {
                        assert_ne!(GetWindowRect(popup, &mut rect), 0);
                        assert!(rect.right - rect.left <= scale(240, GetDpiForWindow(popup)));
                        let region = CreateRectRgn(0, 0, 0, 0);
                        assert_eq!(GetWindowRgn(popup, region), COMPLEXREGION);
                        assert_eq!(PtInRegion(region, 0, 0), 0);
                        assert_ne!(PtInRegion(region, (rect.right - rect.left) / 2, 1), 0);
                        let corner = MSG {
                            hwnd: popup,
                            message: WM_LBUTTONUP,
                            pt: POINT {
                                x: rect.right - 1,
                                y: rect.bottom - 1,
                            },
                            ..Default::default()
                        };
                        assert_eq!(
                            CallMsgFilterW(&corner, MSGF_MENU as i32),
                            0,
                            "a clipped corner must not activate Exit"
                        );
                        DeleteObject(region);
                    }
                    native_keep_menu_click(&app, ID_TRAY_MAINTENANCE, true);
                }
                28 => {
                    assert!(
                        app.busy,
                        "menu command must run before the native menu closes"
                    );
                    let Command::SavePreferences(preferences) =
                        native_command(&commands).expect("expected manual command")
                    else {
                        panic!("expected maintenance save")
                    };
                    assert!(preferences.auto_login);
                    let mut view = app.view.clone();
                    view.preferences = preferences;
                    results.send(view).unwrap();
                    post(app.host, RESULT_MESSAGE, 0);
                }
                29 => {
                    assert!(
                        !app.busy,
                        "worker results must run inside the native menu loop"
                    );
                    assert_eq!(
                        app.active_menu.as_ref().unwrap().handle(),
                        tracked_menu.get()
                    );
                    assert!(app.view.preferences.auto_login);
                    assert_ne!(
                        unsafe {
                            GetMenuState(
                                tracked_menu.get(),
                                ID_TRAY_MAINTENANCE as u32,
                                MF_BYCOMMAND,
                            )
                        } & MF_CHECKED,
                        0
                    );
                    native_keep_menu_click(&app, ID_REFRESH, false);
                }
                30 => {
                    assert!(matches!(
                        native_command(&commands).expect("expected manual command"),
                        Command::Refresh
                    ));
                    let mut view = app.view.clone();
                    view.online = Online::Yes;
                    results.send(view).unwrap();
                    post(app.host, RESULT_MESSAGE, 0);
                }
                31 => {
                    assert!(!app.busy);
                    assert_eq!(
                        app.active_menu.as_ref().unwrap().handle(),
                        tracked_menu.get()
                    );
                    let mut text = [0u16; 32];
                    let length = unsafe {
                        GetMenuStringW(
                            tracked_menu.get(),
                            ID_LOGIN as u32,
                            text.as_mut_ptr(),
                            text.len() as i32,
                            MF_BYCOMMAND,
                        )
                    };
                    assert_eq!(
                        String::from_utf16_lossy(&text[..length as usize]),
                        strings::TRAY_LOGGED_IN
                    );
                    native_keep_menu_click(&app, ID_LOGIN, true);
                    assert!(native_command(&commands).is_none());
                    native_keep_menu_click(&app, ID_TRAY_STARTUP, false);
                }
                32 => {
                    assert!(matches!(
                        native_command(&commands).expect("expected manual command"),
                        Command::SetStartup(true)
                    ));
                    let mut view = app.view.clone();
                    view.failed = true;
                    view.message = strings::STARTUP_WRITE_FAILED.into();
                    results.send(view).unwrap();
                    post(app.host, RESULT_MESSAGE, 0);
                }
                33 => {
                    assert!(!app.busy);
                    assert!(
                        app.window.is_none(),
                        "failure must not dismiss the menu to show a modal"
                    );
                    assert_eq!(
                        app.pending_error.as_deref(),
                        Some(strings::STARTUP_WRITE_FAILED)
                    );
                    assert_eq!(
                        app.active_menu.as_ref().unwrap().handle(),
                        tracked_menu.get()
                    );
                    assert_eq!(
                        unsafe {
                            GetMenuState(tracked_menu.get(), ID_TRAY_STARTUP as u32, MF_BYCOMMAND)
                        } & MF_CHECKED,
                        0
                    );
                    app.pending_error = None; // Suppress the synthetic error dialog at test teardown.
                    let mut escape = native_menu_message(&app, ID_SHOW, false);
                    escape.wParam = 0x1b;
                    assert_eq!(unsafe { CallMsgFilterW(&escape, MSGF_MENU as i32) }, 0);
                    native_keep_menu_click(&app, ID_SHOW, false);
                }
                34 => {
                    assert!(app.active_menu.is_none());
                    assert_eq!(unsafe { IsMenu(tracked_menu.get()) }, 0);
                    assert!(
                        app.window.is_some(),
                        "Open Alogin must dismiss the menu and open the window"
                    );
                    post(app.host, UI_MESSAGE, ID_EXIT);
                }
                _ => panic!("native exit was not dispatched"),
            }
            stage.set(stage.get() + 1);
        }))
    });
    let timer = unsafe { SetTimer(ptr::null_mut(), 0, 500, Some(native_test_tick)) };
    assert_ne!(timer, 0);
    let (finished, deadline) = mpsc::channel::<()>();
    let test_host = host as usize;
    let watchdog = std::thread::spawn(move || {
        if deadline.recv_timeout(Duration::from_secs(35)).is_err() {
            post(test_host as HWND, WM_CANCELMODE, 0);
        }
    });
    slint::run_event_loop_until_quit().unwrap();
    finished.send(()).unwrap();
    watchdog.join().unwrap();
    unsafe {
        KillTimer(ptr::null_mut(), timer);
    }
    NATIVE_TEST_STEP.with(|slot| slot.borrow_mut().take());
    assert_eq!(final_stage.get(), 35);
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
