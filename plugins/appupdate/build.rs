const COMMANDS: &[&str] = &["check_latest", "download_and_install"];

fn main() {
    tauri_plugin::Builder::new(COMMANDS)
        .android_path("android")
        .build();
}
