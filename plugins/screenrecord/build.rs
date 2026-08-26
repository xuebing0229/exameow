const COMMANDS: &[&str] = &[
    "start_session",
    "start_float_session",
    "begin",
    "adjust",
    "stop",
    "show_answer",
];

fn main() {
    tauri_plugin::Builder::new(COMMANDS)
        .android_path("android")
        .build();
}
