use core_domain::{DomainError, DomainResult};
use serde::{Deserialize, Serialize};

pub const CURRENT_SCHEMA_VERSION: u32 = 1;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CustomThemeSpec {
    pub id: String,
    pub name: String,
    pub background_hex: String,
    pub card_hex: String,
    pub accent_hex: String,
    pub text_hex: String,
}

fn normalize_hex6(input: &str, fallback: &str) -> String {
    let cleaned = input.trim().trim_start_matches('#');
    if cleaned.len() == 6 && cleaned.chars().all(|c| c.is_ascii_hexdigit()) {
        format!("#{}", cleaned.to_uppercase())
    } else {
        fallback.to_string()
    }
}

fn parse_rgb(hex: &str) -> (u8, u8, u8) {
    let c = hex.trim().trim_start_matches('#');
    if c.len() == 6 {
        let r = u8::from_str_radix(&c[0..2], 16).unwrap_or(20);
        let g = u8::from_str_radix(&c[2..4], 16).unwrap_or(18);
        let b = u8::from_str_radix(&c[4..6], 16).unwrap_or(36);
        (r, g, b)
    } else {
        (20, 18, 36)
    }
}

fn blend_hex(a: &str, b: &str, ratio: f32) -> String {
    let (ar, ag, ab) = parse_rgb(a);
    let (br, bg, bb) = parse_rgb(b);
    let t = ratio.clamp(0.0, 1.0);
    let r = ((ar as f32) * (1.0 - t) + (br as f32) * t).round().clamp(0.0, 255.0) as u8;
    let g = ((ag as f32) * (1.0 - t) + (bg as f32) * t).round().clamp(0.0, 255.0) as u8;
    let bl = ((ab as f32) * (1.0 - t) + (bb as f32) * t).round().clamp(0.0, 255.0) as u8;
    format!("#{r:02X}{g:02X}{bl:02X}")
}

impl CustomThemeSpec {
    pub fn normalized(&self) -> Self {
        let id = if self.id.trim().is_empty() || !self.id.contains('.') {
            format!("custom.theme.{}", self.id.trim())
        } else {
            self.id.trim().to_string()
        };
        let name = if self.name.trim().is_empty() {
            "Özel Tema".to_string()
        } else {
            self.name.trim().to_string()
        };
        Self {
            id,
            name,
            background_hex: normalize_hex6(&self.background_hex, "#141224"),
            card_hex: normalize_hex6(&self.card_hex, "#282348"),
            accent_hex: normalize_hex6(&self.accent_hex, "#ED438D"),
            text_hex: normalize_hex6(&self.text_hex, "#F5EEF8"),
        }
    }

