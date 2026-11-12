use crate::manifest::{ModManifest, ModPermission};
use std::collections::BTreeSet;

pub const SAMPLE_MOD_ID: &str = "org.plasmawave.sample-tempo";

pub const SAMPLE_WAT: &str = r#"
(module
  (import "host" "log" (func $log (param i32 i32)))
  (import "host" "set_output" (func $set_output (param i32 i32)))
  (memory (export "memory") 1)
  (data (i32.const 0) "Analyzing BPM...")
  (data (i32.const 32) "PlasmaWave Mod: Estimated Tempo is 124 BPM (Key: F Minor)")

  (func (export "on_track_action") (result i32)
    ;; Log message
    (call $log (i32.const 0) (i32.const 16))
    ;; Set user-facing output message
    (call $set_output (i32.const 32) (i32.const 57))
    ;; Return 0 (success)
    (i32.const 0)
  )
)
"#;

pub fn sample_mod_manifest() -> ModManifest {
    let mut permissions = BTreeSet::new();
    permissions.insert(ModPermission::CustomTrackMenu);
    permissions.insert(ModPermission::ReadMetadata);
    ModManifest {
        id: SAMPLE_MOD_ID.into(),
        name: "BPM & Tempo Estimator".into(),
        version: "1.0.0".into(),
        author: "PlasmaWave Community".into(),
        description: "Adds a custom track action to estimate tempo and musical key.".into(),
        api_version: 1,
        entry_point: "on_track_action".into(),
        permissions,
    }
}

pub fn sample_wasm_bytes() -> Vec<u8> {
    wat::parse_str(SAMPLE_WAT).expect("sample WAT must be valid")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::host::ModRuntime;

    #[test]
    fn sample_mod_executes_successfully() {
        let wasm = sample_wasm_bytes();
        let runtime = ModRuntime::new();
        let result = runtime.execute(&wasm, "on_track_action", "{}").unwrap();
        assert!(result.success);
        assert_eq!(
            result.output_message.as_deref(),
            Some("PlasmaWave Mod: Estimated Tempo is 124 BPM (Key: F Minor)")
        );
        assert_eq!(result.logs, vec!["Analyzing BPM..."]);
    }
}
