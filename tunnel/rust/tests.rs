//! Вопросы к клиентской половине. Окно и сборку спрашивает proto (`stream.rs`) — тот же код, что
//! у моста, — здесь только то, что добавлено поверх: событие вокруг кадра, доказательство работы,
//! адрес моста, время окна приёма и NIP-44 по векторам целиком.

use super::*;
use nostr_sdk::hashes::sha256;

const SID: &str = "6f1c2a7e-0000-4000-8000-000000000001";

/// Устройство и мост, каждый со своим ключом.
fn pair() -> (Arc<FrameSealer>, Arc<FrameSealer>) {
    let client = generate_secret_key();
    let bridge = generate_secret_key();
    let client_hex = public_key_hex(client.clone()).unwrap();
    let bridge_hex = public_key_hex(bridge.clone()).unwrap();
    (
        FrameSealer::for_client(client, bridge_hex).unwrap(),
        FrameSealer::for_bridge(bridge, client_hex).unwrap(),
    )
}

#[test]
fn what_the_client_seals_the_bridge_opens_for_every_op() {
    let (client, bridge) = pair();
    let frames = [
        frame_open(SID.into(), "matrix-client.matrix.org".into(), 443, b"\x16\x03\x01hello".to_vec()),
        frame_data(SID.into(), 7, vec![0xff; 1000]),
        frame_ack(SID.into(), 12),
        frame_fin(SID.into(), 13),
        frame_rst(SID.into(), "closed".into()),
    ];
    for frame in frames {
        let event = client.seal(frame.clone(), 0).unwrap();
        assert_eq!(bridge.open(event).as_ref(), Some(&frame));
    }
    let opened = bridge.seal(frame_opened(SID.into()), 0).unwrap();
    assert_eq!(client.open(opened), Some(frame_opened(SID.into())));
}

#[test]
fn the_event_is_the_one_the_bridge_listens_for() {
    let (client, bridge) = pair();
    let event = Event::from_json(client.seal(frame_ack(SID.into(), 1), 0).unwrap()).unwrap();
    assert_eq!(event.kind, Kind::Custom(KIND_STREAM_CLIENT));
    assert_eq!(event.pubkey.to_hex(), client.our_public_key_hex());
    let p: Vec<String> = event.tags.public_keys().map(|key| key.to_hex()).collect();
    assert_eq!(p, vec![bridge.our_public_key_hex()]);
    assert!(!event.content.contains(SID), "the stream id travels sealed");
}

#[test]
fn a_frame_from_anyone_else_is_nothing() {
    let (client, bridge) = pair();
    let (stranger, _) = pair();
    // Чужой клиент, запечатанный для своего моста: другой автор и другой ключ.
    let foreign = stranger.seal(frame_ack(SID.into(), 1), 0).unwrap();
    assert_eq!(bridge.open(foreign), None);
    // Своё же событие обратно — не тот kind: клиент не слушает себя.
    let own = client.seal(frame_ack(SID.into(), 1), 0).unwrap();
    assert_eq!(client.open(own), None);
    assert_eq!(client.open("not json".into()), None);
}

#[test]
fn a_tampered_event_does_not_verify() {
    let (client, bridge) = pair();
    let event = client.seal(frame_ack(SID.into(), 1), 0).unwrap();
    let mut value: serde_json::Value = serde_json::from_str(&event).unwrap();
    value["created_at"] = serde_json::json!(value["created_at"].as_u64().unwrap() + 1);
    let tampered = value.to_string();
    assert_eq!(verify_event(tampered.clone()), None);
    assert_eq!(bridge.open(tampered), None);
    assert!(verify_event(event).is_some());
}

#[test]
fn a_frame_proto_would_refuse_does_not_go_out() {
    let (client, _) = pair();
    let mut open = frame_open(SID.into(), "matrix.org".into(), 443, b"x".to_vec());
    open.port = None;
    assert!(matches!(client.seal(open, 0), Err(TunnelError::Malformed { .. })));
    let mut ack = frame_ack(SID.into(), 1);
    ack.seq = Some(1);
    assert!(matches!(client.seal(ack, 0), Err(TunnelError::Malformed { .. })));
}

/// Доказательство работы — у самого события, а не в теге на честном слове: id начинается с нужного
/// числа нулей, подпись сходится, и мост его откроет.
#[test]
fn an_open_is_mined_to_what_was_asked() {
    let (client, bridge) = pair();
    let open = frame_open(SID.into(), "matrix.org".into(), 443, vec![0x16; 600]);
    for bits in [1, 8, 12] {
        let json = client.seal(open.clone(), bits).unwrap();
        let event = Event::from_json(&json).unwrap();
        assert!(event.check_pow(bits), "{bits} bits");
        event.verify().unwrap();
        let nonce = event.tags.iter().find(|tag| tag.kind() == TagKind::Nonce).expect("a nonce tag");
        assert_eq!(nonce.as_slice()[2], bits.to_string());
        assert_eq!(bridge.open(json), Some(open.clone()));
    }
}