    pub fn to_manifest(&self) -> DomainResult<ThemeManifest> {
        let norm = self.normalized();
        let (br, bg, bb) = parse_rgb(&norm.background_hex);
        let luminance = (0.299 * (br as f32) + 0.587 * (bg as f32) + 0.114 * (bb as f32)) / 255.0;
        let is_dark = luminance < 0.55;
        let surface_hex = blend_hex(&norm.background_hex, &norm.card_hex, 0.5);
        let border_hex = blend_hex(&norm.card_hex, &norm.accent_hex, 0.35);
        let text_secondary_hex = blend_hex(&norm.text_hex, &norm.background_hex, 0.32);

        let manifest = ThemeManifest {
            schema_version: CURRENT_SCHEMA_VERSION,
            id: norm.id,
            name: norm.name,
            author: "Kullanıcı".into(),
            description: Some("Kullanıcı tarafından oluşturulan özel tema".into()),
            dark: is_dark,
            colors: ColorTokens {
                background: norm.background_hex,
                surface: surface_hex.clone(),
                surface_variant: surface_hex,
                card: norm.card_hex.clone(),
                card_pressed: blend_hex(&norm.card_hex, &norm.accent_hex, 0.2),
                accent: norm.accent_hex.clone(),
                accent_muted: blend_hex(&norm.accent_hex, &norm.card_hex, 0.45),
                on_accent: "#FFFFFF".into(),
                text: norm.text_hex,
                text_secondary: text_secondary_hex.clone(),
                text_disabled: text_secondary_hex,
                border: border_hex.clone(),
                divider: border_hex,
                positive: default_positive(),
                warning: default_warning(),
                negative: default_negative(),
                scrim: default_scrim(),
            },
            shapes: ShapeTokens {
                small_radius: 8,
                medium_radius: 14,
                large_radius: 22,
            },
            spacing: SpacingTokens::default(),
            typography: TypographyTokens::default(),
            elevation: ElevationTokens::default(),
            motion: MotionTokens::default(),
        };
        manifest.validate()?;
        Ok(manifest)
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ThemeManifest {
    pub schema_version: u32,
    pub id: String,
    pub name: String,
    pub author: String,
    #[serde(default)]
    pub description: Option<String>,
    #[serde(default = "default_dark")]
    pub dark: bool,
    pub colors: ColorTokens,
    #[serde(default)]
    pub shapes: ShapeTokens,
    #[serde(default)]
    pub spacing: SpacingTokens,
    #[serde(default)]
    pub typography: TypographyTokens,
    #[serde(default)]
    pub elevation: ElevationTokens,
    #[serde(default)]
    pub motion: MotionTokens,
}

fn default_dark() -> bool {
    true
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ColorTokens {
    pub background: String,
    pub surface: String,
    #[serde(default = "default_surface_variant")]
    pub surface_variant: String,
    pub card: String,
    #[serde(default = "default_card_pressed")]
    pub card_pressed: String,
    pub accent: String,
    #[serde(default = "default_accent_muted")]
    pub accent_muted: String,
    #[serde(default = "default_on_accent")]
    pub on_accent: String,
    pub text: String,
    #[serde(default = "default_text_secondary")]
    pub text_secondary: String,
    #[serde(default = "default_text_disabled")]
    pub text_disabled: String,
    pub border: String,
    #[serde(default = "default_divider")]
    pub divider: String,
    #[serde(default = "default_positive")]
    pub positive: String,
    #[serde(default = "default_warning")]
    pub warning: String,
    #[serde(default = "default_negative")]
    pub negative: String,
    #[serde(default = "default_scrim")]
    pub scrim: String,
}

fn default_surface_variant() -> String {
    "#2A2E32".into()
}
fn default_card_pressed() -> String {
    "#31363B".into()
}
fn default_accent_muted() -> String {
    "#1D5A7A".into()
}
fn default_on_accent() -> String {
    "#FCFCFC".into()
}
fn default_text_secondary() -> String {
    "#A1A9B1".into()
}
fn default_text_disabled() -> String {
    "#6E757C".into()
}
fn default_divider() -> String {
    "#2F3337".into()
}
fn default_positive() -> String {
    "#27AE60".into()
}
fn default_warning() -> String {
    "#F67400".into()
}
fn default_negative() -> String {
    "#DA4453".into()
}
fn default_scrim() -> String {
    "#B3000000".into()
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ShapeTokens {
    #[serde(default = "default_small_radius")]
    pub small_radius: u32,
    #[serde(default = "default_medium_radius")]
    pub medium_radius: u32,
    #[serde(default = "default_large_radius")]
    pub large_radius: u32,
}

fn default_small_radius() -> u32 {
    6
}
fn default_medium_radius() -> u32 {
    10
}
fn default_large_radius() -> u32 {
    18
}

impl Default for ShapeTokens {
    fn default() -> Self {
        Self {
            small_radius: default_small_radius(),
            medium_radius: default_medium_radius(),
            large_radius: default_large_radius(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SpacingTokens {
    #[serde(default = "default_spacing_xs")]
    pub xs: u32,
    #[serde(default = "default_spacing_sm")]
    pub sm: u32,
    #[serde(default = "default_spacing_md")]
    pub md: u32,
    #[serde(default = "default_spacing_lg")]
    pub lg: u32,
    #[serde(default = "default_spacing_xl")]
    pub xl: u32,
}

fn default_spacing_xs() -> u32 {
    4
}
fn default_spacing_sm() -> u32 {
    8
}
fn default_spacing_md() -> u32 {
    12
}
fn default_spacing_lg() -> u32 {
    16
}
fn default_spacing_xl() -> u32 {
    24
}

impl Default for SpacingTokens {
    fn default() -> Self {
        Self {
            xs: default_spacing_xs(),
            sm: default_spacing_sm(),
            md: default_spacing_md(),
            lg: default_spacing_lg(),
            xl: default_spacing_xl(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TypographyTokens {
    #[serde(default = "default_font_family")]
    pub font_family: String,
    #[serde(default = "default_scale")]
    pub scale: f32,
}

fn default_font_family() -> String {
    "system".into()
}
fn default_scale() -> f32 {
    1.0
}

impl Default for TypographyTokens {
    fn default() -> Self {
        Self {
            font_family: default_font_family(),
            scale: default_scale(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ElevationTokens {
    #[serde(default = "default_elev_low")]
    pub low: u32,
    #[serde(default = "default_elev_medium")]
    pub medium: u32,
    #[serde(default = "default_elev_high")]
    pub high: u32,
}

fn default_elev_low() -> u32 {
    1
}
fn default_elev_medium() -> u32 {
    4
}
fn default_elev_high() -> u32 {
    12
}

impl Default for ElevationTokens {
    fn default() -> Self {
        Self {
            low: default_elev_low(),
            medium: default_elev_medium(),
            high: default_elev_high(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MotionTokens {
    #[serde(default = "default_motion_short")]
    pub short_ms: u32,
    #[serde(default = "default_motion_medium")]
    pub medium_ms: u32,
    #[serde(default = "default_motion_long")]
    pub long_ms: u32,
}

fn default_motion_short() -> u32 {
    120
}
fn default_motion_medium() -> u32 {
    220
}
fn default_motion_long() -> u32 {
    360
}

impl Default for MotionTokens {
    fn default() -> Self {
        Self {
            short_ms: default_motion_short(),
            medium_ms: default_motion_medium(),
            long_ms: default_motion_long(),
        }
    }
}

pub fn validate_hex_color(name: &str, val: &str) -> DomainResult<()> {
    let s = val.trim();
    if !s.starts_with('#') {
        return Err(DomainError::InvalidInput(format!(
            "color '{name}' must start with '#' (got '{val}')"
        )));
    }
    let hex = &s[1..];
    if !(hex.len() == 6 || hex.len() == 8 || hex.len() == 3 || hex.len() == 4) {
        return Err(DomainError::InvalidInput(format!(
            "color '{name}' has invalid hex length ({})",
            hex.len()
        )));
    }
    if !hex.chars().all(|c| c.is_ascii_hexdigit()) {
        return Err(DomainError::InvalidInput(format!(
            "color '{name}' contains non-hex characters"
        )));
    }
    Ok(())
}

impl ThemeManifest {
    pub fn parse_and_validate(json_str: &str) -> DomainResult<Self> {
        let manifest: Self = serde_json::from_str(json_str)
            .map_err(|e| DomainError::InvalidInput(format!("theme JSON parse error: {e}")))?;
        manifest.validate()?;
        Ok(manifest)
    }

    pub fn validate(&self) -> DomainResult<()> {
        if self.schema_version != CURRENT_SCHEMA_VERSION {
            return Err(DomainError::InvalidInput(format!(
                "unsupported theme schema version {}; expected {}",
                self.schema_version, CURRENT_SCHEMA_VERSION
            )));
        }
        if self.id.trim().is_empty() || self.id.len() > 128 || !self.id.contains('.') {
            return Err(DomainError::InvalidInput(format!(
                "invalid theme id '{}'; must be reverse-domain format (e.g. org.kde.breeze-dark)",
                self.id
            )));
        }
        if self.name.trim().is_empty() || self.name.len() > 64 {
            return Err(DomainError::InvalidInput("invalid theme name".into()));
        }

        // Validate colors
        validate_hex_color("background", &self.colors.background)?;
        validate_hex_color("surface", &self.colors.surface)?;
        validate_hex_color("surfaceVariant", &self.colors.surface_variant)?;
        validate_hex_color("card", &self.colors.card)?;
        validate_hex_color("cardPressed", &self.colors.card_pressed)?;
        validate_hex_color("accent", &self.colors.accent)?;
        validate_hex_color("accentMuted", &self.colors.accent_muted)?;
        validate_hex_color("onAccent", &self.colors.on_accent)?;
        validate_hex_color("text", &self.colors.text)?;
        validate_hex_color("textSecondary", &self.colors.text_secondary)?;
        validate_hex_color("textDisabled", &self.colors.text_disabled)?;
        validate_hex_color("border", &self.colors.border)?;
        validate_hex_color("divider", &self.colors.divider)?;
        validate_hex_color("positive", &self.colors.positive)?;
        validate_hex_color("warning", &self.colors.warning)?;
        validate_hex_color("negative", &self.colors.negative)?;
        validate_hex_color("scrim", &self.colors.scrim)?;

        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn valid_manifest_parses() {
        let json = r##"{
            "schemaVersion": 1,
            "id": "org.kde.breeze-test",
            "name": "Breeze Test",
            "author": "Tester",
            "colors": {
                "background": "#1B1E20",
                "surface": "#202326",
                "card": "#2A2E32",
                "accent": "#3DAEE9",
                "text": "#FCFCFC",
                "border": "#3B4045"
            }
        }"##;
        let manifest = ThemeManifest::parse_and_validate(json).unwrap();
        assert_eq!(manifest.id, "org.kde.breeze-test");
        assert_eq!(manifest.colors.background, "#1B1E20");
        assert_eq!(manifest.shapes.medium_radius, 10);
    }

    #[test]
    fn bad_color_fails_validation() {
        let json = r##"{
            "schemaVersion": 1,
            "id": "org.kde.breeze-test",
            "name": "Breeze Test",
            "author": "Tester",
            "colors": {
                "background": "red",
                "surface": "#202326",
                "card": "#2A2E32",
                "accent": "#3DAEE9",
                "text": "#FCFCFC",
                "border": "#3B4045"
            }
        }"##;
        assert!(ThemeManifest::parse_and_validate(json).is_err());
    }
}
