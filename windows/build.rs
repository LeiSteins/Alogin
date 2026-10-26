fn main() {
    slint_build::compile_with_config(
        "ui/main.slint",
        slint_build::CompilerConfiguration::new()
            .with_style("fluent".into())
            .with_debug_info(std::env::var("PROFILE").as_deref() != Ok("release")),
    )
    .expect("Slint interface must compile");
    let version = std::env::var("CARGO_PKG_VERSION").unwrap();
    let numeric_version = format!("{version}.0");
    let output = std::path::PathBuf::from(std::env::var("OUT_DIR").unwrap());
    let resource = std::fs::read_to_string("resources/app.rc")
        .unwrap()
        .replace("@VERSION_COMMA@", &numeric_version.replace('.', ","))
        .replace("@VERSION@", &version);
    let resource_path = output.join("app.rc");
    std::fs::write(&resource_path, resource).unwrap();
    embed_resource::compile_for_everything(resource_path, embed_resource::NONE)
        .manifest_required()
        .expect("Windows application icon resources must compile");
    println!("cargo:rerun-if-changed=resources/app.rc");
    println!("cargo:rerun-if-changed=resources/alogin.ico");
    if std::env::var("CARGO_CFG_TARGET_ENV").as_deref() == Ok("msvc") {
        let manifest = output.join("app.manifest");
        let contents = std::fs::read_to_string("app.manifest")
            .unwrap()
            .replace("@VERSION@", &numeric_version);
        std::fs::write(&manifest, contents).unwrap();
        println!("cargo:rustc-link-arg-bin=alogin-windows=/MANIFEST:EMBED");
        println!(
            "cargo:rustc-link-arg-bin=alogin-windows=/MANIFESTINPUT:{}",
            manifest.display()
        );
        println!("cargo:rerun-if-changed=app.manifest");
    }
}