#[test]
fn a_difficulty_past_the_ceiling_is_capped_not_mined() {
    let (client, _) = pair();
    // Мост, который просит 255 бит, сломан или враждебен; клиент майнит потолок, а не до смерти батареи.
    assert_eq!(pow_required(format!("pow {}", MAX_POW_BITS + 1)), None);
    let event = Event::from_json(client.seal(frame_ack(SID.into(), 1), 0).unwrap()).unwrap();
    assert!(event.tags.iter().all(|tag| tag.kind() != TagKind::Nonce), "no proof without being asked");
}

#[test]
fn the_filter_asks_for_the_bridge_answering_us() {
    let (client, bridge) = pair();
    let filter: serde_json::Value = serde_json::from_str(&client.subscription_filter(1_700_000_000)).unwrap();
    assert_eq!(filter["kinds"], serde_json::json!([KIND_STREAM_BRIDGE]));
    assert_eq!(filter["authors"], serde_json::json!([bridge.our_public_key_hex()]));
    assert_eq!(filter["#p"], serde_json::json!([client.our_public_key_hex()]));
    assert_eq!(filter["since"], serde_json::json!(1_700_000_000u64));
}

// ---- Адрес моста.

/// Мост разработчиков Искры: то, что поле адреса держит, пока там не напечатали другой.
const ISKRA_BRIDGE: &str = "npub1dwztf356tpg2lvzv8z74qgx68efhk0aq7yv2xr4tfq4pnsr92musk5k06v";

#[test]
fn an_npub_and_its_hex_are_the_same_bridge() {
    let BridgeAddress::Key { public_key_hex: hex } = parse_bridge_address(ISKRA_BRIDGE.into()) else {
        panic!("the Iskra bridge's own address does not parse");
    };
    assert_eq!(parse_bridge_address(format!("  {hex}\n")), BridgeAddress::Key { public_key_hex: hex.clone() });
    assert_eq!(parse_bridge_address(hex.to_uppercase()), BridgeAddress::Key { public_key_hex: hex.clone() });
    assert_eq!(npub(hex).unwrap(), ISKRA_BRIDGE);
}

#[test]
fn what_is_not_a_bridge_address_says_why() {
    assert_eq!(parse_bridge_address("".into()), BridgeAddress::Empty);
    assert_eq!(parse_bridge_address(" \n ".into()), BridgeAddress::Empty);
    let nsec = Keys::generate().secret_key().to_bech32().unwrap();
    assert_eq!(parse_bridge_address(nsec), BridgeAddress::LooksLikeASecretKey);
    assert_eq!(parse_bridge_address("matrix.org".into()), BridgeAddress::NotAKey);
    // Одна буква не та — контрольная сумма bech32 не сходится.
    let typo = ISKRA_BRIDGE.replacen('d', "e", 1);
    assert_eq!(parse_bridge_address(typo), BridgeAddress::NotAKey);
    // Шестьдесят четыре знака hex, но не точка на кривой: x больше модуля поля.
    assert_eq!(parse_bridge_address("ff".repeat(32)), BridgeAddress::NotAKey);
}

#[test]
fn the_device_key_is_stable_and_its_own() {
    let secret = generate_secret_key();
    assert_eq!(secret.len(), 32);
    assert_eq!(public_key_hex(secret.clone()).unwrap(), public_key_hex(secret).unwrap());
    assert_ne!(generate_secret_key(), generate_secret_key());
    assert!(matches!(public_key_hex(vec![0; 3]), Err(TunnelError::NotAKey)));
}

// ---- Окна: время приходит снаружи.

#[test]
fn the_gap_timeout_is_counted_in_the_callers_millis() {
    let inbound = InboundReassembly::new();
    let gap = tunnel_limits().gap_timeout_millis;
    assert_eq!(inbound.accept(1, vec![1; 10], 1_000).unwrap(), Vec::<Vec<u8>>::new());
    assert!(!inbound.gap_expired(1_000 + gap - 1));
    assert!(inbound.gap_expired(1_000 + gap));
    assert_eq!(inbound.accept(0, vec![0; 10], 1_000 + gap).unwrap(), vec![vec![0; 10], vec![1; 10]]);
    assert!(!inbound.gap_expired(1_000_000));
}

#[test]
fn a_violation_comes_back_as_its_rst_word() {
    let outbound = OutboundWindow::new();
    outbound.push(10);
    match outbound.ack(5) {
        Err(TunnelError::Violation { reason }) => assert_eq!(reason, "protocol"),
        other => panic!("{other:?}"),
    }
    let inbound = InboundReassembly::new();
    match inbound.accept(10_000, vec![0], 0) {
        Err(TunnelError::Violation { reason }) => assert_eq!(reason, "window"),
        other => panic!("{other:?}"),
    }
}

