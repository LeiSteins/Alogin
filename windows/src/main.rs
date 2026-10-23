#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
#![deny(unsafe_op_in_unsafe_fn)]

mod account;
mod app;
mod domain;
mod network;
mod platform;
mod storage;
mod strings;
mod ui;

fn main() {
    if let Err(error) = ui::run() {
        ui::show_error(&error);
    }
}
