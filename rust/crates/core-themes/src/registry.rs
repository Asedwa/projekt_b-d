use std::collections::BTreeMap;
use std::sync::RwLock;

use core_domain::{DomainError, DomainResult};

use crate::manifest::ThemeManifest;

pub const DEFAULT_THEME_ID: &str = "org.kde.breeze-dark";

const BREEZE_DARK_JSON: &str = include_str!("../../../../themes/breeze-dark/theme.json");
const BREEZE_LIGHT_JSON: &str = include_str!("../../../../themes/breeze-light/theme.json");
const OXYGEN_NIGHT_JSON: &str = include_str!("../../../../themes/oxygen-night/theme.json");
const NOTHING_JSON: &str = include_str!("../../../../themes/nothing/theme.json");
const TWILIGHT_SPARKLE_JSON: &str = include_str!("../../../../themes/twilight-sparkle/theme.json");
const PINKIE_PIE_JSON: &str = include_str!("../../../../themes/pinkie-pie/theme.json");

#[derive(Debug)]
pub struct ThemeRegistry {
    themes: RwLock<BTreeMap<String, ThemeManifest>>,
}

impl Default for ThemeRegistry {
    fn default() -> Self {
        Self::new()
    }
}

impl ThemeRegistry {
    pub fn new() -> Self {
        let registry = Self {
            themes: RwLock::new(BTreeMap::new()),
        };
        registry.register_builtins();
        registry
    }

    fn register_builtins(&self) {
        let mut map = self.themes.write().unwrap();
        for json in [
            BREEZE_DARK_JSON,
            BREEZE_LIGHT_JSON,
            OXYGEN_NIGHT_JSON,
            NOTHING_JSON,
            TWILIGHT_SPARKLE_JSON,
            PINKIE_PIE_JSON,
        ] {
            if let Ok(manifest) = ThemeManifest::parse_and_validate(json) {
                map.insert(manifest.id.clone(), manifest);
            }
        }
    }

    pub fn list(&self) -> Vec<ThemeManifest> {
        let map = self.themes.read().unwrap();
        map.values().cloned().collect()
    }

    pub fn get(&self, id: &str) -> Option<ThemeManifest> {
        let map = self.themes.read().unwrap();
        map.get(id).cloned()
    }

    pub fn resolve_or_default(&self, id: &str) -> ThemeManifest {
        self.get(id)
            .or_else(|| self.get(DEFAULT_THEME_ID))
            .unwrap_or_else(|| {
                ThemeManifest::parse_and_validate(BREEZE_DARK_JSON)
                    .expect("embedded breeze dark must be valid")
            })
    }

    pub fn register(&self, manifest: ThemeManifest) -> DomainResult<()> {
        manifest.validate()?;
        let mut map = self.themes.write().unwrap();
        map.insert(manifest.id.clone(), manifest);
        Ok(())
    }

    pub fn import_json(&self, json_str: &str) -> DomainResult<ThemeManifest> {
        let manifest = ThemeManifest::parse_and_validate(json_str)?;
        self.register(manifest.clone())?;
        Ok(manifest)
    }

    pub fn remove(&self, id: &str) -> DomainResult<()> {
        if id == DEFAULT_THEME_ID
            || id == "org.kde.breeze-light"
            || id == "org.plasmawave.oxygen-night"
            || id == "org.plasmawave.nothing"
            || id == "org.plasmawave.twilight-sparkle"
            || id == "org.plasmawave.pinkie-pie"
        {
            return Err(DomainError::InvalidInput(
                "cannot remove built-in themes".into(),
            ));
        }
        let mut map = self.themes.write().unwrap();
        if map.remove(id).is_some() {
            Ok(())
        } else {
            Err(DomainError::NotFound(format!("theme {id}")))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builtins_are_registered() {
        let reg = ThemeRegistry::new();
        let list = reg.list();
        assert!(list.len() >= 4);
        let dark = reg.get(DEFAULT_THEME_ID).expect("breeze dark present");
        assert_eq!(dark.name, "Breeze Dark");
        assert!(dark.dark);
        let nothing = reg.get("org.plasmawave.nothing").expect("nothing present");
        assert_eq!(nothing.name, "Nothing OS");
        assert!(nothing.dark);
    }

    #[test]
    fn resolve_fallback() {
        let reg = ThemeRegistry::new();
        let fallback = reg.resolve_or_default("non.existent.theme");
        assert_eq!(fallback.id, DEFAULT_THEME_ID);
    }

    #[test]
    fn import_and_remove() {
        let reg = ThemeRegistry::new();
        let custom_json = r##"{
            "schemaVersion": 1,
            "id": "org.custom.neon",
            "name": "Neon",
            "author": "Me",
            "colors": {
                "background": "#000000",
                "surface": "#111111",
                "card": "#222222",
                "accent": "#00FFCC",
                "text": "#FFFFFF",
                "border": "#333333"
            }
        }"##;
        let imported = reg.import_json(custom_json).unwrap();
        assert_eq!(imported.id, "org.custom.neon");
        assert!(reg.get("org.custom.neon").is_some());
        assert!(reg.remove("org.custom.neon").is_ok());
        assert!(reg.get("org.custom.neon").is_none());
        assert!(reg.remove(DEFAULT_THEME_ID).is_err());
    }
}
