//! Slint 1.18.1 workaround for Windows discarding displayed software frames.
//!
//! Mirrors https://github.com/slint-ui/slint/pull/13568. Remove this module and
//! the version-matched i-slint-core dependency when upgrading to a fixed Slint.
use i_slint_core::{lengths::LogicalRect, window::WindowInner};
use windows_sys::Win32::{Foundation::RECT, Graphics::Gdi::GetUpdateRect};

pub fn mark_native_update_region_dirty(window: &slint::Window) {
    use raw_window_handle::{HasWindowHandle, RawWindowHandle};
    use slint::winit_030::WinitWindowAccessor;

    window.with_winit_window(|native| {
        let Ok(handle) = native.window_handle() else {
            return;
        };
        let RawWindowHandle::Win32(handle) = handle.as_raw() else {
            return;
        };
        let mut rect = RECT::default();
        // Winit 0.30.13 delivers RedrawRequested before validating WM_PAINT.
        // Slint's own internal paints have no update rectangle, so ordinary
        // partial frames do not incur a full repaint. Do not validate it here.
        if unsafe { GetUpdateRect(handle.hwnd.get() as _, &mut rect, 0) } == 0 {
            return;
        }
        let scale = window.scale_factor();
        let region = LogicalRect::new(
            (rect.left as f32 / scale, rect.top as f32 / scale).into(),
            (
                (rect.right - rect.left) as f32 / scale,
                (rect.bottom - rect.top) as f32 / scale,
            )
                .into(),
        );
        WindowInner::from_pub(window)
            .window_adapter()
            .renderer()
            .mark_dirty_region(region.into());
    });
}
