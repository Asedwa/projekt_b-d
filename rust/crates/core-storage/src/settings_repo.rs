use core_domain::{DomainError, DomainResult};
use core_settings::KeyValueStore;
use rusqlite::{params, OptionalExtension};

use crate::Database;

impl KeyValueStore for Database {
    fn get(&self, key: &str) -> DomainResult<Option<String>> {
        let conn = self.lock()?;
        let mut stmt = conn
            .prepare("SELECT value FROM settings WHERE key = ?1;")
            .map_err(|e| DomainError::Storage(format!("prepare error: {e}")))?;

        let val = stmt
            .query_row(params![key], |row| row.get(0))
            .optional()
            .map_err(|e| DomainError::Storage(format!("get settings error: {e}")))?;
        Ok(val)
    }

    fn set(&self, key: &str, value: &str) -> DomainResult<()> {
        let conn = self.lock()?;
        conn.execute(
            "INSERT INTO settings (key, value) VALUES (?1, ?2)
             ON CONFLICT(key) DO UPDATE SET value = excluded.value;",
            params![key, value],
        )
        .map_err(|e| DomainError::Storage(format!("set settings error: {e}")))?;
        Ok(())
    }

    fn remove(&self, key: &str) -> DomainResult<()> {
        let conn = self.lock()?;
        conn.execute("DELETE FROM settings WHERE key = ?1;", params![key])
            .map_err(|e| DomainError::Storage(format!("remove settings error: {e}")))?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn settings_roundtrip() {
        let db = Database::in_memory().unwrap();
        assert_eq!(db.get("k1").unwrap(), None);

        db.set("k1", "v1").unwrap();
        assert_eq!(db.get("k1").unwrap(), Some("v1".into()));

        db.set("k1", "v2").unwrap();
        assert_eq!(db.get("k1").unwrap(), Some("v2".into()));

        db.remove("k1").unwrap();
        assert_eq!(db.get("k1").unwrap(), None);
    }
}
