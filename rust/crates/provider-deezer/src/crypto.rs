//! Deezer 2048-byte stripe Blowfish/CBC/NoPadding decryption and MD5 key derivation.

use blowfish::Blowfish;
use cbc::cipher::{BlockDecryptMut, KeyIvInit};
use md5::{Digest, Md5};

pub const STRIPE_CHUNK_SIZE: usize = 2048;
const STRIPE_IV: [u8; 8] = [0, 1, 2, 3, 4, 5, 6, 7];
const STRIPE_SALT: &[u8; 16] = b"g4el58wc0zvf9na1";

/// Derives the 16-byte Blowfish key for a Deezer track ID.
pub fn derive_track_key(track_id: &str) -> [u8; 16] {
    let digest = Md5::digest(track_id.as_bytes());
    let hex_str = hex::encode(digest);
    let hex_bytes = hex_str.as_bytes();
    let mut key = [0u8; 16];
    for i in 0..16 {
        key[i] = hex_bytes[i] ^ hex_bytes[i + 16] ^ STRIPE_SALT[i];
    }
    key
}

/// Decrypts a single 2048-byte Deezer stripe in-place if `chunk_index % 3 == 0`.
pub fn decrypt_stripe_in_place(key: &[u8; 16], chunk_index: u64, data: &mut [u8]) {
    if data.len() != STRIPE_CHUNK_SIZE || chunk_index % 3 != 0 {
        return;
    }
    if let Ok(mut decryptor) = cbc::Decryptor::<Blowfish>::new_from_slices(key, &STRIPE_IV) {
        for block in data.chunks_exact_mut(8) {
            decryptor.decrypt_block_mut(block.into());
        }
    }
}

/// Convenience wrapper returning a decrypted buffer for FFI callers.
pub fn decrypt_stripe_bytes(key: &[u8], chunk_index: u64, mut data: Vec<u8>) -> Vec<u8> {
    if key.len() == 16 {
        let mut arr = [0u8; 16];
        arr.copy_from_slice(key);
        decrypt_stripe_in_place(&arr, chunk_index, &mut data);
    }
    data
}

/// Decrypts a contiguous buffer of 2048-byte-aligned stripes starting at `start_chunk_index`.
pub fn decrypt_aligned_chunks(key: &[u8], start_chunk_index: u64, mut data: Vec<u8>) -> Vec<u8> {
    if key.len() != 16 {
        return data;
    }
    let mut arr = [0u8; 16];
    arr.copy_from_slice(key);
    for (idx, chunk) in data.chunks_mut(STRIPE_CHUNK_SIZE).enumerate() {
        decrypt_stripe_in_place(&arr, start_chunk_index + idx as u64, chunk);
    }
    data
}

#[cfg(test)]
mod tests {
    use super::*;
    use cbc::cipher::BlockEncryptMut;

    #[test]
    fn derives_deterministic_16_byte_key() {
        let k1 = derive_track_key("3135556");
        let k2 = derive_track_key("3135556");
        assert_eq!(k1, k2);
        assert_ne!(k1, derive_track_key("3135557"));
    }

    #[test]
    fn decrypts_every_third_2048_stripe_only() {
        let key = derive_track_key("3135556");
        let plaintext = vec![0x42u8; STRIPE_CHUNK_SIZE];

        // Encrypt with Blowfish/CBC using the same key & IV
        let mut encrypted = plaintext.clone();
        let mut encryptor = cbc::Encryptor::<Blowfish>::new_from_slices(&key, &STRIPE_IV).unwrap();
        for block in encrypted.chunks_exact_mut(8) {
            encryptor.encrypt_block_mut(block.into());
        }
        assert_ne!(encrypted, plaintext);

        // Chunk 0 (0 % 3 == 0): should decrypt back to plaintext
        let out0 = decrypt_stripe_bytes(&key, 0, encrypted.clone());
        assert_eq!(out0, plaintext);

        // Chunk 1 (1 % 3 != 0): should remain untouched
        let out1 = decrypt_stripe_bytes(&key, 1, encrypted.clone());
        assert_eq!(out1, encrypted);

        // Chunk 3 (3 % 3 == 0): should decrypt back to plaintext
        let out3 = decrypt_stripe_bytes(&key, 3, encrypted.clone());
        assert_eq!(out3, plaintext);
    }
}
