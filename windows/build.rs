fn main() {
    slint_build::compile_with_config(
        "ui/main.slint",
        slint_build::CompilerConfiguration::new()
            .with_style("fluent".into())
            .with_debug_info(std::env::var("PROFILE").as_deref() != Ok("release")),
    )
    .expect("Slint interface must compile");
    embed_resource::compile_for_everything("resources/app.rc", embed_resource::NONE)
        .manifest_required()
        .expect("Windows application icon resources must compile");
    println!("cargo:rerun-if-changed=resources/app.rc");
    println!("cargo:rerun-if-changed=resources/alogin.ico");
    if std::env::var("CARGO_CFG_TARGET_ENV").as_deref() == Ok("msvc") {
        let manifest = std::path::Path::new(&std::env::var("CARGO_MANIFEST_DIR").unwrap())
            .join("app.manifest");
        println!("cargo:rustc-link-arg-bin=alogin-windows=/MANIFEST:EMBED");
        println!(
            "cargo:rustc-link-arg-bin=alogin-windows=/MANIFESTINPUT:{}",
            manifest.display()
        );
        println!("cargo:rerun-if-changed=app.manifest");
    }
}
