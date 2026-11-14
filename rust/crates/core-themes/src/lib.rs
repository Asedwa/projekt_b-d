//! JSON theme models, validation, and registry for PlasmaWave.
//!
//! Themes define design tokens (colors, shapes, typography, spacing, motion)
//! and never execute arbitrary code. Themes are validated against a schema
//! version and fallback cleanly to Breeze Dark if invalid.

mod manifest;
mod registry;

pub use manifest::{
    ColorTokens, CustomThemeSpec, ElevationTokens, MotionTokens, ShapeTokens, SpacingTokens,
    ThemeManifest, TypographyTokens, CURRENT_SCHEMA_VERSION,
};
pub use registry::{ThemeRegistry, DEFAULT_THEME_ID};
