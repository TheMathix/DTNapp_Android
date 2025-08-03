use toml;
use dtn7::DtnConfig;
use dtn7::dtnd::daemon::start_dtnd;
use std::{fs, path::PathBuf};
use jni::JNIEnv;
use jni::objects::JClass;
use log::{debug, error};
use std::env;

#[no_mangle]
pub extern "system" fn Java_com_example_mydtnapp_MainActivity_startdaemon(
    _env: JNIEnv,
    _class: JClass,
) {
    std::thread::spawn(|| {
        if let Err(e) = run_dtnd() {
            error!("❌ Failed to start DTND: {:?}", e);
        }
    });
}


fn run_dtnd() -> Result<(), Box<dyn std::error::Error>> {
    env::set_var("RUST_LOG", "debug");
    android_logger::init_once(
        android_logger::Config::default()
            .with_tag("DTN-RUST")
            .with_max_level(log::LevelFilter::Debug)
    );


    debug!("🚀 Starting DTND daemon...");

    // Locate config file path - place in app's internal storage
    let config_path = "/data/data/com.example.mydtnapp/files/dtnd.toml";

    // If config doesn't exist, copy from assets (you can expose this via JNI if needed)
    if !PathBuf::from(config_path).exists() {
        error!("❌ Config file not found at {}", config_path);
        return Err("Missing dtnd.toml config".into());
    }


    let config = DtnConfig::from(std::path::PathBuf::from(config_path));
    debug!("✅ Parsed config: {:?}", config);
    let config_text = std::fs::read_to_string(config_path)?;
    debug!("📄 TOML FILE:\n{}", config_text);

    debug!("🆔 Loaded node ID: {}", config.nodeid);

    debug!("✅ Loaded config from {}", config_path);
    let rt = tokio::runtime::Runtime::new()?;
    Ok(rt.block_on(start_dtnd(config))?)

}
