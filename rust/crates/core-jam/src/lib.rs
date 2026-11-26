//! MQTT 3.1.1 binary packet codec and Jam protocol helpers.

use thiserror::Error;

pub const MAX_MQTT_REMAINING_LENGTH: usize = 2 * 1024 * 1024;

#[derive(Debug, Error)]
pub enum JamError {
    #[error("invalid MQTT packet: {0}")]
    InvalidPacket(String),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DecodedMqttPublish {
    pub topic: String,
    pub payload: String,
}

/// Constant-time comparison for Jam room codes / tokens.
pub fn constant_time_eq(expected: &str, provided: &str) -> bool {
    let a = expected.as_bytes();
    let b = provided.as_bytes();
    let max_len = a.len().max(b.len());
    let mut diff: u8 = if a.len() == b.len() { 0 } else { 1 };
    for i in 0..max_len {
        let ba = *a.get(i).unwrap_or(&0);
        let bb = *b.get(i).unwrap_or(&0);
        diff |= ba ^ bb;
    }
    diff == 0
}

/// Computes seek target (ms) if remote vs local position drift exceeds `threshold_ms`.
pub fn compute_drift_target_ms(
    remote_pos_ms: i64,
    local_pos_ms: i64,
    threshold_ms: i64,
) -> Option<i64> {
    let diff = (remote_pos_ms - local_pos_ms).abs();
    if diff > threshold_ms {
        Some(remote_pos_ms.max(0))
    } else {
        None
    }
}

pub fn encode_mqtt_remaining_length(mut length: usize, out: &mut Vec<u8>) {
    loop {
        let mut digit = (length % 128) as u8;
        length /= 128;
        if length > 0 {
            digit |= 0x80;
        }
        out.push(digit);
        if length == 0 {
            break;
        }
    }
}

pub fn decode_mqtt_remaining_length(bytes: &[u8]) -> Result<(usize, usize), JamError> {
    let mut multiplier: usize = 1;
    let mut value: usize = 0;
    for (idx, &encoded) in bytes.iter().take(4).enumerate() {
        value += ((encoded & 0x7F) as usize) * multiplier;
        if value > MAX_MQTT_REMAINING_LENGTH {
            return Err(JamError::InvalidPacket(
                "MQTT remaining length exceeds 2 MiB".into(),
            ));
        }
        if (encoded & 0x80) == 0 {
            return Ok((value, idx + 1));
        }
        multiplier *= 128;
    }
    Err(JamError::InvalidPacket(
        "malformed MQTT remaining length".into(),
    ))
}

fn push_utf8(out: &mut Vec<u8>, s: &str) {
    let b = s.as_bytes();
    let len = b.len().min(u16::MAX as usize);
    out.push(((len >> 8) & 0xFF) as u8);
    out.push((len & 0xFF) as u8);
    out.extend_from_slice(&b[..len]);
}

pub fn encode_mqtt_connect(client_id: &str, keep_alive_secs: u16) -> Vec<u8> {
    let mut payload = Vec::with_capacity(32 + client_id.len());
    push_utf8(&mut payload, "MQTT");
    payload.push(4); // Protocol level 4 (MQTT 3.1.1)
    payload.push(0x02); // Clean session
    payload.push(((keep_alive_secs >> 8) & 0xFF) as u8);
    payload.push((keep_alive_secs & 0xFF) as u8);
    push_utf8(&mut payload, client_id);

    let mut packet = Vec::with_capacity(5 + payload.len());
    packet.push(0x10);
    encode_mqtt_remaining_length(payload.len(), &mut packet);
    packet.extend_from_slice(&payload);
    packet
}

pub fn encode_mqtt_subscribe(packet_id: u16, topic_filter: &str) -> Vec<u8> {
    let mut payload = Vec::with_capacity(8 + topic_filter.len());
    payload.push(((packet_id >> 8) & 0xFF) as u8);
    payload.push((packet_id & 0xFF) as u8);
    push_utf8(&mut payload, topic_filter);
    payload.push(0x00); // QoS 0

    let mut packet = Vec::with_capacity(5 + payload.len());
    packet.push(0x82);
    encode_mqtt_remaining_length(payload.len(), &mut packet);
    packet.extend_from_slice(&payload);
    packet
}

pub fn encode_mqtt_publish(topic: &str, payload_str: &str, retain: bool) -> Vec<u8> {
    let payload_bytes = payload_str.as_bytes();
    let mut body = Vec::with_capacity(2 + topic.len() + payload_bytes.len());
    push_utf8(&mut body, topic);
    body.extend_from_slice(payload_bytes);

    let mut packet = Vec::with_capacity(5 + body.len());
    packet.push(if retain { 0x31 } else { 0x30 });
    encode_mqtt_remaining_length(body.len(), &mut packet);
    packet.extend_from_slice(&body);
    packet
}

pub fn encode_mqtt_pingreq() -> Vec<u8> {
    vec![0xC0, 0x00]
}

pub fn decode_mqtt_publish_body(header: u8, body: &[u8]) -> Result<DecodedMqttPublish, JamError> {
    if body.len() < 2 {
        return Err(JamError::InvalidPacket("publish body too short".into()));
    }
    let topic_len = ((body[0] as usize) << 8) | (body[1] as usize);
    let mut pos = 2 + topic_len;
    if pos > body.len() {
        return Err(JamError::InvalidPacket("publish topic out of bounds".into()));
    }
    let topic = String::from_utf8_lossy(&body[2..pos]).into_owned();
    let qos = (header >> 1) & 0x03;
    if qos > 0 {
        pos += 2; // Skip 2-byte packet identifier for QoS 1/2
    }
    if pos > body.len() {
        return Err(JamError::InvalidPacket("publish payload out of bounds".into()));
    }
    let payload = String::from_utf8_lossy(&body[pos..]).into_owned();
    Ok(DecodedMqttPublish { topic, payload })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mqtt_packet_roundtrip_and_limits() {
        let pub_pkt = encode_mqtt_publish("jam/room1/state", r#"{"playing":true}"#, true);
        let header = pub_pkt[0];
        assert_eq!(header & 0xF0, 0x30);
        let (rem_len, len_bytes) = decode_mqtt_remaining_length(&pub_pkt[1..]).unwrap();
        let body = &pub_pkt[1 + len_bytes..1 + len_bytes + rem_len];
        let decoded = decode_mqtt_publish_body(header, body).unwrap();
        assert_eq!(decoded.topic, "jam/room1/state");
        assert_eq!(decoded.payload, r#"{"playing":true}"#);

        // Reject > 2 MiB remaining length
        assert!(decode_mqtt_remaining_length(&[0xFF, 0xFF, 0xFF, 0x01]).is_err());
    }

    #[test]
    fn constant_time_and_drift_helpers() {
        assert!(constant_time_eq("ABCD12", "ABCD12"));
        assert!(!constant_time_eq("ABCD12", "ABCD13"));
        assert!(!constant_time_eq("ABCD12", "ABCD1"));

        assert_eq!(compute_drift_target_ms(15_000, 14_200, 1_500), None);
        assert_eq!(compute_drift_target_ms(15_000, 12_000, 1_500), Some(15_000));
    }
}
