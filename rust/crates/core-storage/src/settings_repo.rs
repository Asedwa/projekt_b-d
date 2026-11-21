use std::collections::BTreeMap;

use core_domain::{DomainError, DomainResult};
use core_settings::KeyValueStore;
use rusqlite::{params, OptionalExtension};

use crate::Database;

impl KeyValueStore for Database {
    fn get(&self, key: &str) -> DomainResult<Option<String>> {
        let conn = self.lock()?;
        let mut stmt = conn
            .prepare_cached("SELECT value FROM settings WHERE key = ?1;")
            .map_err(|e| DomainError::Storage(format!("prepare error: {e}")))?;

        let val = stmt
            .query_row(params![key], |row| row.get(0))
            .optional()
            .map_err(|e| DomainError::Storage(format!("get settings error: {e}")))?;
        Ok(val)
    }

    fn set(&self, key: &str, value: &str) -> DomainResult<()> {
        let conn = self.lock()?;
        let mut stmt = conn
            .prepare_cached(
                "INSERT INTO settings (key, value) VALUES (?1, ?2)
                 ON CONFLICT(key) DO UPDATE SET value = excluded.value;",
            )
            .map_err(|e| DomainError::Storage(format!("prepare set settings error: {e}")))?;
        stmt.execute(params![key, value])
            .map_err(|e| DomainError::Storage(format!("set settings error: {e}")))?;
        Ok(())
    }

    fn remove(&self, key: &str) -> DomainResult<()> {
        let conn = self.lock()?;
        let mut stmt = conn
            .prepare_cached("DELETE FROM settings WHERE key = ?1;")
            .map_err(|e| DomainError::Storage(format!("prepare remove settings error: {e}")))?;
        stmt.execute(params![key])
            .map_err(|e| DomainError::Storage(format!("remove settings error: {e}")))?;
        Ok(())
    }

    fn get_all(&self) -> DomainResult<BTreeMap<String, String>> {
        let conn = self.lock()?;
        let mut stmt = conn
            .prepare_cached("SELECT key, value FROM settings;")
            .map_err(|e| DomainError::Storage(format!("prepare get_all settings error: {e}")))?;
        let rows = stmt
            .query_map([], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
            })
            .map_err(|e| DomainError::Storage(format!("query get_all settings error: {e}")))?;

        let mut map = BTreeMap::new();
        for r in rows {
            let (k, v) =
                r.map_err(|e| DomainError::Storage(format!("row get_all settings error: {e}")))?;
            map.insert(k, v);
        }
        Ok(map)
    }

    fn set_batch(&self, entries: &[(&str, Option<&str>)]) -> DomainResult<()> {
        if entries.is_empty() {
            return Ok(());
        }
        let mut conn = self.lock()?;
        let tx = conn
            .transaction()
            .map_err(|e| DomainError::Storage(format!("settings batch tx error: {e}")))?;
        {
            let mut upsert = tx
                .prepare_cached(
                    "INSERT INTO settings (key, value) VALUES (?1, ?2)
                     ON CONFLICT(key) DO UPDATE SET value = excluded.value;",
                )
                .map_err(|e| DomainError::Storage(format!("prepare batch upsert error: {e}")))?;
            let mut delete = tx
                .prepare_cached("DELETE FROM settings WHERE key = ?1;")
                .map_err(|e| DomainError::Storage(format!("prepare batch delete error: {e}")))?;

            for &(key, value) in entries {
                match value {
                    Some(v) => {
                        upsert.execute(params![key, v]).map_err(|e| {
                            DomainError::Storage(format!("exec batch upsert error: {e}"))
                        })?;
                    }
                    None => {
                        delete.execute(params![key]).map_err(|e| {
                            DomainError::Storage(format!("exec batch delete error: {e}"))
                        })?;
                    }
                }
            }
        }
        tx.commit()
            .map_err(|e| DomainError::Storage(format!("commit settings batch error: {e}")))?;
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
