use tauri::{
    plugin::{Builder, TauriPlugin},
    Runtime,
};

pub fn init<R: Runtime>() -> TauriPlugin<R> {
    Builder::new("appupdate")
        .setup(|_app, api| {
            #[cfg(target_os = "android")]
            {
                use tauri::plugin::PluginHandle;
                let _handle: PluginHandle<R> =
                    api.register_android_plugin("com.exameow.appupdate", "AppUpdatePlugin")?;
            }
            Ok(())
        })
        .build()
}
