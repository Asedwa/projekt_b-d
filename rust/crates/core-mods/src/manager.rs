use std::collections::BTreeMap;
use std::sync::RwLock;

use core_domain::{DomainError, DomainResult};
use serde::{Deserialize, Serialize};

use crate::host::{ModExecutionResult, ModRuntime};
use crate::manifest::{ModManifest, ModPermission};
use crate::sample::{sample_mod_manifest, sample_wasm_bytes};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ModState {
    Enabled,
    Disabled,
    Error,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ModInfo {
    pub manifest: ModManifest,
    pub state: ModState,
    pub is_builtin: bool,
    pub last_error: Option<String>,
}

struct InstalledMod {
    manifest: ModManifest,
    wasm_bytes: Vec<u8>,
    state: ModState,
    is_builtin: bool,
    last_error: Option<String>,
}

pub struct ModManager {
    mods: RwLock<BTreeMap<String, InstalledMod>>,
    runtime: ModRuntime,
}

impl Default for ModManager {
    fn default() -> Self {
        Self::new()
    }
}

impl ModManager {
    pub fn new() -> Self {
        let manager = Self {
            mods: RwLock::new(BTreeMap::new()),
            runtime: ModRuntime::new(),
        };
        // Register sample builtin mod
        manager.install_sample_mod();
        manager
    }

    fn install_sample_mod(&self) {
        let manifest = sample_mod_manifest();
        let wasm = sample_wasm_bytes();
        let mut map = self.mods.write().unwrap();
        map.insert(
            manifest.id.clone(),
            InstalledMod {
                manifest,
                wasm_bytes: wasm,
                state: ModState::Enabled,
                is_builtin: true,
                last_error: None,
            },
        );
    }

    pub fn list(&self) -> Vec<ModInfo> {
        let map = self.mods.read().unwrap();
        map.values()
            .map(|m| ModInfo {
                manifest: m.manifest.clone(),
                state: m.state,
                is_builtin: m.is_builtin,
                last_error: m.last_error.clone(),
            })
            .collect()
    }

    pub fn get_info(&self, id: &str) -> Option<ModInfo> {
        let map = self.mods.read().unwrap();
        map.get(id).map(|m| ModInfo {
            manifest: m.manifest.clone(),
            state: m.state,
            is_builtin: m.is_builtin,
            last_error: m.last_error.clone(),
        })
    }

    pub fn install(&self, manifest: ModManifest, wasm_bytes: Vec<u8>) -> DomainResult<ModInfo> {
        manifest.validate()?;
        if wasm_bytes.is_empty() {
            return Err(DomainError::InvalidInput("Wasm payload is empty".into()));
        }

        let id = manifest.id.clone();
        let mut map = self.mods.write().unwrap();
        let installed = InstalledMod {
            manifest: manifest.clone(),
            wasm_bytes,
            state: ModState::Enabled,
            is_builtin: false,
            last_error: None,
        };
        map.insert(id, installed);

        Ok(ModInfo {
            manifest,
            state: ModState::Enabled,
            is_builtin: false,
            last_error: None,
        })
    }

    pub fn set_enabled(&self, id: &str, enabled: bool) -> DomainResult<()> {
        let mut map = self.mods.write().unwrap();
        let m = map
            .get_mut(id)
            .ok_or_else(|| DomainError::NotFound(format!("mod {id}")))?;
        m.state = if enabled {
            ModState::Enabled
        } else {
            ModState::Disabled
        };
        Ok(())
    }

    pub fn uninstall(&self, id: &str) -> DomainResult<()> {
        let mut map = self.mods.write().unwrap();
        if let Some(m) = map.get(id) {
            if m.is_builtin {
                return Err(DomainError::InvalidInput(
                    "cannot uninstall builtin mod".into(),
                ));
            }
        }
        if map.remove(id).is_some() {
            Ok(())
        } else {
            Err(DomainError::NotFound(format!("mod {id}")))
        }
    }

    pub fn execute_action(
        &self,
        id: &str,
        required_perm: ModPermission,
        input_json: &str,
    ) -> DomainResult<ModExecutionResult> {
        let (wasm, entry_point) = {
            let map = self.mods.read().unwrap();
            let m = map
                .get(id)
                .ok_or_else(|| DomainError::NotFound(format!("mod {id}")))?;

            if m.state != ModState::Enabled {
                return Err(DomainError::CapabilityUnavailable(format!(
                    "mod {id} is disabled"
                )));
            }
            if !m.manifest.permissions.contains(&required_perm) {
                return Err(DomainError::CapabilityUnavailable(format!(
                    "mod {id} lacks permission '{}'",
                    required_perm.as_str()
                )));
            }
            (m.wasm_bytes.clone(), m.manifest.entry_point.clone())
        };

        let result = self.runtime.execute(&wasm, &entry_point, input_json)?;

        if !result.success {
            // Update last_error
            let mut map = self.mods.write().unwrap();
            if let Some(m) = map.get_mut(id) {
                m.last_error = result.error.clone();
            }
        }

        Ok(result)
    }

    /// List active mods that provide a custom track-menu action
    pub fn get_track_menu_actions(&self) -> Vec<ModInfo> {
        self.list()
            .into_iter()
            .filter(|m| {
                m.state == ModState::Enabled
                    && m.manifest
                        .permissions
                        .contains(&ModPermission::CustomTrackMenu)
            })
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sample::SAMPLE_MOD_ID;

    #[test]
    fn builtin_sample_mod_present_and_callable() {
        let mgr = ModManager::new();
        let list = mgr.list();
        assert!(!list.is_empty());
        let sample = mgr.get_info(SAMPLE_MOD_ID).expect("sample mod exists");
        assert_eq!(sample.state, ModState::Enabled);
        assert!(sample.is_builtin);

        let actions = mgr.get_track_menu_actions();
        assert_eq!(actions.len(), 1);

        let res = mgr
            .execute_action(
                SAMPLE_MOD_ID,
                ModPermission::CustomTrackMenu,
                r#"{"trackId":"test"}"#,
            )
            .unwrap();
        assert!(res.success);
        assert!(res.output_message.unwrap().contains("Estimated Tempo"));
    }

    #[test]
    fn disabling_mod_blocks_execution() {
        let mgr = ModManager::new();
        mgr.set_enabled(SAMPLE_MOD_ID, false).unwrap();
        let res = mgr.execute_action(SAMPLE_MOD_ID, ModPermission::CustomTrackMenu, "{}");
        assert!(res.is_err());
    }

    #[test]
    fn cannot_uninstall_builtin() {
        let mgr = ModManager::new();
        assert!(mgr.uninstall(SAMPLE_MOD_ID).is_err());
    }
}