// ---- NIP-44, векторы целиком (включая сообщение на 65535 байт).

fn vectors() -> serde_json::Value {
    let text = include_str!("../src/test/resources/nip44.vectors.json");
    serde_json::from_str::<serde_json::Value>(text).unwrap()["v2"].clone()
}

fn hex(value: &serde_json::Value) -> Vec<u8> {
    let text = value.as_str().unwrap();
    (0..text.len()).step_by(2).map(|i| u8::from_str_radix(&text[i..i + 2], 16).unwrap()).collect()
}

fn s(value: &serde_json::Value) -> String {
    value.as_str().unwrap().to_string()
}

#[test]
fn nip44_conversation_keys() {
    let v = vectors();
    for case in v["valid"]["get_conversation_key"].as_array().unwrap() {
        let key = nip44_conversation_key(s(&case["sec1"]), s(&case["pub2"])).unwrap();
        assert_eq!(key, hex(&case["conversation_key"]));
    }
    for case in v["invalid"]["get_conversation_key"].as_array().unwrap() {
        assert!(nip44_conversation_key(s(&case["sec1"]), s(&case["pub2"])).is_err(), "{}", case["note"]);
    }
}

#[test]
fn nip44_encrypt_and_decrypt() {
    let v = vectors();
    for case in v["valid"]["encrypt_decrypt"].as_array().unwrap() {
        let sec2 = SecretKey::from_hex(case["sec2"].as_str().unwrap()).unwrap();
        let pub2 = Keys::new(sec2).public_key().to_hex();
        let key = nip44_conversation_key(s(&case["sec1"]), pub2).unwrap();
        assert_eq!(key, hex(&case["conversation_key"]));
        let payload = nip44_encrypt_with_nonce(key.clone(), hex(&case["nonce"]), s(&case["plaintext"])).unwrap();
        assert_eq!(payload, s(&case["payload"]));
        assert_eq!(nip44_decrypt(key, payload).unwrap(), s(&case["plaintext"]));
    }
}

/// Сообщения на 65535 байт nostr 0.44.8 — та же версия, что у моста, — не шифрует: у него потолок
/// 65536 − 128 (`MAX_SUPPORTED_PLAINTEXT_SIZE` в `nip44/v2.rs`), хотя NIP-44 и `MAX_NIP44_PLAINTEXT`
/// в proto говорят 65535. Туннеля это не касается — полный сегмент, 24 КиБ, это ~33 КБ JSON, вдвое
/// ниже обоих потолков, — но вектор не пропускается молча: тест держит, что потолок именно такой, и
/// упадёт, когда nostr его поднимет, — тогда эта ветка уходит.
const NOSTR_PLAINTEXT_CEILING: usize = 65_536 - 128;

#[test]
fn nip44_long_messages() {
    let v = vectors();
    let sha = |bytes: &[u8]| sha256::Hash::hash(bytes).to_byte_array().to_vec();
    for case in v["valid"]["encrypt_decrypt_long_msg"].as_array().unwrap() {
        let plaintext = s(&case["pattern"]).repeat(case["repeat"].as_u64().unwrap() as usize);
        assert_eq!(sha(plaintext.as_bytes()), hex(&case["plaintext_sha256"]));
        let key = hex(&case["conversation_key"]);
        if plaintext.len() > NOSTR_PLAINTEXT_CEILING {
            let refused = nip44_encrypt_with_nonce(key, hex(&case["nonce"]), plaintext);
            assert!(matches!(refused, Err(TunnelError::Malformed { .. })), "nostr took {} bytes", 65_535);
            continue;
        }
        let payload = nip44_encrypt_with_nonce(key.clone(), hex(&case["nonce"]), plaintext.clone()).unwrap();
        assert_eq!(sha(payload.as_bytes()), hex(&case["payload_sha256"]));
        assert_eq!(nip44_decrypt(key, payload).unwrap(), plaintext);
    }
}

#[test]
fn nip44_refusals() {
    let v = vectors();
    let key = vec![0x11; 32];
    for length in v["invalid"]["encrypt_msg_lengths"].as_array().unwrap() {
        let plaintext = "a".repeat(length.as_u64().unwrap() as usize);
        assert!(nip44_encrypt_with_nonce(key.clone(), vec![0; 32], plaintext).is_err(), "{length}");
    }
    for case in v["invalid"]["decrypt"].as_array().unwrap() {
        assert!(nip44_decrypt(hex(&case["conversation_key"]), s(&case["payload"])).is_err(), "{}", case["note"]);
    }
}
